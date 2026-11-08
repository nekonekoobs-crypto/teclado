package com.example.stickerboard

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import java.io.File
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    private lateinit var prefs: SharedPreferences
    private lateinit var repo: StickerRepository
    private val suggester = StickerSuggester()
    private val translator = TranslatorManager()
    private val handler = Handler(Looper.getMainLooper())

    private var theme = Themes.all[0]
    private var shift = false
    private var symbols = false
    private var pairIndex = 0

    private lateinit var root: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var translateBar: LinearLayout
    private lateinit var keys: LinearLayout
    private lateinit var pairButton: TextView

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        repo = StickerRepository(this)
        theme = Themes.all[prefs.getInt("theme", 0).coerceIn(0, Themes.all.lastIndex)]
        pairIndex = prefs.getInt("pair", 0).coerceIn(0, translator.pairs.lastIndex)
    }

    override fun onDestroy() {
        translator.close()
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
        buildViews()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = false
        symbols = false
        if (::root.isInitialized) {
            renderKeys()
            refreshSuggestions()
        }
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
        if (::strip.isInitialized) refreshSuggestions()
    }

    // ---------- Construcción de la vista ----------

    private fun buildViews() {
        root.removeAllViews()
        root.setBackgroundColor(theme.bg)

        // Barra superior: sugerencias + traductor + tema
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
        val h = (dm.heightPixels * fraction - dp(52)) / 4f
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
            val shiftKey = key(if (symbols) "=\\<" else if (shift) "⇧" else "⇪", 1.4f, special = true) {
                if (!symbols) { shift = !shift; renderKeys() }
            }
            r.addView(shiftKey)
            rows[2].forEach { r.addView(charKey(it)) }
            r.addView(backspaceKey())
        })
        keys.addView(row(h) { r ->
            r.addView(key(if (symbols) "ABC" else "?123", 1.5f, special = true) { symbols = !symbols; renderKeys() })
            r.addView(key(",", 1f) { type(",") })
            r.addView(key("espacio", 4.5f) { type(" ") })
            r.addView(key(".", 1f) { type(".") })
            r.addView(key("⏎", 1.5f, accent = true) { enter() })
        })
    }

    private fun row(h: Int, fill: (LinearLayout) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, h)
        fill(this)
    }

    private fun spacer(w: Float) = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, -1, w) }

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
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                    handler.postDelayed(repeat, 400)
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
        if (shift && !symbols) { shift = false; renderKeys() }
        refreshSuggestions()
    }

    private fun enter() {
        if (!sendDefaultEditorAction(true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    // ---------- Stickers ----------

    private fun refreshSuggestions() {
        if (!::strip.isInitialized) return
        val before = currentInputConnection?.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val list = suggester.suggest(before, repo.all())
        strip.removeAllViews()
        if (list.isEmpty()) {
            strip.addView(TextView(this).apply {
                text = if (repo.packCount() == 0) "Importa paquetes de stickers en la app" else "Escribe para ver stickers…"
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
                    if (!ok) Toast.makeText(context, "Esta app no acepta stickers desde el teclado", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    /** Muestra el sticker; si es webp/gif animado (Android 9+) se reproduce en bucle. */
    private fun ImageView.loadSticker(f: File) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f))
                setImageDrawable(d)
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
                return
            } catch (_: Exception) {
            }
        }
        setImageURI(Uri.fromFile(f))
    }

    // ---------- Temas ----------

    private fun nextTheme() {
        val i = (Themes.all.indexOf(theme) + 1) % Themes.all.size
        theme = Themes.all[i]
        prefs.edit().putInt("theme", i).apply()
        buildViews()
        refreshSuggestions()
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
                refreshSuggestions()
            },
            onError = {
                Toast.makeText(this, "No se pudo traducir (¿sin internet para bajar el idioma?)", Toast.LENGTH_LONG).show()
            })
    }
}
