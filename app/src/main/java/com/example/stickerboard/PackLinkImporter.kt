package com.example.stickerboard

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga un paquete a partir de un enlace de sigstick.com/pack/<id>.
 * Lee la página, encuentra la carpeta del CDN y para cada sticker prueba primero
 * los formatos animados (webp, gif) y luego png; si ninguno responde, usa la miniatura.
 */
object PackLinkImporter {

    data class Result(val total: Int, val animated: Int, val thumbsOnly: Int, val error: String? = null)

    fun import(repo: StickerRepository, link: String, onDone: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                run(repo, link)
            } catch (e: Exception) {
                Result(0, 0, 0, e.message ?: "Error desconocido")
            }
            main.post { onDone(result) }
        }.start()
    }

    private fun run(repo: StickerRepository, link: String): Result {
        val uri = try { java.net.URI(link) } catch (_: Exception) { return Result(0, 0, 0, "Enlace no válido") }
        val host = uri.host ?: return Result(0, 0, 0, "Enlace no válido")
        if (uri.scheme != "https" || !(host == "sigstick.com" || host.endsWith(".sigstick.com")))
            return Result(0, 0, 0, "Por ahora solo acepto enlaces https de sigstick.com")
        val id = Regex("/pack/([A-Za-z0-9_-]+)").find(uri.path ?: "")?.groupValues?.get(1)
            ?: return Result(0, 0, 0, "No encontré el ID del paquete en el enlace")

        val html = download("https://www.sigstick.com/pack/$id", 3_000_000, requireImage = false)
            ?.toString(Charsets.UTF_8)
            ?.replace("\\/", "/")?.replace("&amp;", "&")
            ?: return Result(0, 0, 0, "No pude abrir la página del paquete")

        // https://<cdn>/<id>/<n>.<algo>.png  -> base + índice + miniatura
        val pattern = Regex("""(https://[^"'\s)\\]+/$id)/(\d+)\.[A-Za-z0-9._-]*?(?:png|webp|gif|jpg)""")
        val found = pattern.findAll(html).toList()
        if (found.isEmpty()) return Result(0, 0, 0, "No encontré stickers en la página (¿cambió el formato del sitio?)")
        val base = found.first().groupValues[1]
        val thumbs = LinkedHashMap<Int, String>()
        found.forEach { thumbs.putIfAbsent(it.groupValues[2].toInt(), it.value) }

        val dir = repo.createPackDir("sigstick_$id")
        var animated = 0
        var thumbsOnly = 0
        var saved = 0
        for ((n, thumbUrl) in thumbs.toSortedMap()) {
            var data: ByteArray? = null
            var ext = "png"
            var full = false
            for (cand in listOf("webp", "gif", "png")) {
                val bytes = download("$base/$n.$cand", 6_000_000, requireImage = true)
                if (bytes != null) { data = bytes; ext = cand; full = true; break }
            }
            if (data == null) {
                data = download(thumbUrl, 2_000_000, requireImage = true)
                if (data != null) thumbsOnly++
            }
            if (data == null) continue
            File(dir, "sticker_$n.$ext").writeBytes(data)
            saved++
            if (full && (ext == "gif" || (ext == "webp" && isAnimatedWebp(data)))) animated++
        }
        if (saved == 0) {
            dir.deleteRecursively()
            return Result(0, 0, 0, "No pude descargar ninguna imagen")
        }
        return Result(saved, animated, thumbsOnly)
    }

    /** Un WebP animado contiene el bloque "ANIM" en su cabecera. */
    private fun isAnimatedWebp(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1)
        return head.contains("ANIM")
    }

    private fun download(url: String, maxBytes: Int, requireImage: Boolean): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) StickerBoard")
            }
            if (conn.responseCode != 200) return null
            if (requireImage && conn.contentType?.startsWith("image/") != true) return null
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    if (out.size() > maxBytes) return null
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
