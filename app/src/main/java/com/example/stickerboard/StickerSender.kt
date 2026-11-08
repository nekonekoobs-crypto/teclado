package com.example.stickerboard

import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

object StickerSender {

    /** Devuelve false si la app destino no acepta imágenes desde el teclado. */
    fun send(context: Context, ic: InputConnection?, info: EditorInfo?, file: File): Boolean {
        if (ic == null || info == null) return false
        val mime = mimeFor(file.name)
        val supported = EditorInfoCompat.getContentMimeTypes(info)
        if (supported.none { ClipDescription.compareMimeTypes(mime, it) }) return false

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.stickers", file)
        val description = ClipDescription(file.name, arrayOf(mime))
        val content = InputContentInfoCompat(uri, description, null)
        val flags = if (Build.VERSION.SDK_INT >= 25) InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        return InputConnectionCompat.commitContent(ic, info, content, flags, null)
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/webp"
    }
}
