package com.example.stickerboard

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Nombrado rápido: muestra un sticker grande a la vez con botones de estados de ánimo y acciones.
 * Se tocan una o varias y se pasa al siguiente. Los nombres se guardan en el paquete (labels.json).
 */
class QuickNameActivity : ComponentActivity() {

    private lateinit var repo: StickerRepository
    private var files: List<File> = emptyList()
    private var index = 0
    private val selected = linkedSetOf<String>()
    private val chipButtons = LinkedHashMap<String, Button>()

    private lateinit var image: ImageView
    private lateinit var progress: TextView
    private lateinit var custom: EditText

    // Palabras que el teclado ya sabe relacionar con emojis (ver StickerSuggester)
    private val options = listOf(
        "feliz", "risa", "amor", "beso", "abrazo", "gracias", "hola", "adiós", "ok", "sí", "no",
        "triste", "llorar", "enojado", "susto", "pena", "wow", "pensando", "cansado", "sueño",
        "hambre", "fiesta", "cumpleaños", "calor", "frío", "ayuda", "porfa"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StickerRepository(this)

        val paths = intent.getStringArrayListExtra("paths")
        files = if (paths != null) paths.map { File(it) }.filter { it.exists() }
        else repo.all().filter { it.label.isBlank() }.map { it.file }

        if (files.isEmpty()) {
            finish()
            return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }
        progress = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER }
        col.addView(progress)

        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        col.addView(image, LinearLayout.LayoutParams(-1, dp(230)))

        col.addView(TextView(this).apply {
            text = "¿Qué expresa? (puedes elegir varios)"
            textSize = 14f
            alpha = 0.7f
            setPadding(0, dp(8), 0, dp(8))
        })

        val grid = GridLayout(this).apply { columnCount = 3 }
        val cellW = (resources.displayMetrics.widthPixels - dp(32)) / 3
        options.forEach { word ->
            val b = Button(this).apply {
                text = word
                isAllCaps = false
                textSize = 13f
                setOnClickListener { toggle(word) }
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellW
                    height = dp(46)
                }
            }
            chipButtons[word] = b
            grid.addView(b)
        }
        col.addView(grid)

        custom = EditText(this).apply {
            hint = "Otro nombre (opcional, separa con comas)"
            setSingleLine()
        }
        col.addView(custom)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Omitir"
            setOnClickListener { advance(save = false) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Guardar y siguiente"
            setOnClickListener { advance(save = true) }
        }, LinearLayout.LayoutParams(0, -2, 2f))
        col.addView(row)
        col.addView(Button(this).apply {
            text = "Terminar"
            setOnClickListener { finish() }
        })

        setContentView(ScrollView(this).apply { addView(col) })
        show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toggle(word: String) {
        if (!selected.remove(word)) selected.add(word)
        paintChips()
    }

    private fun paintChips() {
        chipButtons.forEach { (word, b) ->
            val on = word in selected
            b.text = if (on) "✓ $word" else word
            b.alpha = if (on) 1f else 0.75f
            b.setTextColor(if (on) Color.parseColor("#7C5CFF") else Color.BLACK)
        }
    }

    private fun show() {
        if (index >= files.size) {
            finish()
            return
        }
        val f = files[index]
        progress.text = "Sticker ${index + 1} de ${files.size}"
        image.loadSticker(f)

        val existing = repo.all().firstOrNull { it.file == f }?.label.orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        selected.clear()
        selected.addAll(existing.filter { it in options })
        custom.setText(existing.filter { it !in options }.joinToString(", "))
        paintChips()
    }

    private fun advance(save: Boolean) {
        if (save) {
            val extra = custom.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val label = (selected.toList() + extra).distinct().joinToString(", ")
            repo.setLabel(files[index], label)
        }
        index++
        show()
    }

    companion object {
        fun intentFor(context: android.content.Context, files: List<File>? = null): Intent =
            Intent(context, QuickNameActivity::class.java).apply {
                if (files != null) putStringArrayListExtra("paths", ArrayList(files.map { it.absolutePath }))
            }
    }
}
