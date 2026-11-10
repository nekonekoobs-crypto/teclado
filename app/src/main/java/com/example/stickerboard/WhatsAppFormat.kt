package com.example.stickerboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Ajusta los stickers a los requisitos de WhatsApp:
 *  - Estático: WebP de 512x512 px, fondo transparente, máximo 100 KB.
 *  - Animado: WebP animado de 512x512 px, máximo 500 KB.
 *
 * Android no incluye un codificador de WebP animado, así que los animados NO se pueden recomprimir
 * aquí: si ya cumplen se dejan igual, y si no cumplen se conservan tal cual y se avisa.
 */
object WhatsAppFormat {

    enum class Outcome { STATIC_CONVERTED, ANIMATED_OK, ANIMATED_NOT_COMPLIANT, FAILED }

    private const val SIZE = 512
    private const val MAX_STATIC = 100 * 1024
    private const val MAX_ANIMATED = 500 * 1024

    /** Procesa el archivo. Si convierte un sticker estático, el archivo original se reemplaza por un .webp. */
    fun process(file: File): Outcome {
        return try {
            if (isAnimated(file)) checkAnimated(file) else convertStatic(file)
        } catch (_: Exception) {
            Outcome.FAILED
        }
    }

    private fun isAnimated(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            return try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) is AnimatedImageDrawable
            } catch (_: Exception) {
                false
            }
        }
        val head = ByteArray(64)
        val n = file.inputStream().use { it.read(head) }
        val text = String(head, 0, maxOf(n, 0), Charsets.ISO_8859_1)
        return text.contains("ANIM") || file.extension.lowercase() == "gif"
    }

    private fun checkAnimated(file: File): Outcome {
        if (file.extension.lowercase() != "webp") return Outcome.ANIMATED_NOT_COMPLIANT // gif: no es formato de WhatsApp
        val (w, h) = webpCanvas(file) ?: return Outcome.ANIMATED_NOT_COMPLIANT
        return if (w == SIZE && h == SIZE && file.length() <= MAX_ANIMATED) Outcome.ANIMATED_OK
        else Outcome.ANIMATED_NOT_COMPLIANT
    }

    /** Lee el tamaño del lienzo de un WebP extendido (bloque VP8X). */
    private fun webpCanvas(file: File): Pair<Int, Int>? {
        val b = ByteArray(30)
        val n = file.inputStream().use { it.read(b) }
        if (n < 30) return null
        if (String(b, 12, 4, Charsets.ISO_8859_1) != "VP8X") return null
        fun u24(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)
        return (u24(24) + 1) to (u24(27) + 1)
    }

    private fun convertStatic(file: File): Outcome {
        val src: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        } else {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return Outcome.FAILED
        }

        // Lienzo transparente de 512x512 con el sticker centrado y ajustado sin deformarse
        val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val dest = RectF((SIZE - w) / 2f, (SIZE - h) / 2f, (SIZE + w) / 2f, (SIZE + h) / 2f)
        Canvas(canvasBmp).drawBitmap(src, null, dest, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val out = ByteArrayOutputStream()
        var quality = 90
        do {
            out.reset()
            canvasBmp.compress(format, quality, out)
            quality -= 10
        } while (out.size() > MAX_STATIC && quality >= 10)
        if (out.size() > MAX_STATIC) return Outcome.FAILED

        val target = File(file.parentFile, file.nameWithoutExtension + ".webp")
        target.writeBytes(out.toByteArray())
        if (target.absolutePath != file.absolutePath) file.delete()
        return Outcome.STATIC_CONVERTED
    }
}
