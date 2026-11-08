package com.example.stickerboard

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import java.io.File

/** Muestra el sticker; si es webp/gif animado (Android 9+) se reproduce en bucle. */
fun ImageView.loadSticker(f: File) {
    if (Build.VERSION.SDK_INT >= 28) {
        try {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f))
            setImageDrawable(d)
            if (d is AnimatedImageDrawable) {
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.start()
            }
            return
        } catch (_: Exception) {
        }
    }
    setImageURI(Uri.fromFile(f))
}
