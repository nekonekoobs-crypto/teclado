package com.example.stickerboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var repo: StickerRepository
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("prefs", Context.MODE_PRIVATE) }

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

        button("Ver mis paquetes de stickers") { startActivity(Intent(this, PacksActivity::class.java)) }

        col.addView(CheckBox(this).apply {
            text = "Texto predictivo (sugiere palabras)"
            isChecked = prefs.getBoolean("predictive", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("predictive", checked).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Corrección automática de ortografía"
            isChecked = prefs.getBoolean("autocorrect", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("autocorrect", checked).apply() }
        })
        col.addView(CheckBox(this).apply {
            text = "Vibración ligera al tocar las teclas"
            isChecked = prefs.getBoolean("haptic", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("haptic", checked).apply() }
        })
        col.addView(TextView(this).apply {
            text = "Espacio inferior del teclado (0 = automático). Súbelo si la barra de Android tapa la tecla ?123."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        })
        col.addView(SeekBar(this).apply {
            max = 64
            progress = prefs.getInt("bottomPad", 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putInt("bottomPad", p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        button("Borrar palabras aprendidas") {
            WordPredictor.get(this).clearLearned()
            toast("Palabras aprendidas borradas")
        }

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
                    if (r.error != null) {
                        // Se muestra el detalle completo para poder diagnosticar el fallo
                        AlertDialog.Builder(this)
                            .setTitle("No se pudo descargar")
                            .setMessage(r.error + "\n\n" + r.log)
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val sb = StringBuilder("${r.total} stickers descargados.\n")
                        if (r.staticConverted > 0) sb.append("• ${r.staticConverted} estáticos convertidos a WebP 512×512 (≤100 KB).\n")
                        if (r.animatedOk > 0) sb.append("• ${r.animatedOk} animados ya cumplen el formato de WhatsApp.\n")
                        if (r.animatedKept > 0) sb.append("• ${r.animatedKept} animados se guardaron sin cambios: no cumplen el formato de WhatsApp y esta app no puede recomprimir animaciones.\n")
                        if (r.convertFailed > 0) sb.append("• ${r.convertFailed} no se pudieron convertir y quedaron como estaban.\n")
                        if (r.thumbsOnly > 0) sb.append("• ${r.thumbsOnly} solo estaban en miniatura (128 px): se ampliaron y se verán borrosos.\n")
                        AlertDialog.Builder(this)
                            .setTitle("Paquete descargado")
                            .setMessage(sb.toString().trim())
                            .setPositiveButton("OK", null)
                            .show()
                    }
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
