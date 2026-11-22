package com.example.stickerboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Miniaturas estáticas en caché. Se decodifican en segundo plano a un tamaño pequeño, así el teclado
 * no se traba al mostrar la tira de sugerencias ni el panel (decodificar stickers animados en el hilo
 * principal era lo que lo ponía lento).
 */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private fun key(f: File, px: Int) = "${f.absolutePath}|${f.lastModified()}|$px"

    private fun decode(f: File, px: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    fun load(view: ImageView, f: File, px: Int) {
        val k = key(f, px)
        view.tag = k
        val hit = cache.get(k)
        if (hit != null) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bmp = decode(f, px) ?: return@execute
            cache.put(k, bmp)
            main.post { if (view.tag == k) view.setImageBitmap(bmp) }
        }
    }
}

/** Versión ligera para listas: imagen fija y pequeña, cargada en segundo plano. */
fun ImageView.loadThumb(f: File, px: Int = 160) = Thumbs.load(this, f, px)
