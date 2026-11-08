package com.example.stickerboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var repo: StickerRepository
    private lateinit var status: TextView

    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val n = repo.importImages(uris)
        toast("$n stickers importados")
        refresh()
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val n = repo.importZip(uri, "paquete")
            toast(if (n > 0) "$n stickers importados" else "No encontré imágenes en ese archivo")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StickerRepository(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        col.addView(TextView(this).apply { text = "StickerBoard"; textSize = 28f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 32) }
        col.addView(status)

        fun button(label: String, action: () -> Unit) = col.addView(Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        })

        button("1. Activar teclado") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("2. Elegir teclado") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        button("Importar paquete (.zip / .wastickers)") { pickZip.launch(arrayOf("*/*")) }
        button("Importar imágenes sueltas") { pickImages.launch(arrayOf("image/*")) }

        val link = EditText(this).apply {
            hint = "Pega el enlace de un paquete de Sigstick"
            setSingleLine()
        }
        col.addView(link)
        button("Descargar paquete desde enlace") {
            val url = link.text.toString().trim()
            if (url.isEmpty()) {
                toast("Pega primero un enlace")
            } else {
                toast("Descargando…")
                PackLinkImporter.import(repo, url) { r ->
                    if (r.error != null) toast(r.error)
                    else toast("${r.total} stickers (${r.animated} animados" +
                        if (r.thumbsOnly > 0) ", ${r.thumbsOnly} solo en miniatura)" else ")")
                    refresh()
                }
            }
        }

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private fun refresh() {
        status.text = "Paquetes: ${repo.packCount()} · Stickers: ${repo.all().size}"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
