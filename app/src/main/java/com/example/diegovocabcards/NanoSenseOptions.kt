package com.example.diegovocabcards

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.withTimeoutOrNull

internal const val GEMINI_TRANSLATION = "Traducción sugerida por Gemini Nano"
internal const val GEMINI_SYNONYMS = "Sinónimos sugeridos por Gemini Nano"

internal enum class SenseRequest { Translation, Synonyms }

internal suspend fun nanoSenseOptions(term: String, sense: Suggestion, task: SenseRequest): List<String> {
    val instruction = when (task) {
        SenseRequest.Translation -> "Return only one short Spanish translation of the full expression in this sense."
        SenseRequest.Synonyms -> "Return up to four interchangeable English synonyms for the full expression in this sense, separated by commas."
    }
    val context = listOf(term, sense.category, sense.definition, sense.examples.firstOrNull().orEmpty())
        .joinToString("\n") { it.replace(Regex("\\s+"), " ").take(400) }
    val model = Generation.getClient()
    try {
        return withTimeoutOrNull(60_000) {
            if (model.checkStatus() != FeatureStatus.AVAILABLE) return@withTimeoutOrNull emptyList()
            val request = generateContentRequest(TextPart("$instruction No explanation. If unsure return NONE.\nExpression, category, definition, example:\n$context")) {
                temperature = 0.1f
                maxOutputTokens = 64
            }
            val answer = model.generateContent(request).candidates.firstOrNull()?.text.orEmpty().trim()
            val terms = if (task == SenseRequest.Synonyms) answer.split(',') else listOf(answer)
            terms.map { it.trim().trim('"') }.filter {
                it.matches(Regex("""[\p{L}][\p{L}\p{M}'’ -]{0,79}""")) &&
                    it.split(' ').size <= 9 && !it.equals("NONE", true) &&
                    (task == SenseRequest.Translation || !it.equals(term, true))
            }.distinctBy { it.lowercase() }.take(4)
        }.orEmpty()
    } finally {
        model.close()
    }
}
