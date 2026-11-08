package com.example.stickerboard

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

data class Sticker(val file: File, val pack: String, val tags: Set<String>, val label: String = "")

/**
 * Los paquetes viven en filesDir/packs/<nombre>/ con las imágenes y un tags.json opcional:
 *   {"archivo.webp": ["😂", "risa"]}
 * Al importar un zip/.wastickers se lee contents.json (formato de WhatsApp) para sacar
 * los emojis de cada sticker y usarlos como etiquetas automáticas.
 */
class StickerRepository(private val context: Context) {

    private val root get() = File(context.filesDir, "packs").also { it.mkdirs() }

    private val tagger = StickerSuggester()

    fun all(): List<Sticker> {
        val result = mutableListOf<Sticker>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val tagMap = readTags(File(dir, "tags.json"))
            val labels = readLabels(dir)
            dir.listFiles()?.filter { isImage(it.name) }?.sortedBy { it.name }?.forEach { f ->
                val label = labels[f.name].orEmpty()
                val tags = mutableSetOf<String>()
                tagMap[f.name]?.let { tags += it }
                if (label.isNotBlank()) {
                    // El nombre que le pusiste (y los emojis equivalentes) es lo que usa el teclado
                    tags += tagger.expandTags(label)
                } else {
                    tags += f.nameWithoutExtension.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
                }
                result += Sticker(f, dir.name, tags, label)
            }
        }
        return result
    }

    // ---------- Nombres editables (labels.json) ----------

    private fun readLabels(dir: File): Map<String, String> {
        val f = File(dir, "labels.json")
        if (!f.exists()) return emptyMap()
        return try {
            val obj = JSONObject(f.readText())
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setLabel(file: File, label: String) {
        val dir = file.parentFile ?: return
        val map = readLabels(dir).toMutableMap()
        if (label.isBlank()) map.remove(file.name) else map[file.name] = label.trim()
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        File(dir, "labels.json").writeText(obj.toString())
    }

    fun deleteSticker(file: File) {
        setLabel(file, "")
        file.delete()
    }

    /** Copia imágenes elegidas por el usuario dentro de un paquete y devuelve los archivos nuevos. */
    fun addStickers(packDir: File, uris: List<Uri>): List<File> {
        val added = mutableListOf<File>()
        val stamp = System.currentTimeMillis()
        uris.forEachIndexed { i, uri ->
            val ext = extensionFor(context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri)?.use { input ->
                val f = File(packDir, "sticker_${stamp}_$i.$ext")
                f.outputStream().use { input.copyTo(it) }
                added += f
            }
        }
        return added
    }

    /** Paquetes con su nombre visible (name.txt) y sus stickers, para la pantalla de paquetes. */
    fun packs(): List<Pack> {
        val grouped = all().groupBy { it.file.parentFile }
        return root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.map { dir ->
            val name = File(dir, "name.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifEmpty { dir.name }
            Pack(name, dir, grouped[dir].orEmpty())
        } ?: emptyList()
    }

    fun deletePack(dir: File) {
        if (dir.parentFile == root) dir.deleteRecursively()
    }

    /** Carpeta fija por nombre: volver a importar el mismo paquete lo reemplaza en vez de duplicarlo. */
    fun replacePackDir(name: String): File {
        val d = File(root, name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_"))
        d.deleteRecursively()
        d.mkdirs()
        return d
    }

    fun createPackDir(name: String): File =
        File(root, name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_") + "_" + System.currentTimeMillis())
            .also { it.mkdirs() }

    fun packCount(): Int = root.listFiles()?.count { it.isDirectory } ?: 0

    fun importImages(uris: List<Uri>): Int {
        if (uris.isEmpty()) return 0
        val dir = File(root, "imagenes_${System.currentTimeMillis()}").also { it.mkdirs() }
        var n = 0
        uris.forEachIndexed { i, uri ->
            val ext = extensionFor(context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(dir, "sticker_$i.$ext").outputStream().use { input.copyTo(it) }
                n++
            }
        }
        return n
    }

    fun importZip(uri: Uri, packName: String): Int {
        val dir = File(root, packName.replace(Regex("[^\\p{L}\\p{N}_-]"), "_") + "_" + System.currentTimeMillis())
            .also { it.mkdirs() }
        var n = 0
        var contentsJson: String? = null
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name // evita rutas con ../
                    when {
                        entry.isDirectory -> Unit
                        name == "contents.json" -> contentsJson = zip.readBytes().toString(Charsets.UTF_8)
                        isImage(name) -> {
                            File(dir, name).outputStream().use { zip.copyTo(it) }
                            n++
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        contentsJson?.let { writeTagsFromContents(it, File(dir, "tags.json")) }
        if (n == 0) dir.deleteRecursively()
        return n
    }

    private fun writeTagsFromContents(json: String, out: File) {
        try {
            val obj = JSONObject(json)
            val packs = obj.optJSONArray("sticker_packs")
            val stickers = packs?.optJSONObject(0)?.optJSONArray("stickers") ?: obj.optJSONArray("stickers") ?: return
            val result = JSONObject()
            for (i in 0 until stickers.length()) {
                val s = stickers.getJSONObject(i)
                val file = s.optString("image_file")
                val emojis = s.optJSONArray("emojis") ?: continue
                result.put(File(file).name, emojis)
            }
            out.writeText(result.toString())
        } catch (_: Exception) {
        }
    }

    private fun readTags(f: File): Map<String, List<String>> {
        if (!f.exists()) return emptyMap()
        return try {
            val obj = JSONObject(f.readText())
            obj.keys().asSequence().associateWith { k ->
                val arr = obj.getJSONArray(k)
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".webp") || n.endsWith(".png") || n.endsWith(".jpg") ||
            n.endsWith(".jpeg") || n.endsWith(".gif")
    }

    private fun extensionFor(mime: String?) = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        else -> "webp"
    }
}
