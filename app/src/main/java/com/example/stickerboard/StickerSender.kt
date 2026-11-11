package com.example.stickerboard

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

object StickerSender {

    /** Motivo del último fallo, para mostrarlo al usuario. */
    @Volatile
    var lastError: String = ""

    /** Aviso para el usuario cuando el sticker se envió distinto al original (por ejemplo, fijo en vez de animado). */
    @Volatile
    var lastNote: String = ""

    /**
     * Envía el sticker al campo de texto. Si la app no acepta el formato (por ejemplo WebP)
     * o lo rechaza, lo reintenta convertido a PNG (sin animación).
     */
    fun send(context: Context, ic: InputConnection?, info: EditorInfo?, file: File): Boolean {
        lastNote = ""
        if (ic == null || info == null) {
            lastError = "No hay un campo de texto activo"
            return false
        }
        val supported = EditorInfoCompat.getContentMimeTypes(info)
        if (supported.isEmpty()) {
            lastError = "Esta app no acepta imágenes desde el teclado"
            return false
        }
        fun accepts(m: String) = supported.any { ClipDescription.compareMimeTypes(m, it) }

        val original = mimeFor(file.name)

        // WhatsApp puede anunciar un tipo propio para stickers WebP. Si está, se usa primero:
        // así el archivo se entrega como sticker y no como una imagen cualquiera.
        val waSticker = "image/webp.wasticker"
        if (original == "image/webp" && supported.any { it.equals(waSticker, ignoreCase = true) }) {
            if (commit(context, ic, info, file, waSticker)) return true
            // Si lo rechaza, se sigue con el flujo normal (WebP y luego PNG)
        }

        if (accepts(original)) {
            if (commit(context, ic, info, file, original)) return true
            // La app declaró el formato pero lo rechazó: se prueba con PNG
            if (original != "image/png" && accepts("image/png")) {
                val png = toPng(context, file)
                if (png != null && commit(context, ic, info, png, "image/png")) {
                    lastNote = "La app rechazó el WebP; se envió como imagen fija (PNG)"
                    return true
                }
            }
            if (lastError.isEmpty()) lastError = "La app rechazó el sticker ($original)"
            return false
        }

        if (accepts("image/png")) {
            val png = toPng(context, file)
            if (png == null) {
                lastError = "No pude convertir el sticker a PNG"
                return false
            }
            val ok = commit(context, ic, info, png, "image/png")
            if (ok) lastNote = "La app no acepta WebP desde el teclado; se envió como imagen fija (PNG)"
            return ok
        }
        lastError = "Esta app solo acepta: ${supported.joinToString(", ")}"
        return false
    }

    private fun commit(context: Context, ic: InputConnection, info: EditorInfo, file: File, mime: String): Boolean {
        lastError = ""
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.stickers", file)
        } catch (e: Exception) {
            lastError = "No pude preparar el archivo (${e.message})"
            return false
        }
        val description = ClipDescription(file.name, arrayOf(mime))
        val content = InputContentInfoCompat(uri, description, null)
        val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        val ok = try {
            InputConnectionCompat.commitContent(ic, info, content, flags, null)
        } catch (e: Exception) {
            lastError = "Error al enviar (${e.javaClass.simpleName})"
            false
        }
        if (!ok && lastError.isEmpty()) lastError = "La app rechazó el sticker ($mime)"
        return ok
    }

    /** Convierte a PNG (primer fotograma si estaba animado) en la carpeta de caché. */
    private fun toPng(context: Context, file: File): File? {
        return try {
            val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            }
            if (bitmap == null) return null
            val dir = File(context.cacheDir, "send").also { it.mkdirs() }
            val out = File(dir, "${file.parentFile?.name}_${file.nameWithoutExtension}.png")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/webp"
    }
}
