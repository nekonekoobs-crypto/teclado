package com.example.stickerboard

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Descarga un paquete a partir de un enlace de sigstick.com/pack/<id>.
 * Lee la página, encuentra las miniaturas en el CDN (nombres como 0.thumb128.webp o 0-1.thumb128.png)
 * y para cada sticker intenta primero una versión más grande; si no existe, guarda la miniatura.
 */
object PackLinkImporter {

    data class Result(
        val total: Int,
        val animated: Int,
        val thumbsOnly: Int,
        val error: String? = null,
        val log: String = "",
        val staticConverted: Int = 0,
        val animatedOk: Int = 0,
        val animatedKept: Int = 0,
        val convertFailed: Int = 0
    )

    private data class Thumb(val url: String, val ext: String, val size: Int)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun import(repo: StickerRepository, link: String, onDone: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val log = StringBuilder()
            val result = try {
                run(repo, link, log)
            } catch (e: Exception) {
                Result(0, 0, 0, e.message ?: e.javaClass.simpleName, log.toString())
            }
            main.post { onDone(result) }
        }.start()
    }

    private fun note(log: StringBuilder, line: String) {
        if (log.count { it == '\n' } < 30) log.append(line).append('\n')
    }

    private fun run(repo: StickerRepository, link: String, log: StringBuilder): Result {
        fun fail(msg: String) = Result(0, 0, 0, msg, log.toString())

        val uri = try { java.net.URI(link.trim()) } catch (_: Exception) { return fail("Enlace no válido") }
        val host = uri.host ?: return fail("Enlace no válido")
        if (uri.scheme != "https" || !(host == "sigstick.com" || host.endsWith(".sigstick.com")))
            return fail("Por ahora solo acepto enlaces https de sigstick.com")
        val id = Regex("/pack/([A-Za-z0-9_-]+)").find(uri.path ?: "")?.groupValues?.get(1)
            ?: return fail("No encontré el ID del paquete en el enlace")

        // 1) Página del paquete
        val pageBytes = download("https://www.sigstick.com/pack/$id", 4_000_000, false, log, "página")
            ?: return fail("No pude abrir la página del paquete")
        val raw = String(pageBytes, Charsets.UTF_8)
        val unescaped = raw.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        val decoded = try { URLDecoder.decode(unescaped, "UTF-8") } catch (_: Exception) { unescaped }
        val text = unescaped + "\n" + decoded
        note(log, "página: ${pageBytes.size} bytes")

        // 2) Miniaturas: .../<id>/<nombre>.thumb<tamaño>.<ext>   (nombre = 0, 17-2, 52-1, ...)
        val pattern = Regex("""(https://[^"'\s)\\<>]+?/$id)/(\d+(?:-\d+)?)\.thumb(\d+)\.(png|webp|gif|jpg)""")
        val matches = pattern.findAll(text).toList()
        if (matches.isEmpty()) {
            note(log, "¿aparece cdnstep en la página? ${text.contains("cdnstep")}")
            return fail("No encontré stickers en la página (¿cambió el formato del sitio?)")
        }
        val base = matches.first().groupValues[1]
        val thumbs = LinkedHashMap<String, Thumb>()
        matches.forEach {
            val stem = it.groupValues[2]
            val size = it.groupValues[3].toInt()
            val old = thumbs[stem]
            if (old == null || size > old.size) thumbs[stem] = Thumb(it.value, it.groupValues[4], size)
        }
        note(log, "miniaturas encontradas: ${thumbs.size} en $base")

        val title = Regex("<title[^>]*>([^<]{1,160})</title>", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&#x27;", "'")?.replace("&#39;", "'")
            ?.substringBefore(" | ")?.substringBefore(" - ")?.trim().orEmpty()

        val dir = repo.replacePackDir("sigstick_$id")
        File(dir, "name.txt").writeText(title.ifEmpty { "Sigstick $id" })

        // 3) Descarga: se busca una variante grande con el primer sticker y se reutiliza para el resto
        val patterns: List<(String, Thumb) -> String> = listOf(
            { s, t -> "$base/$s.thumb512.${t.ext}" },
            { s, t -> "$base/$s.thumb256.${t.ext}" },
            { s, _ -> "$base/$s.webp" },
            { s, _ -> "$base/$s.gif" },
            { s, _ -> "$base/$s.png" }
        )
        var working: Int? = null
        var noFull = false
        var animated = 0
        var thumbsOnly = 0
        var saved = 0
        var staticConverted = 0
        var animatedOk = 0
        var animatedKept = 0
        var convertFailed = 0

        val ordered = thumbs.entries.sortedWith(
            compareBy({ it.key.substringBefore('-').toInt() }, { it.key })
        )
        for ((stem, thumb) in ordered) {
            var data: ByteArray? = null
            var kind: String? = null
            var full = false

            if (!noFull) {
                val order = if (working != null) listOf(working!!) else patterns.indices.toList()
                for (i in order) {
                    val bytes = download(patterns[i](stem, thumb), 8_000_000, true, log, "grande")
                    val k = bytes?.let { imageKind(it) }
                    if (bytes != null && k != null) { data = bytes; kind = k; full = true; working = i; break }
                }
                if (data == null && working == null) {
                    noFull = true
                    note(log, "no encontré versión grande; uso miniaturas")
                }
            }
            if (data == null) {
                val bytes = download(thumb.url, 3_000_000, true, log, "miniatura")
                val k = bytes?.let { imageKind(it) }
                if (bytes != null && k != null) { data = bytes; kind = k; thumbsOnly++ }
            }
            if (data == null || kind == null) continue

            val raw = File(dir, "sticker_$stem.$kind")
            raw.writeBytes(data)
            saved++
            when (WhatsAppFormat.process(raw)) {
                WhatsAppFormat.Outcome.STATIC_CONVERTED -> staticConverted++
                WhatsAppFormat.Outcome.ANIMATED_OK -> { animated++; animatedOk++ }
                WhatsAppFormat.Outcome.ANIMATED_NOT_COMPLIANT -> { animated++; animatedKept++ }
                WhatsAppFormat.Outcome.FAILED -> convertFailed++
            }
        }
        if (saved == 0) {
            dir.deleteRecursively()
            return fail("Encontré ${thumbs.size} stickers pero no pude descargar ninguna imagen")
        }
        return Result(saved, animated, thumbsOnly, null, log.toString(), staticConverted, animatedOk, animatedKept, convertFailed)
    }

    /** Identifica la imagen por sus primeros bytes (no confía en el Content-Type del servidor). */
    private fun imageKind(b: ByteArray): String? {
        if (b.size < 12) return null
        if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) return "png"
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) return "gif"
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return "jpg"
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() && b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()
        ) return "webp"
        return null
    }

    /** Un WebP animado contiene el bloque "ANIM" en su cabecera. */
    private fun isAnimatedWebp(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 64), Charsets.ISO_8859_1)
        return head.contains("ANIM")
    }

    private fun download(url: String, maxBytes: Int, quiet404: Boolean, log: StringBuilder, label: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://www.sigstick.com/")
                setRequestProperty("Accept", "image/webp,image/png,image/*,text/html,*/*;q=0.8")
            }
            val code = conn.responseCode
            if (code != 200) {
                if (!(quiet404 && code == 404)) note(log, "$label: HTTP $code ${url.takeLast(60)}")
                return null
            }
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
        } catch (e: Exception) {
            note(log, "$label: ${e.javaClass.simpleName} ${e.message ?: ""} ${url.takeLast(50)}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
