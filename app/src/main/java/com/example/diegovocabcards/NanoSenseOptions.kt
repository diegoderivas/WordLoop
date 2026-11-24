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
        SenseRequest.Translation -> "Return only one short ${if (sense.language == "ja") "Japanese" else "Spanish"} translation of the full expression in this sense."
        SenseRequest.Synonyms -> "Return up to four interchangeable English synonyms for the full expression in this sense, separated by commas."
    }
    val context = listOf(term, sense.category, sense.definition, sense.examples.firstOrNull().orEmpty())
        .joinToString("\n") { it.replace(Regex("\\s+"), " ").take(400) }
    val answer = nanoSenseAnswer("$instruction No explanation. If unsure return NONE.\nExpression, category, definition, example:\n$context")
        .orEmpty().trim()
    val terms = if (task == SenseRequest.Synonyms) answer.split(',') else listOf(answer)
    return terms.map { it.trim().trim('"') }.filter {
        it.matches(Regex("""[\p{L}][\p{L}\p{M}'’ -]{0,79}""")) &&
            (task != SenseRequest.Translation || sense.language != "ja" || validJapaneseEquivalent(it)) && it.split(' ').size <= 9 && !it.equals("NONE", true) &&
            (task == SenseRequest.Translation || !it.equals(term, true))
    }.distinctBy { it.lowercase() }.take(4)
}

internal suspend fun nanoSenseAnswer(prompt: String, maxTokens: Int = 64): String? {
    if (NanoUsageNotes.unsupported) return null
    return optionalNanoCall(null) {
        val model = Generation.getClient()
        try {
            withTimeoutOrNull(60_000) {
                if (NanoUsageNotes.recordStatus(model.checkStatus()) != FeatureStatus.AVAILABLE) return@withTimeoutOrNull null
                val request = generateContentRequest(TextPart(prompt)) {
                    temperature = 0.1f
                    maxOutputTokens = maxTokens
                }
                model.generateContent(request).candidates.firstOrNull()?.text
            }
        } finally {
            model.close()
        }
    }
}

internal suspend fun <T> optionalNanoCall(fallback: T, call: suspend () -> T): T = try {
    call()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    fallback
} catch (_: LinkageError) {
    // An optional SDK can fail to load or initialise even before returning a model.
    fallback
}
