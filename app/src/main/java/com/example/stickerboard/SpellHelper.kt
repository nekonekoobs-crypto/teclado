package com.example.stickerboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager

/**
 * Envuelve el corrector ortográfico del sistema (el que el usuario tenga activo en Android),
 * así no hace falta empaquetar diccionarios de cada idioma.
 */
class SpellHelper(
    private val context: Context,
    private val onResult: (word: String, suggestions: List<String>, typo: Boolean, recommended: Boolean) -> Unit
) {
    private var session: SpellCheckerSession? = null
    private val pending = HashMap<Int, String>()
    private var counter = 0
    private val main = Handler(Looper.getMainLooper())

    val available: Boolean get() = session != null

    private val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
            val list = results?.toList() ?: return
            main.post {
                list.forEach { info ->
                    val word = pending.remove(info.cookie) ?: return@forEach
                    val attrs = info.suggestionsAttributes
                    val typo = attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
                    val recommended = attrs and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0
                    val suggestions = (0 until info.suggestionsCount)
                        .map { info.getSuggestionAt(it) }
                        .filter { it.isNotBlank() }
                    onResult(word, suggestions, typo, recommended)
                }
            }
        }

        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {}
    }

    fun open() {
        session = try {
            val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            tsm.newSpellCheckerSession(null, null, listener, true)
        } catch (_: Exception) {
            null
        }
    }

    fun check(word: String) {
        val s = session ?: return
        val id = ++counter
        if (pending.size > 50) pending.clear()
        pending[id] = word
        try {
            @Suppress("DEPRECATION")
            s.getSuggestions(TextInfo(word, id, 0), 5)
        } catch (_: Exception) {
        }
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
    }
}
