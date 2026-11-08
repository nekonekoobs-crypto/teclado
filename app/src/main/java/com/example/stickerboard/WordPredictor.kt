package com.example.stickerboard

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Predicción de palabras en el dispositivo: vocabulario básico (es/en) + lo que aprende de lo que escribes.
 * Una palabra aprendida solo cuenta cuando la has escrito al menos 2 veces, para no "aprender" errores.
 */
class WordPredictor private constructor(private val context: Context) {

    private val es = ArrayList<String>()
    private val en = ArrayList<String>()
    private val vocab = HashSet<String>()
    private val learned = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private var unsaved = 0
    private val starters = listOf("de", "que", "el", "la", "y", "en", "no", "me", "te")

    init {
        loadVocab("words_es.txt", es)
        loadVocab("words_en.txt", en)
        vocab.addAll(es)
        vocab.addAll(en)
        loadLearned()
    }

    private fun learnedFile() = File(context.filesDir, "learned.json")

    private fun loadVocab(asset: String, into: MutableList<String>) {
        try {
            val words = context.assets.open(asset).bufferedReader().use { it.readText() }
                .split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
            into.addAll(LinkedHashSet(words))
        } catch (_: Exception) {
        }
    }

    private fun loadLearned() {
        try {
            val f = learnedFile()
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            obj.optJSONObject("w")?.let { w -> w.keys().forEach { learned[it] = w.getInt(it) } }
            obj.optJSONObject("b")?.let { b ->
                b.keys().forEach { prev ->
                    val inner = b.getJSONObject(prev)
                    val map = HashMap<String, Int>()
                    inner.keys().forEach { next -> map[next] = inner.getInt(next) }
                    bigrams[prev] = map
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun save() {
        try {
            val w = JSONObject()
            learned.forEach { (k, v) -> w.put(k, v) }
            val b = JSONObject()
            bigrams.forEach { (prev, map) ->
                val inner = JSONObject()
                map.forEach { (k, v) -> inner.put(k, v) }
                b.put(prev, inner)
            }
            learnedFile().writeText(JSONObject().put("w", w).put("b", b).toString())
            unsaved = 0
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clearLearned() {
        learned.clear()
        bigrams.clear()
        learnedFile().delete()
        unsaved = 0
    }

    fun isKnown(word: String): Boolean {
        val w = word.lowercase()
        return w in vocab || (learned[w] ?: 0) >= 2
    }

    /** Marca una palabra como válida (por ejemplo, cuando deshaces una autocorrección). */
    @Synchronized
    fun markKnown(word: String) {
        val w = word.lowercase()
        learned[w] = maxOf(learned[w] ?: 0, 3)
        unsaved++
    }

    @Synchronized
    fun learn(word: String, prev: String?) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' || it == '’' }) return
        learned[w] = (learned[w] ?: 0) + 1
        if (prev != null) {
            val p = prev.lowercase()
            val map = bigrams.getOrPut(p) { HashMap() }
            map[w] = (map[w] ?: 0) + 1
        }
        if (learned.size > 4000) learned.entries.removeAll { it.value <= 1 }
        if (bigrams.size > 3000) bigrams.clear()
        if (++unsaved >= 25) save()
    }

    fun complete(prefix: String, limit: Int = 4): List<String> {
        val p = prefix.lowercase()
        val scores = HashMap<String, Int>()
        fun consider(w: String, s: Int) {
            if (w.length > p.length && w.startsWith(p)) {
                val old = scores[w]
                if (old == null || s > old) scores[w] = s
            }
        }
        es.forEachIndexed { i, w -> consider(w, 1000 - i) }
        en.forEachIndexed { i, w -> consider(w, 900 - i) }
        learned.forEach { (w, c) -> if (c >= 2) consider(w, 1100 + c) }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    fun next(prev: String?, limit: Int = 4): List<String> {
        val out = LinkedHashSet<String>()
        prev?.lowercase()?.let { p ->
            bigrams[p]?.entries?.sortedByDescending { it.value }?.forEach { if (it.value >= 2) out.add(it.key) }
        }
        for (s in starters) {
            if (out.size >= limit) break
            out.add(s)
        }
        return out.take(limit)
    }

    /** Correcciones simples (una letra de diferencia) cuando el corrector del sistema no está disponible. */
    fun suggestFix(word: String, limit: Int = 2): List<String> {
        val w = word.lowercase()
        if (w.length < 3) return emptyList()
        val found = ArrayList<Pair<String, Int>>()
        es.forEachIndexed { i, c -> if (within1(w, c)) found.add(c to 1000 - i) }
        en.forEachIndexed { i, c -> if (within1(w, c)) found.add(c to 900 - i) }
        return found.sortedByDescending { it.second }.map { it.first }.distinct().take(limit)
    }

    private fun within1(a: String, b: String): Boolean {
        if (a == b) return false
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0
        while (i < la && i < lb && a[i] == b[i]) i++
        if (la == lb) {
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            return i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)
        }
        return if (la + 1 == lb) a.substring(i) == b.substring(i + 1) else b.substring(i) == a.substring(i + 1)
    }

    companion object {
        @Volatile
        private var instance: WordPredictor? = null

        fun get(context: Context): WordPredictor =
            instance ?: synchronized(this) {
                instance ?: WordPredictor(context.applicationContext).also { instance = it }
            }
    }
}
