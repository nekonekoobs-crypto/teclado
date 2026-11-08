package com.example.stickerboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.io.File

/**
 * Propone un nombre para un sticker a partir de lo que reconoce en la imagen (en el teléfono, sin enviar nada).
 * Es una ayuda: el reconocedor detecta objetos y expresiones generales ("sonrisa", "gato", "comida"),
 * no siempre el estado de ánimo, por eso el nombre siempre se puede editar.
 */
object StickerLabeler {

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())
    }
    private val translator = TranslatorManager()

    /** Etiquetas demasiado genéricas para servir como nombre. */
    private val generic = setOf(
        "cartoon", "art", "illustration", "animation", "animated cartoon", "drawing", "graphics", "design",
        "fiction", "fictional character", "clip art", "font", "line", "pattern", "circle", "symbol", "text",
        "logo", "rectangle", "graphic design", "creative arts", "visual arts", "painting"
    )

    /** Devuelve en [done] el nombre sugerido en español, o "" si no pudo reconocer nada. */
    fun suggest(file: File, done: (String) -> Unit) {
        val bitmap = loadBitmap(file)
        if (bitmap == null) {
            done("")
            return
        }
        labeler.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { labels ->
                val all = labels.map { it.text }
                val useful = all.filter { it.lowercase() !in generic }.ifEmpty { all }.take(3)
                if (useful.isEmpty()) {
                    done("")
                    return@addOnSuccessListener
                }
                val english = useful.joinToString(", ").lowercase()
                translator.translate(
                    english,
                    TranslateLanguage.ENGLISH to TranslateLanguage.SPANISH,
                    onResult = { es -> done(es.lowercase().trim()) },
                    onError = { done(english) } // sin modelo de traducción todavía: se deja en inglés
                )
            }
            .addOnFailureListener { done("") }
    }

    /** Nombra varios stickers uno tras otro. */
    fun nameAll(
        repo: StickerRepository,
        files: List<File>,
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: () -> Unit
    ) {
        fun step(i: Int) {
            if (i >= files.size) {
                onDone()
                return
            }
            suggest(files[i]) { label ->
                if (label.isNotBlank()) repo.setLabel(files[i], label)
                onProgress(i + 1, files.size)
                step(i + 1)
            }
        }
        step(0)
    }

    /** Primer fotograma sobre fondo blanco (los stickers suelen ser transparentes). */
    private fun loadBitmap(f: File): Bitmap? {
        val raw: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val side = maxOf(info.size.width, info.size.height)
                    if (side > 512) decoder.setTargetSampleSize(side / 512)
                }
            } else {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(f.absolutePath, opts)
            }
        } catch (_: Exception) {
            null
        }
        if (raw == null) return null
        val out = Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(raw, 0f, 0f, null)
        }
        return out
    }
}
