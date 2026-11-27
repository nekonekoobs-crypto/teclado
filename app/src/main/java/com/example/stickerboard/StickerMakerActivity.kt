package com.example.stickerboard

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

/**
 * Menú para crear stickers desde un video: eliges el video, el inicio y la duración, y el modo
 * (animado, fijo o clip con audio). Lo creado va al paquete "Mis stickers".
 */
class StickerMakerActivity : ComponentActivity() {

    private lateinit var repo: StickerRepository
    private var video: Uri? = null
    private var durationMs = 0L

    private lateinit var preview: ImageView
    private lateinit var info: TextView
    private lateinit var startLabel: TextView
    private lateinit var lenLabel: TextView
    private lateinit var startBar: SeekBar
    private lateinit var lenBar: SeekBar
    private lateinit var fillBox: CheckBox
    private lateinit var audioBox: CheckBox
    private lateinit var lenBox: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var createBtn: Button
    private lateinit var status: TextView
    private var idAnimated = 0
    private var idStatic = 0
    private var idClip = 0
    private var busy = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadVideo(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startMs() = startBar.progress * 100L
    private fun requestedLen() = 1000L + lenBar.progress * 100L
    private fun effectiveLen() = minOf(requestedLen(), durationMs - startMs())

    private fun fmt(ms: Long) = String.format("%.1f s", ms / 1000.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StickerRepository(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(24))
        }
        col.addView(TextView(this).apply { text = "Crear sticker desde un video"; textSize = 24f })
        col.addView(TextView(this).apply {
            text = "WhatsApp no permite audio en stickers: el sticker animado va sin sonido. " +
                "Si quieres el sonido, usa \"Clip con audio\" y compártelo como video."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(6), 0, dp(10))
        })
        col.addView(Button(this).apply {
            text = "Elegir video"
            setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        })

        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0x22888888)
        }
        col.addView(preview, LinearLayout.LayoutParams(-1, dp(240)).apply { topMargin = dp(8) })
        info = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8)) }
        col.addView(info)

        startLabel = TextView(this).apply { textSize = 14f }
        col.addView(startLabel)
        startBar = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateLabels()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) = refreshPreview()
            })
        }
        col.addView(startBar)

        lenBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lenLabel = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, 0) }
        lenBox.addView(lenLabel)
        lenBar = SeekBar(this).apply {
            max = 40
            progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateLabels()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        lenBox.addView(lenBar)
        col.addView(lenBox)

        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; setPadding(0, dp(12), 0, 0) }
        fun radio(text: String): Int {
            val rb = RadioButton(this).apply { this.text = text; id = View.generateViewId() }
            modeGroup.addView(rb)
            return rb.id
        }
        idAnimated = radio("Sticker animado (sin audio, formato WhatsApp, hasta 5 s)")
        idStatic = radio("Sticker fijo (un cuadro del video)")
        idClip = radio("Clip con audio (MP4 para compartir como video)")
        modeGroup.check(idAnimated)
        modeGroup.setOnCheckedChangeListener { _, _ -> onModeChanged() }
        col.addView(modeGroup)

        fillBox = CheckBox(this).apply {
            text = "Llenar el cuadro (recorta los bordes). Desmárcalo para ver el video completo con bordes transparentes."
            isChecked = true
            setOnCheckedChangeListener { _, _ -> refreshPreview() }
        }
        col.addView(fillBox)

        audioBox = CheckBox(this).apply {
            text = "Guardar también el audio del tramo, para stickers con sonido (hoy WhatsApp no lo recibe desde teclados)"
            isChecked = true
        }
        col.addView(audioBox)

        createBtn = Button(this).apply {
            text = "Crear"
            isEnabled = false
            setOnClickListener { create() }
        }
        col.addView(createBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        status = TextView(this).apply { textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        col.addView(status)

        setContentView(ScrollView(this).apply { addView(col) })
        updateLabels()
    }

    private fun isClip() = modeGroup.checkedRadioButtonId == idClip
    private fun isStatic() = modeGroup.checkedRadioButtonId == idStatic

    private fun onModeChanged() {
        lenBox.visibility = if (isStatic()) View.GONE else View.VISIBLE
        fillBox.visibility = if (isClip()) View.GONE else View.VISIBLE
        audioBox.visibility = if (isClip() || isStatic()) View.GONE else View.VISIBLE
        if (isClip()) {
            lenBar.max = 290 // hasta 30 s
        } else {
            lenBar.max = 40 // hasta 5 s
            if (lenBar.progress > 40) lenBar.progress = 40
        }
        updateLabels()
        refreshPreview()
    }

    private fun loadVideo(uri: Uri) {
        status.text = "Leyendo el video…"
        Thread {
            val d = VideoStickerMaker.durationMs(this, uri)
            runOnUiThread {
                if (d < 1000) {
                    status.text = "No pude leer ese video (o dura menos de 1 s)."
                    return@runOnUiThread
                }
                video = uri
                durationMs = d
                startBar.max = ((d - 500) / 100).toInt().coerceAtLeast(0)
                startBar.progress = 0
                createBtn.isEnabled = true
                status.text = ""
                updateLabels()
                refreshPreview()
            }
        }.start()
    }

    private fun updateLabels() {
        startLabel.text = "Inicio: ${fmt(startMs())}" + if (durationMs > 0) " de ${fmt(durationMs)}" else ""
        lenLabel.text = "Duración: ${fmt(if (durationMs > 0) effectiveLen() else requestedLen())}"
    }

    private fun refreshPreview() {
        val uri = video ?: return
        val ms = startMs()
        val fill = fillBox.isChecked || isClip()
        Thread {
            val bmp: Bitmap? = VideoStickerMaker.frame(this, uri, ms, fill)
            runOnUiThread {
                if (bmp != null) preview.setImageBitmap(bmp)
                info.text = if (bmp == null) "No pude mostrar el cuadro." else "Vista del cuadro inicial"
            }
        }.start()
    }

    private fun create() {
        val uri = video ?: return
        if (busy) return
        val start = startMs()
        val len = effectiveLen()
        if (!isStatic() && len < 500) {
            status.text = "El tramo es muy corto: mueve el inicio hacia atrás."
            return
        }
        val fill = fillBox.isChecked
        val wantAudio = audioBox.isChecked
        val animated = !isStatic() && !isClip()
        busy = true
        createBtn.isEnabled = false
        status.text = "Trabajando…"
        val stamp = System.currentTimeMillis()

        Thread {
            try {
                when {
                    isClip() -> {
                        val dir = File(filesDir, "clips").also { it.mkdirs() }
                        val dest = File(dir, "clip_$stamp.mp4")
                        val err = VideoStickerMaker.createClip(this, uri, start, len, dest)
                        runOnUiThread { finishClip(dest, err) }
                    }
                    animated -> {
                        val dest = File(repo.myStickersDir(), "video_$stamp.webp")
                        val r = VideoStickerMaker.createAnimated(this, uri, start, len, fill, dest) { msg ->
                            runOnUiThread { status.text = msg }
                        }
                        var audioNote = ""
                        if (r.ok && wantAudio) {
                            val audioFile = File(dest.parentFile, dest.nameWithoutExtension + ".audio.m4a")
                            val err = VideoStickerMaker.extractAudio(this, uri, start, len, audioFile)
                            if (err != null) audioFile.delete()
                            audioNote = if (err == null) "\nAudio guardado junto al sticker." else "\nSin audio: $err"
                        }
                        val full = VideoStickerMaker.Result(r.ok, r.message + audioNote)
                        runOnUiThread { finishSticker(dest, full) }
                    }
                    else -> {
                        val dest = File(repo.myStickersDir(), "video_$stamp.webp")
                        val r = VideoStickerMaker.createStatic(this, uri, start, fill, dest)
                        runOnUiThread { finishSticker(dest, r) }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    done()
                    status.text = "Error: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
                }
            }
        }.start()
    }

    private fun done() {
        busy = false
        createBtn.isEnabled = video != null
    }

    private fun finishSticker(dest: File, r: VideoStickerMaker.Result) {
        done()
        if (!r.ok) {
            status.text = ""
            AlertDialog.Builder(this).setTitle("No se pudo crear").setMessage(r.message)
                .setPositiveButton("OK", null).show()
            return
        }
        status.text = "Listo"
        val check = WhatsAppFormat.inspect(dest)
        AlertDialog.Builder(this)
            .setTitle("Sticker creado")
            .setMessage("Guardado en \"Mis stickers\".\n${r.message}\nWhatsApp: ${check.text}")
            .setPositiveButton("Ponerle nombre") { _, _ -> startActivity(QuickNameActivity.intentFor(this, listOf(dest))) }
            .setNegativeButton("Listo", null)
            .show()
    }

    private fun finishClip(dest: File, err: String?) {
        done()
        if (err != null) {
            dest.delete()
            status.text = ""
            AlertDialog.Builder(this).setTitle("No se pudo crear el clip").setMessage(err)
                .setPositiveButton("OK", null).show()
            return
        }
        status.text = "Listo"
        AlertDialog.Builder(this)
            .setTitle("Clip creado")
            .setMessage(
                "${dest.length() / 1024} KB, con audio. Se guarda dentro de la app; compártelo como video " +
                    "(el recorte empieza en el cuadro clave anterior, así que puede arrancar un instante antes)."
            )
            .setPositiveButton("Compartir") { _, _ ->
                try {
                    val u = FileProvider.getUriForFile(this, "$packageName.stickers", dest)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, u)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Compartir clip"))
                } catch (e: Exception) {
                    status.text = "No pude abrir el menú de compartir."
                }
            }
            .setNegativeButton("Listo", null)
            .show()
    }
}
