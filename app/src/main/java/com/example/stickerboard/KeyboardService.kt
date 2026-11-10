package com.example.stickerboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class KeyboardService : InputMethodService() {

    private data class SpellInfo(val suggestions: List<String>, val typo: Boolean, val recommended: Boolean)
    private data class Correction(val original: String, val fix: String, val boundary: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var repo: StickerRepository
    private lateinit var predictor: WordPredictor
    private lateinit var spell: SpellHelper
    private val suggester = StickerSuggester()
    private val translator = TranslatorManager()
    private val handler = Handler(Looper.getMainLooper())
    private val spellCache = HashMap<String, SpellInfo>()
    private var stickers: List<Sticker> = emptyList()

    private var theme = Themes.all[0]
    private var shift = false
    private var capsLock = false
    // Sugerencia que se conserva después de enviar el texto, hasta que se envíe el sticker
    private var pendingStickers: List<Sticker> = emptyList()
    private var showPending = false
    private var hadText = false
    private var lastManualDelete = 0L
    private var lastFieldKey = ""
    private var lastShiftTap = 0L
    private var hapticOn = true
    private var symbols = false
    private var pairIndex = 0

    private var predictiveOn = true
    private var autocorrectOn = true
    private var builtPredictive = true
    private var fieldAllows = true   // campo de texto normal (no contraseña, correo ni URL)
    private var fieldLearns = true   // el campo no pidió "sin aprendizaje personalizado"
    private var lastCorrection: Correction? = null
    private var pendingSpellWord: String? = null
    private val spellRunnable = Runnable { pendingSpellWord?.let { spell.check(it) } }

    private lateinit var root: LinearLayout
    private lateinit var wordBar: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var translateBar: LinearLayout
    private lateinit var keys: LinearLayout
    private lateinit var pairButton: TextView

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        repo = StickerRepository(this)
        predictor = WordPredictor.get(this)
        spell = SpellHelper(this) { word, suggestions, typo, recommended ->
            if (spellCache.size > 300) spellCache.clear()
            spellCache[word.lowercase()] = SpellInfo(suggestions, typo, recommended)
            refreshWordBar()
        }
        spell.open()
        try {
            @Suppress("DEPRECATION")
            setBackDisposition(BACK_DISPOSITION_WILL_NOT_DISMISS)
        } catch (_: Exception) {
        }
        theme = Themes.all[prefs.getInt("theme", 0).coerceIn(0, Themes.all.lastIndex)]
        pairIndex = prefs.getInt("pair", 0).coerceIn(0, translator.pairs.lastIndex)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        translator.close()
        spell.close()
        predictor.save()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Ajuste a barra de navegación / gestos para que no tape las teclas
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + dp(2))
            insets
        }
        readSettings()
        buildViews()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false
        capsLock = false
        symbols = false
        lastCorrection = null
        spellCache.clear()
        stickers = repo.all()
        val fieldKey = "${info?.packageName}/${info?.fieldId}"
        if (fieldKey != lastFieldKey) {
            lastFieldKey = fieldKey
            pendingStickers = emptyList()
            showPending = false
            hadText = false
        }
        readFieldType(info)
        if (!::root.isInitialized) return
        val before = predictiveOn
        readSettings()
        if (before != predictiveOn || builtPredictive != predictiveOn) buildViews() else renderKeys()
        refreshAll()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lastCorrection = null
        predictor.save()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::root.isInitialized) renderKeys() // vuelve a calcular alturas (giro, plegable, ventana dividida)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::strip.isInitialized) {
            refreshSuggestions()
            refreshWordBar()
        }
    }

    // ---------- Ajustes y tipo de campo ----------

    private fun readSettings() {
        predictiveOn = prefs.getBoolean("predictive", true)
        autocorrectOn = prefs.getBoolean("autocorrect", true)
        hapticOn = prefs.getBoolean("haptic", true)
    }

    /** No se predice, corrige ni aprende en contraseñas, correos o direcciones web. */
    private fun readFieldType(info: EditorInfo?) {
        val type = info?.inputType ?: 0
        val cls = type and InputType.TYPE_MASK_CLASS
        val v = type and InputType.TYPE_MASK_VARIATION
        val sensitive = v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            v == InputType.TYPE_TEXT_VARIATION_URI
        fieldAllows = cls == InputType.TYPE_CLASS_TEXT && !sensitive
        val noLearning = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        fieldLearns = fieldAllows && !noLearning
    }

    // ---------- Construcción de la vista ----------

    private fun buildViews() {
        root.removeAllViews()
        root.setBackgroundColor(theme.bg)
        builtPredictive = predictiveOn

        // Barra de palabras sugeridas
        wordBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (predictiveOn) root.addView(wordBar, LinearLayout.LayoutParams(-1, dp(38)))

        // Barra superior: sugerencias de stickers + traductor + tema
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(strip)
        top.addView(scroll, LinearLayout.LayoutParams(0, dp(52), 1f))
        top.addView(iconButton("🌐") { toggleTranslate() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(iconButton("🎨") { nextTheme() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(52)))

        // Barra del traductor (oculta por defecto)
        translateBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(6), 0, dp(6), dp(4))
        }
        pairButton = labelButton(pairLabel()) { cyclePair() }
        translateBar.addView(pairButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
        translateBar.addView(
            labelButton("Traducir texto", accent = true) { translateField() },
            LinearLayout.LayoutParams(0, dp(40), 2f)
        )
        root.addView(translateBar, LinearLayout.LayoutParams(-1, -2))

        keys = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), 0, dp(3), dp(4))
        }
        root.addView(keys, LinearLayout.LayoutParams(-1, -2))
        renderKeys()
    }

    private fun keyHeight(): Int {
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val fraction = if (landscape) 0.52f else 0.36f
        val bars = dp(52) + if (predictiveOn) dp(38) else 0
        val h = (dm.heightPixels * fraction - bars) / 4f
        return h.toInt().coerceIn(dp(34), dp(60))
    }

    private fun renderKeys() {
        keys.removeAllViews()
        val h = keyHeight()
        val rows = if (symbols) listOf("1234567890", "@#$%&-+()/", "*\"':;!?")
        else listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")

        keys.addView(row(h) { r ->
            rows[0].forEach { r.addView(charKey(it)) }
        })
        keys.addView(row(h) { r ->
            r.addView(spacer(0.25f)); rows[1].forEach { r.addView(charKey(it)) }; r.addView(spacer(0.25f))
        })
        keys.addView(row(h) { r ->
            val shiftLabel = when {
                symbols -> "=\\<"
                capsLock -> "⇪"   // mayúsculas fijas
                shift -> "⬆"      // una sola mayúscula
                else -> "⇧"
            }
            val shiftKey = key(shiftLabel, 1.4f, special = true, accent = !symbols && (shift || capsLock)) {
                if (!symbols) onShiftTap()
            }
            r.addView(shiftKey)
            rows[2].forEach { r.addView(charKey(it)) }
            r.addView(backspaceKey())
        })
        keys.addView(row(h) { r ->
            r.addView(key(if (symbols) "ABC" else "?123", 1.5f, special = true) { symbols = !symbols; renderKeys() })
            r.addView(key(",", 1f) { boundary(",") })
            r.addView(key("espacio", 4.5f) { boundary(" ") })
            r.addView(key(".", 1f) { boundary(".") })
            r.addView(key("⏎", 1.5f, accent = true) { enter() })
        })
    }

    private fun row(h: Int, fill: (LinearLayout) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, h)
        fill(this)
    }

    private fun spacer(w: Float) = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -1, w) }

    /** Un toque: una mayúscula. Doble toque: mayúsculas fijas. Con las fijas activas, un toque las quita. */
    private fun onShiftTap() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shift = false }
            now - lastShiftTap < 350 -> { capsLock = true; shift = true }
            else -> shift = !shift
        }
        lastShiftTap = now
        renderKeys()
    }

    private fun buzz(v: View) {
        if (hapticOn) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun charKey(c: Char): View {
        val label = if (shift && !symbols) c.uppercaseChar().toString() else c.toString()
        return key(label, 1f) { type(label) }
    }

    private fun key(
        label: String, weight: Float, special: Boolean = false, accent: Boolean = false, onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (label.length > 2) 14f else 19f
        setTextColor(if (accent) Color.WHITE else theme.keyText)
        background = round(if (accent) theme.accent else if (special) theme.special else theme.key, 8)
        layoutParams = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
        setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) buzz(v)
            false
        }
        setOnClickListener { onClick() }
    }

    private fun backspaceKey(): View {
        val k = key("⌫", 1.4f, special = true) { }
        val repeat = object : Runnable {
            override fun run() {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                handler.postDelayed(this, 60)
            }
        }
        k.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    buzz(k)
                    lastManualDelete = System.currentTimeMillis()
                    // Borrar justo después de una autocorrección devuelve la palabra original
                    if (!revertCorrection()) {
                        lastCorrection = null
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                        handler.postDelayed(repeat, 400)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(repeat)
            }
            true
        }
        return k
    }

    private fun iconButton(emoji: String, onClick: () -> Unit) = TextView(this).apply {
        text = emoji
        textSize = 20f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun labelButton(label: String, accent: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(if (accent) Color.WHITE else theme.keyText)
        background = round(if (accent) theme.accent else theme.key, 10)
        setOnClickListener { onClick() }
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Escritura ----------

    private fun type(s: String) {
        currentInputConnection?.commitText(s, 1)
        lastCorrection = null
        if (shift && !capsLock && !symbols) { shift = false; renderKeys() }
        afterChange()
    }

    /** Espacio o signo que termina una palabra: aquí se autocorrige y se aprende. */
    private fun boundary(ch: String) {
        val ic = currentInputConnection ?: return
        val word = if (fieldAllows) wordBeforeCursor() else ""
        var corrected = false
        if (word.isNotEmpty()) {
            val prev = previousWord()
            val fix = if (autocorrectOn) autoFix(word) else null
            if (fix != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(fix + ch, 1)
                ic.endBatchEdit()
                lastCorrection = Correction(word, fix, ch)
                corrected = true
            } else {
                ic.commitText(ch, 1)
            }
            if (fieldLearns && predictiveOn) predictor.learn(fix ?: word, prev)
        } else {
            ic.commitText(ch, 1)
        }
        if (!corrected) lastCorrection = null
        afterChange()
    }

    private fun enter() {
        if (fieldAllows && autocorrectOn) boundary("")
        lastCorrection = null
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun afterChange() {
        refreshSuggestions()
        refreshWordBar()
        scheduleSpell()
    }

    // ---------- Texto predictivo y corrección ----------

    private fun textBefore(n: Int): String = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()

    private fun wordBeforeCursor(): String = WORD_END.find(textBefore(40))?.value ?: ""

    private fun previousWord(): String? {
        val before = textBefore(80)
        val w = WORD_END.find(before)?.value ?: ""
        return PREV_WORD.find(before.dropLast(w.length))?.groupValues?.get(1)
    }

    private fun atSentenceStart(textBefore: String): Boolean {
        if (textBefore.isEmpty() || textBefore.endsWith("\n")) return true
        val t = textBefore.trimEnd()
        return t.isEmpty() || t.last() in ".!?"
    }

    private fun matchCase(original: String, candidate: String): String = when {
        original.length > 1 && original.any { it.isLetter() } && original.all { !it.isLetter() || it.isUpperCase() } ->
            candidate.uppercase()
        original.isNotEmpty() && original.first().isUpperCase() -> candidate.replaceFirstChar { it.uppercase() }
        else -> candidate
    }

    /** Solo corrige si el corrector del sistema está seguro (sugerencia recomendada) y la palabra no es conocida. */
    private fun autoFix(word: String): String? {
        if (word.length < 3 || predictor.isKnown(word)) return null
        if (word.drop(1).any { it.isUpperCase() }) return null // siglas, nombres raros
        if (word.first().isUpperCase() && !atSentenceStart(textBefore(80).dropLast(word.length))) return null // nombres propios
        val info = spellCache[word.lowercase()] ?: return null
        if (!info.typo || !info.recommended || info.suggestions.isEmpty()) return null
        val fix = info.suggestions.first()
        if (fix.equals(word, ignoreCase = true)) return null
        return matchCase(word, fix)
    }

    private fun revertCorrection(): Boolean {
        val c = lastCorrection ?: return false
        lastCorrection = null
        val ic = currentInputConnection ?: return false
        val committed = c.fix + c.boundary
        if (!textBefore(committed.length).endsWith(committed)) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(committed.length, 0)
        ic.commitText(c.original + c.boundary, 1)
        ic.endBatchEdit()
        predictor.markKnown(c.original) // no volver a corregir esta palabra
        afterChange()
        return true
    }

    private fun scheduleSpell() {
        handler.removeCallbacks(spellRunnable)
        if (!(predictiveOn || autocorrectOn) || !fieldAllows) return
        val w = wordBeforeCursor()
        if (w.length < 3 || predictor.isKnown(w) || spellCache.containsKey(w.lowercase())) return
        pendingSpellWord = w
        handler.postDelayed(spellRunnable, 60)
    }

    private fun refreshWordBar() {
        if (!::wordBar.isInitialized || !builtPredictive) return
        wordBar.removeAllViews()
        if (!fieldAllows) return

        val word = wordBeforeCursor()
        val items = ArrayList<Pair<String, Boolean>>() // texto, resaltar (corrección)
        if (word.isNotEmpty()) {
            if (!predictor.isKnown(word)) {
                val info = spellCache[word.lowercase()]
                val fixes = when {
                    info != null && info.typo && info.suggestions.isNotEmpty() -> info.suggestions.take(2)
                    !spell.available -> predictor.suggestFix(word, 2)
                    else -> emptyList()
                }
                fixes.forEach { items.add(matchCase(word, it) to true) }
            }
            predictor.complete(word, 4).forEach { c ->
                val t = matchCase(word, c)
                if (items.none { it.first.equals(t, ignoreCase = true) }) items.add(t to false)
            }
        } else {
            val startCap = atSentenceStart(textBefore(80))
            predictor.next(previousWord(), 4).forEach {
                items.add((if (startCap) it.replaceFirstChar { c -> c.uppercase() } else it) to false)
            }
        }

        items.take(3).forEachIndexed { i, (text, highlight) ->
            if (i > 0) {
                wordBar.addView(View(this).apply {
                    setBackgroundColor(theme.keyText)
                    alpha = 0.2f
                    layoutParams = LinearLayout.LayoutParams(dp(1), dp(20))
                })
            }
            wordBar.addView(chip(text, highlight) { applySuggestion(text) })
        }
    }

    private fun chip(text: String, highlight: Boolean, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 16f
        maxLines = 1
        setTextColor(if (highlight) theme.accent else theme.keyText)
        layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        setOnClickListener { onClick() }
    }

    private fun applySuggestion(text: String) {
        val ic = currentInputConnection ?: return
        val word = wordBeforeCursor()
        val prev = previousWord()
        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        if (fieldLearns) predictor.learn(text, prev)
        lastCorrection = null
        afterChange()
    }

    // ---------- Stickers ----------

    private fun refreshAll() {
        refreshSuggestions()
        refreshWordBar()
    }

    private fun refreshSuggestions() {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        if (!fieldAllows) return // no se lee el texto de contraseñas, correos ni direcciones
        val before = textBefore(60)
        val after = currentInputConnection?.getTextAfterCursor(1, 0)?.toString().orEmpty()
        var list = suggester.suggest(before, stickers)
        if (before.isNotEmpty() || after.isNotEmpty()) {
            // Hay texto: se recuerdan las sugerencias actuales
            hadText = true
            showPending = false
            pendingStickers = list
        } else {
            // El campo quedó vacío. Si había texto y no fui yo quien lo borró, el mensaje se envió:
            // se siguen mostrando los stickers hasta que se envíe uno.
            val manual = System.currentTimeMillis() - lastManualDelete < 1500
            if (hadText && !manual && pendingStickers.isNotEmpty()) showPending = true
            hadText = false
            if (showPending) list = pendingStickers
        }
        if (list.isEmpty()) {
            strip.addView(TextView(this).apply {
                text = if (stickers.isEmpty()) "Importa paquetes de stickers en la app" else "Escribe para ver stickers…"
                setTextColor(theme.keyText)
                alpha = 0.6f
                textSize = 13f
                setPadding(dp(10), 0, 0, 0)
            })
            return
        }
        list.forEach { s ->
            strip.addView(ImageView(this).apply {
                loadSticker(s.file)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
                setOnClickListener {
                    val ok = StickerSender.send(this@KeyboardService, currentInputConnection, currentInputEditorInfo, s.file)
                    if (!ok) Toast.makeText(context, StickerSender.lastError.ifEmpty { "No se pudo enviar el sticker" }, Toast.LENGTH_LONG).show()
                    else {
                        pendingStickers = emptyList()
                        showPending = false
                        refreshSuggestions()
                    }
                }
            })
        }
    }

    // ---------- Temas ----------

    private fun nextTheme() {
        val i = (Themes.all.indexOf(theme) + 1) % Themes.all.size
        theme = Themes.all[i]
        prefs.edit().putInt("theme", i).apply()
        buildViews()
        refreshAll()
    }

    // ---------- Traductor ----------

    private fun toggleTranslate() {
        translateBar.visibility = if (translateBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        renderKeys()
    }

    private fun pairLabel(): String {
        val (a, b) = translator.pairs[pairIndex]
        return "${a.uppercase()} → ${b.uppercase()}"
    }

    private fun cyclePair() {
        pairIndex = (pairIndex + 1) % translator.pairs.size
        prefs.edit().putInt("pair", pairIndex).apply()
        pairButton.text = pairLabel()
    }

    private fun translateField() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(2000, 0)?.toString().orEmpty()
        val full = before + after
        if (full.isBlank()) return
        Toast.makeText(this, "Traduciendo…", Toast.LENGTH_SHORT).show()
        translator.translate(full, translator.pairs[pairIndex],
            onResult = { result ->
                currentInputConnection?.apply {
                    beginBatchEdit()
                    deleteSurroundingText(before.length, after.length)
                    commitText(result, 1)
                    endBatchEdit()
                }
                lastCorrection = null
                refreshAll()
            },
            onError = {
                Toast.makeText(this, "No se pudo traducir (¿sin internet para bajar el idioma?)", Toast.LENGTH_LONG).show()
            })
    }

    companion object {
        private val WORD_END = Regex("[\\p{L}'’]+$")
        private val PREV_WORD = Regex("([\\p{L}'’]+)\\s+$")
    }
}
