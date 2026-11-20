package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card

internal const val GEMINI_IPA = "Sugerido por Gemini"

internal data class IpaContext(
    val expression: String,
    val category: String = "",
    val definition: String = "",
    val example: String = "",
    val spanish: String = "",
    val usage: String = ""
)

internal fun ipaContext(
    expression: String, selected: Suggestion?, candidates: List<Suggestion>,
    original: Card?, lookupSucceeded: Boolean
): IpaContext? {
    if (expression.length !in 2..240) return null
    val sense = selected ?: candidates.singleOrNull()
    if (sense != null) return IpaContext(expression, sense.category, sense.definition,
        sense.examples.firstOrNull().orEmpty(), sense.spanish.firstOrNull().orEmpty(), sense.usage)
    if (candidates.isNotEmpty()) return null
    if (original != null && original.english == expression &&
        (original.definition.isNotBlank() || original.example.isNotBlank())) {
        return IpaContext(expression, original.senseCategories, original.definition,
            original.example.lineSequence().firstOrNull(String::isNotBlank).orEmpty(), original.spanish)
    }
    // With no usable entry, a full phrase can supply context. The model must abstain if ambiguous.
    return if (lookupSucceeded && expression.any(Char::isWhitespace)) IpaContext(expression) else null
}

internal fun ipaPrompt(context: IpaContext): String = buildString {
    appendLine("Return one broad phonemic IPA transcription in Standard Southern British English (UK).")
    appendLine("Transcribe the entire English expression as written, including every word, in the selected sense.")
    appendLine("Use exactly one /…/ transcription. No explanations, labels, variants or Markdown.")
    appendLine("If the intended pronunciation is ambiguous or uncertain, return NONE.")
    fun field(label: String, value: String) {
        if (value.isNotBlank()) appendLine("$label: ${value.replace(Regex("""\s+"""), " ").take(400)}")
    }
    field("Expression", context.expression)
    field("Category", context.category)
    field("Definition", context.definition)
    field("Usage", context.usage)
    field("Example", context.example)
    field("Spanish meaning", context.spanish)
}

// Allow phonemes, stress marks and word spaces, with a single pair of slash delimiters.
private val broadIpa = Regex("""/[a-zɐɑæɒəɚɛɜɡɪŋɔɹʃʊʌʒðθːˈˌ.\p{M} ]{1,118}/""")

internal fun parseSuggestedIpa(answer: String): String? = answer.trim().takeIf {
    broadIpa.matches(it) && it.any { symbol -> symbol.isLetter() && symbol !in "ˈˌː" }
}

internal suspend fun suggestIpa(context: IpaContext): String? = nanoSenseAnswer(ipaPrompt(context))
