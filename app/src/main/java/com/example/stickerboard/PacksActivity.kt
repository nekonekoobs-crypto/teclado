package com.example.stickerboard

import android.app.AlertDialog
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
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Lista los paquetes con sus stickers y el nombre de cada uno. Tocar un sticker abre la imagen y
 * permite editar su nombre (estado de ánimo o acción): el teclado usa esas palabras para sugerirlo.
 */
class PacksActivity : ComponentActivity() {

    private lateinit var repo: StickerRepository
    private lateinit var list: LinearLayout
    private var targetPack: Pack? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val pack = targetPack
        targetPack = null
        if (pack == null || uris.isEmpty()) return@registerForActivityResult
        val files = repo.addStickers(pack.dir, uris)
        if (files.isNotEmpty()) startActivity(QuickNameActivity.intentFor(this, files)) // nombrar lo recién agregado
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StickerRepository(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 48)
        }
        setContentView(ScrollView(this).apply { addView(list) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply { text = "Mis paquetes"; textSize = 26f })
        list.addView(TextView(this).apply {
            text = "Toca un sticker para ver la imagen y editar su nombre. El teclado usa esas palabras " +
                "(por ejemplo: feliz, abrazo, cumpleaños) para sugerirlo mientras escribes."
            textSize = 14f
            alpha = 0.7f
            setPadding(0, 8, 0, 8)
        })

        val packs = repo.packs()
        if (packs.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Aún no tienes paquetes. Impórtalos desde la pantalla principal."
                textSize = 16f
                setPadding(0, 32, 0, 0)
            })
            return
        }

        val unnamed = packs.flatMap { it.stickers }.count { it.label.isBlank() }
        if (unnamed > 0) {
            list.addView(Button(this).apply {
                text = "Nombrado rápido ($unnamed sin nombre)"
                setOnClickListener { startActivity(QuickNameActivity.intentFor(this@PacksActivity)) }
            })
        }

        val cell = (resources.displayMetrics.widthPixels - 64) / 3
        packs.forEach { pack ->
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 40, 0, 8)
            }
            header.addView(
                TextView(this).apply { text = "${pack.name} (${pack.stickers.size})"; textSize = 18f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            header.addView(Button(this).apply {
                text = "+ Agregar"
                setOnClickListener {
                    targetPack = pack
                    pickImages.launch(arrayOf("image/*"))
                }
            })
            header.addView(Button(this).apply {
                text = "Borrar"
                setOnClickListener { confirmDelete(pack) }
            })
            list.addView(header)

            val grid = GridLayout(this).apply { columnCount = 3 }
            pack.stickers.forEach { s ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(4, 4, 4, 12)
                    setOnClickListener { editSticker(s) }
                    layoutParams = GridLayout.LayoutParams().apply { width = cell }
                }
                item.addView(ImageView(this).apply {
                    loadSticker(s.file)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(8, 8, 8, 8)
                    layoutParams = LinearLayout.LayoutParams(cell - 8, cell - 8)
                })
                item.addView(TextView(this).apply {
                    text = s.label.ifBlank { "(sin nombre)" }
                    textSize = 12f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    alpha = if (s.label.isBlank()) 0.5f else 1f
                })
                val info = WhatsAppFormat.inspect(s.file)
                item.addView(TextView(this).apply {
                    text = info.text
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(if (info.ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
                })
                grid.addView(item)
            }
            list.addView(grid)
        }
    }

    private fun editSticker(s: Sticker) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        box.addView(ImageView(this).apply {
            loadSticker(s.file)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
        })
        val fmt = WhatsAppFormat.inspect(s.file)
        box.addView(TextView(this).apply {
            text = "WhatsApp: ${fmt.text}"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (fmt.ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        })
        box.addView(TextView(this).apply {
            text = "Nombre (estado de ánimo o acción). Separa varias palabras con comas."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(8), 0, 0)
        })
        val input = EditText(this).apply {
            setText(s.label)
            hint = "feliz, abrazo, cumpleaños"
            setSingleLine()
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Editar nombre")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                repo.setLabel(s.file, input.text.toString())
                render()
            }
            .setNeutralButton("Borrar sticker") { _, _ ->
                repo.deleteSticker(s.file)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(pack: Pack) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar paquete?")
            .setMessage(pack.name)
            .setPositiveButton("Borrar") { _, _ ->
                repo.deletePack(pack.dir)
                render()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
