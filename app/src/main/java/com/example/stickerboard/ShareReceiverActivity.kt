package com.example.stickerboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat

/**
 * Aparece en el menú "Compartir" de otras apps. Guarda las imágenes o stickers recibidos en el paquete
 * "Guardados" para que salgan en la sección de stickers del teclado.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = ArrayList<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "No recibí ninguna imagen", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val repo = StickerRepository(this)
        Thread {
            val (added, duplicates) = try {
                repo.importShared(uris)
            } catch (_: Exception) {
                0 to 0
            }
            runOnUiThread {
                val msg = when {
                    added > 0 && duplicates > 0 -> "Guardados $added en StickerBoard ($duplicates ya estaban)"
                    added > 0 -> "Guardado en StickerBoard: $added"
                    duplicates > 0 -> "Ya estaba guardado en StickerBoard"
                    else -> "No pude guardar: no es una imagen compatible"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }
}
