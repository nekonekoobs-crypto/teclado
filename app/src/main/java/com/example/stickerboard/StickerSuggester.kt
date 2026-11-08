package com.example.stickerboard

/** Sugiere stickers a partir del texto que hay antes del cursor (todo en el dispositivo). */
class StickerSuggester {

    private val dictionary: Map<String, List<String>> = mapOf(
        "jaja" to listOf("😂", "🤣", "😆"), "jeje" to listOf("😂", "😄"), "jajaja" to listOf("😂", "🤣"),
        "lol" to listOf("😂", "🤣"), "haha" to listOf("😂", "🤣"), "risa" to listOf("😂", "🤣"),
        "amor" to listOf("❤", "😍", "🥰"), "love" to listOf("❤", "😍", "🥰"),
        "quiero" to listOf("❤", "😘"), "beso" to listOf("😘", "😚"), "kiss" to listOf("😘"),
        "gracias" to listOf("🙏", "😊"), "thanks" to listOf("🙏", "😊"), "thank" to listOf("🙏", "😊"),
        "hola" to listOf("👋", "😀"), "hello" to listOf("👋", "😀"), "hi" to listOf("👋"), "hey" to listOf("👋"),
        "adios" to listOf("👋", "😢"), "bye" to listOf("👋"), "nos vemos" to listOf("👋"),
        "triste" to listOf("😢", "😭"), "sad" to listOf("😢", "😭"), "llorar" to listOf("😭", "😢"),
        "enojo" to listOf("😡", "😠"), "enojado" to listOf("😡", "😠"), "angry" to listOf("😡", "😠"),
        "ok" to listOf("👍", "👌"), "vale" to listOf("👍", "👌"), "listo" to listOf("👍", "✅"), "va" to listOf("👍"),
        "si" to listOf("👍", "🙌"), "yes" to listOf("👍", "🙌"), "no" to listOf("🙅", "👎"),
        "buenos dias" to listOf("☀", "😀", "☕"), "good morning" to listOf("☀", "😀", "☕"),
        "buenas noches" to listOf("😴", "🌙"), "good night" to listOf("😴", "🌙"),
        "sueño" to listOf("😴"), "dormir" to listOf("😴"),
        "fiesta" to listOf("🎉", "🥳"), "party" to listOf("🎉", "🥳"), "cumpleaños" to listOf("🎂", "🥳", "🎉"),
        "felicidades" to listOf("🎉", "🥳"), "congrats" to listOf("🎉", "👏"),
        "comida" to listOf("🍕", "😋"), "hambre" to listOf("🍕", "😋"), "pizza" to listOf("🍕"), "tacos" to listOf("🌮"),
        "cafe" to listOf("☕"), "cerveza" to listOf("🍺"), "chela" to listOf("🍺"),
        "wow" to listOf("😮", "🤩"), "increible" to listOf("🤩", "😮"), "genial" to listOf("🤩", "👍"),
        "miedo" to listOf("😱", "😨"), "susto" to listOf("😱"), "pena" to listOf("😳", "🙈"),
        "cansado" to listOf("😩", "😴"), "tired" to listOf("😩", "😴"), "calor" to listOf("🥵"), "frio" to listOf("🥶"),
        "pensando" to listOf("🤔"), "hmm" to listOf("🤔"), "duda" to listOf("🤔"),
        "feliz" to listOf("😄", "😊"), "happy" to listOf("😄", "😊"), "contento" to listOf("😄", "😊"),
        "ayuda" to listOf("🆘", "🙏"), "help" to listOf("🆘", "🙏"), "porfa" to listOf("🙏", "🥺"), "please" to listOf("🙏", "🥺")
    )

    private val normDict: Map<String, List<String>> by lazy { dictionary.mapKeys { normalize(it.key) } }

    /** Convierte el nombre que el usuario le puso a un sticker en etiquetas: sus palabras + emojis equivalentes. */
    fun expandTags(label: String): Set<String> {
        val out = linkedSetOf<String>()
        val phrases = label.lowercase().split(Regex("[,;\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        for (p in phrases) {
            out += normalize(p)
            normDict[normalize(p)]?.let { l -> out += l.map { normalize(it) } }
            for (w in p.split(Regex("\\s+")).filter { it.length > 1 }) {
                out += normalize(w)
                normDict[normalize(w)]?.let { l -> out += l.map { normalize(it) } }
            }
        }
        return out
    }

    fun suggest(textBeforeCursor: String, stickers: List<Sticker>, limit: Int = 12): List<Sticker> {
        if (stickers.isEmpty()) return emptyList()
        val tokens = textBeforeCursor.lowercase().takeLast(60)
            .split(Regex("[^\\p{L}\\p{N}\\p{So}\\p{Sk}\\uFE0F]+"))
            .filter { it.isNotBlank() }.takeLast(4)

        val wanted = linkedMapOf<String, Int>() // etiqueta -> peso (más reciente = más peso)
        fun add(tag: String, weight: Int) {
            val t = normalize(tag)
            wanted[t] = maxOf(wanted[t] ?: 0, weight)
        }
        tokens.forEachIndexed { i, tok ->
            val weight = i + 1
            add(tok, weight)
            normDict[normalize(tok)]?.forEach { add(it, weight) }
            if (i > 0) {
                val bigram = tokens[i - 1] + " " + tok
                normDict[normalize(bigram)]?.forEach { add(it, weight + 1) }
            }
        }
        if (wanted.isEmpty()) return emptyList()

        return stickers
            .map { s -> s to s.tags.sumOf { wanted[normalize(it)] ?: 0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Minúsculas y sin acentos, para que "cumpleanos" coincida con "cumpleaños". */
    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
