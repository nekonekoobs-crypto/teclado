package com.example.stickerboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Crea stickers a partir de un video:
 *  - Animado: WebP animado 512×512, bucle infinito, ≤500 KB (formato de WhatsApp). Android no trae
 *    codificador de WebP animado, así que cada cuadro se comprime como WebP y aquí se arma el
 *    contenedor (VP8X + ANIM + ANMF) a mano. WhatsApp NO admite audio en stickers.
 *  - Fijo: un cuadro como WebP 512×512 ≤100 KB.
 *  - Clip con audio: el tramo del video recortado a MP4 (sin recomprimir), para compartirlo como video.
 */
object VideoStickerMaker {

    const val SIZE = 512
    const val MAX_ANIMATED = 500 * 1024
    const val MAX_SECONDS = 5
    private const val STEP_MS = 100 // 10 cuadros por segundo
    private val L1 = Charsets.ISO_8859_1

    class Result(val ok: Boolean, val message: String)

    fun durationMs(context: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Cuadro del video en el instante dado, ya en 512×512. */
    fun frame(context: Context, uri: Uri, ms: Long, fill: Boolean): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            frameAt(r, ms, fill, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun frameAt(r: MediaMetadataRetriever, ms: Long, fill: Boolean, config: Bitmap.Config): Bitmap? {
        val src = try {
            r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Throwable) {
            null
        } ?: return null
        val out = Bitmap.createBitmap(SIZE, SIZE, config)
        val s = if (fill) maxOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        else minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * s
        val h = src.height * s
        val dest = RectF((SIZE - w) / 2f, (SIZE - h) / 2f, (SIZE + w) / 2f, (SIZE + h) / 2f)
        Canvas(out).drawBitmap(src, null, dest, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
        return out
    }

    // ---------- Sticker animado ----------

    fun createAnimated(
        context: Context, uri: Uri, startMs: Long, lenMs: Long, fill: Boolean, dest: File,
        progress: (String) -> Unit
    ): Result {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val n = (lenMs / STEP_MS).toInt().coerceIn(5, MAX_SECONDS * 1000 / STEP_MS)
            // Con fondo opaco se usa RGB_565 (la mitad de memoria); con bordes transparentes, ARGB
            val config = if (fill) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            val frames = ArrayList<Bitmap>()
            for (i in 0 until n) {
                progress("Leyendo cuadros ${i + 1}/$n…")
                frameAt(r, startMs + i.toLong() * STEP_MS, fill, config)?.let { frames.add(it) }
            }
            if (frames.size < 2) return Result(false, "No pude leer cuadros de ese tramo del video.")

            // (cada cuántos cuadros se toma uno, calidad): primero buena imagen, luego menos peso
            val attempts = listOf(1 to 70, 1 to 55, 1 to 40, 2 to 50, 2 to 38, 2 to 28)
            var lastSize = 0
            for ((skip, q) in attempts) {
                progress("Comprimiendo (calidad $q)…")
                val encoded = frames.filterIndexed { i, _ -> i % skip == 0 }.map { webp(it, q) }
                val bytes = buildAnimated(encoded, STEP_MS * skip, !fill) ?: continue
                lastSize = bytes.size
                if (bytes.size <= MAX_ANIMATED) {
                    dest.writeBytes(bytes)
                    return Result(true, "${frames.size} cuadros · ${bytes.size / 1024} KB")
                }
            }
            return Result(false, "Aun con la menor calidad pesa ${lastSize / 1024} KB (máximo 500 KB). Elige un tramo más corto.")
        } catch (e: Throwable) {
            return Result(false, "Error: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun webp(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        bmp.compress(format, quality, out)
        return out.toByteArray()
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun ByteArrayOutputStream.w32(v: Int) { for (i in 0..3) write((v shr (8 * i)) and 0xFF) }
    private fun ByteArrayOutputStream.w24(v: Int) { for (i in 0..2) write((v shr (8 * i)) and 0xFF) }
    private fun ByteArrayOutputStream.tag(s: String) = write(s.toByteArray(L1))

    /** Saca de un WebP simple los bloques de imagen (ALPH / VP8 / VP8L), que es lo que lleva cada cuadro. */
    private fun frameChunks(webp: ByteArray): ByteArray? {
        if (webp.size < 20 || String(webp, 0, 4, L1) != "RIFF" || String(webp, 8, 4, L1) != "WEBP") return null
        val out = ByteArrayOutputStream()
        var pos = 12
        while (pos + 8 <= webp.size) {
            val name = String(webp, pos, 4, L1)
            val size = le32(webp, pos + 4)
            if (size < 0 || pos + 8 + size > webp.size) return null
            if (name == "ALPH" || name == "VP8 " || name == "VP8L") {
                out.write(webp, pos, 8 + size)
                if (size and 1 == 1) out.write(0)
            }
            pos += 8 + size + (size and 1)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun buildAnimated(frames: List<ByteArray>, durationMs: Int, alpha: Boolean): ByteArray? {
        val body = ByteArrayOutputStream()
        body.tag("VP8X"); body.w32(10)
        body.write(0x02 or (if (alpha) 0x10 else 0)) // animación (+ transparencia)
        body.w24(0)
        body.w24(SIZE - 1); body.w24(SIZE - 1)
        body.tag("ANIM"); body.w32(6)
        body.w32(0)          // color de fondo
        body.write(0); body.write(0) // repeticiones: 0 = infinito (lo que pide WhatsApp)
        for (f in frames) {
            val chunks = frameChunks(f) ?: return null
            body.tag("ANMF"); body.w32(16 + chunks.size)
            body.w24(0); body.w24(0)                 // posición X, Y
            body.w24(SIZE - 1); body.w24(SIZE - 1)   // tamaño del cuadro
            body.w24(durationMs)
            body.write(0x02)                         // sin mezclar con el cuadro anterior
            body.write(chunks)
        }
        val out = ByteArrayOutputStream()
        out.tag("RIFF"); out.w32(4 + body.size()); out.tag("WEBP"); out.write(body.toByteArray())
        return out.toByteArray()
    }

    // ---------- Sticker fijo ----------

    fun createStatic(context: Context, uri: Uri, ms: Long, fill: Boolean, dest: File): Result {
        val bmp = frame(context, uri, ms, fill) ?: return Result(false, "No pude leer ese cuadro del video.")
        val png = File(dest.parentFile, dest.nameWithoutExtension + ".png")
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val outcome = WhatsAppFormat.process(png) // lo convierte a WebP 512×512 ≤100 KB y borra el PNG
        return if (outcome == WhatsAppFormat.Outcome.STATIC_CONVERTED) {
            Result(true, "${dest.length() / 1024} KB")
        } else {
            png.delete()
            Result(false, "No pude ajustarlo a 100 KB.")
        }
    }

    // ---------- Audio del tramo (para stickers con sonido) ----------

    /** Guarda solo el audio del tramo (sin recomprimir). Devuelve null si salió bien, o el motivo. */
    fun extractAudio(context: Context, uri: Uri, startMs: Long, lenMs: Long, dest: File): String? {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        return try {
            ex.setDataSource(context, uri, null)
            var audioTrack = -1
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return "El video no tiene audio."
            ex.selectTrack(audioTrack)
            val m = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val out = m.addTrack(ex.getTrackFormat(audioTrack))
            m.start()
            started = true
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val endUs = (startMs + lenMs) * 1000
            var baseUs = -1L
            var written = 0
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                if (baseUs < 0) baseUs = t
                info.set(0, size, maxOf(0L, t - baseUs), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(out, buf, info)
                written++
                ex.advance()
            }
            if (written == 0) "No encontré audio en ese tramo." else null
        } catch (e: Throwable) {
            "Error: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        } finally {
            try { if (started) mux?.stop() } catch (_: Exception) {}
            try { mux?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    // ---------- Clip con audio ----------

    /** Recorta el tramo a un MP4 sin recomprimir (conserva el audio). Devuelve null si salió bien, o el error. */
    fun createClip(context: Context, uri: Uri, startMs: Long, lenMs: Long, dest: File): String? {
        val ex = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        return try {
            ex.setDataSource(context, uri, null)
            var rotation = 0
            val mr = MediaMetadataRetriever()
            try {
                mr.setDataSource(context, uri)
                rotation = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } catch (_: Exception) {
            } finally {
                try { mr.release() } catch (_: Exception) {}
            }
            val m = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            if (rotation != 0) m.setOrientationHint(rotation)
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    map[i] = m.addTrack(f)
                }
            }
            if (map.isEmpty()) return "El archivo no tiene video."
            m.start()
            started = true
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val endUs = (startMs + lenMs) * 1000
            var baseUs = -1L
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                if (baseUs < 0) baseUs = t
                val track = map[ex.sampleTrackIndex]
                if (track != null) {
                    val key = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, maxOf(0L, t - baseUs), key)
                    m.writeSampleData(track, buf, info)
                }
                ex.advance()
            }
            null
        } catch (e: Throwable) {
            "Error: ${e.javaClass.simpleName} ${e.message.orEmpty()}"
        } finally {
            try { if (started) mux?.stop() } catch (_: Exception) {}
            try { mux?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }
}
