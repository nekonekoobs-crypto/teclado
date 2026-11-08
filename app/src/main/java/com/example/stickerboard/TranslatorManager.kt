package com.example.stickerboard

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions

class TranslatorManager {

    /** Pares disponibles; el botón del teclado los va alternando. */
    val pairs = listOf(
        TranslateLanguage.SPANISH to TranslateLanguage.ENGLISH,
        TranslateLanguage.ENGLISH to TranslateLanguage.SPANISH,
        TranslateLanguage.SPANISH to TranslateLanguage.PORTUGUESE,
        TranslateLanguage.SPANISH to TranslateLanguage.FRENCH,
        TranslateLanguage.SPANISH to TranslateLanguage.ITALIAN
    )

    private val cache = mutableMapOf<Pair<String, String>, Translator>()

    fun translate(
        text: String,
        pair: Pair<String, String>,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val translator = cache.getOrPut(pair) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(pair.first)
                    .setTargetLanguage(pair.second)
                    .build()
            )
        }
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { onResult(it) }
                    .addOnFailureListener { onError(it) }
            }
            .addOnFailureListener { onError(it) }
    }

    fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }
}
