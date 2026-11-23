package com.example.diegovocabcards

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal const val GENERATED_WITH_NANO = "Generado con Gemini Nano"

internal data class UsageNote(
    val meaning: String,
    val usage: String,
    val examples: List<String>,
    val nuance: String? = null,
    val similar: String? = null
) {
    fun sections(): List<Pair<String, String>> = buildList {
        add("Meaning" to meaning)
        add("How to use it" to usage)
        nuance?.let { add("Nuance" to it) }
        similar?.let { add("Similar words" to it) }
    }

    fun forStorage(): String = buildList {
        addAll(sections().map { (title, content) -> "$title\n$content" })
        if (examples.isNotEmpty()) add("Examples\n${examples.joinToString("\n")}")
    }.joinToString("\n\n")
}

internal enum class UsageNoteProgress { Checking, Downloading, Generating }

internal sealed interface UsageNoteResult {
    data class Ready(val note: UsageNote) : UsageNoteResult
    data object Downloadable : UsageNoteResult
    data object Downloading : UsageNoteResult
    data object Unavailable : UsageNoteResult
    data object Error : UsageNoteResult
}

internal fun interface UsageNoteGenerator {
    suspend fun generate(
        prompt: String, download: Boolean, onProgress: (UsageNoteProgress) -> Unit
    ): UsageNoteResult
}

internal fun usageNotePrompt(term: String, sense: Suggestion): String = buildString {
    appendLine("Write a short learner's guide for the single English sense below. Write EVERY field in clear English, even if the input contains a non-English translation.")
    appendLine("Use these labels, one field per line. No introduction or placeholders.")
    appendLine("MEANING: 2–3 complete sentences. Explain what the expression communicates here. Where relevant, explain the feeling, implication, or tone.")
    appendLine("HOW TO USE IT: Give natural collocations or phrases and explain when they fit. Avoid generic grammar rules.")
    appendLine("NUANCE: Optional. Describe a specific tone, implication, or register.")
    appendLine("SIMILAR WORDS: Optional. Contrast one close word only if it helps.")
    appendLine("EXAMPLE 1:, EXAMPLE 2:, EXAMPLE 3: One complete natural English sentence per line using the expression or an inflected form in this sense. Use different situations.")
    appendLine("Skip any optional label or example you cannot support.")
    appendLine()
    appendLine("## Selected dictionary sense")
    appendLine("Expression: ${term.oneLine().take(240)}")
    appendLine("Category: ${sense.category.oneLine().take(80)}")
    appendLine("Definition: ${sense.definition.oneLine().take(400)}")
    sense.usage.takeIf(String::isNotBlank)?.let { appendLine("Usage label: ${it.oneLine().take(120)}") }
    sense.spanish.firstOrNull()?.let { appendLine("${languageName(sense.language)} gloss (context): ${it.oneLine().take(120)}") }
    sense.examples.take(2).forEach { appendLine("Dictionary example: ${it.oneLine().take(220)}") }
}

private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

private val noteFields = listOf("meaning", "how to use it", "nuance", "similar words", "example 1", "example 2", "example 3")
private val placeholder = Regex("""(?i)^(?:omit(?:ted| if.*)?|optional|skip|none|n/?a|not applicable|null)[.!]?$""")
private val sentenceEnd = Regex("""[.!?]+["'”’)]*(?=\s|$)""")
private val word = Regex("""\p{L}+""")

internal fun parseUsageNote(response: String): UsageNote? {
    val values = mutableMapOf<String, String>()
    for (raw in response.lineSequence()) {
        val line = raw.trim().replace("**", "").removePrefix("- ").trim()
        if (line.isEmpty() || line.startsWith("```")) continue
        val label = line.substringBefore(':').trim().lowercase()
        if (label !in noteFields || ':' !in line || label in values) return null
        values[label] = line.substringAfter(':').trim()
    }
    fun field(label: String, limit: Int): String? = values[label]?.takeIf {
        val marker = it.trim(' ', '(', ')', '[', ']', '<', '>', '"')
        it.length <= limit && word.containsMatchIn(marker) && !placeholder.matches(marker)
    }

    val meaning = field("meaning", 650) ?: return null
    val usage = field("how to use it", 500) ?: return null
    val endings = sentenceEnd.findAll(meaning).toList()
    if (endings.size < 2 || endings.last().range.last != meaning.lastIndex || word.findAll(meaning).count() < 8) return null

    val examples = (1..3).mapNotNull { field("example $it", 240) }
        .filter { sentenceEnd.findAll(it).lastOrNull()?.range?.last == it.lastIndex }
        .distinctBy(::senseText)
    return UsageNote(meaning, usage, examples, field("nuance", 320), field("similar words", 320))
}

internal suspend fun validateUsageNote(response: String): UsageNote? {
    val note = parseUsageNote(response) ?: return null
    val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build()
    )
    try {
        suspend fun english(text: String) = identifier.identifyLanguage(text).awaitResult() == "en"
        if (!english(note.meaning) || !english(note.usage)) return null
        return note.copy(
            examples = note.examples.filter { english(it) },
            nuance = note.nuance?.takeIf { english(it) },
            similar = note.similar?.takeIf { english(it) }
        )
    } finally {
        identifier.close()
    }
}

internal object NanoUsageNotes : UsageNoteGenerator {
    @Volatile internal var unsupported = false
        private set

    fun recordStatus(status: Int): Int {
        if (status == FeatureStatus.UNAVAILABLE) unsupported = true
        return status
    }

    suspend fun checkUnsupported(): Boolean {
        if (unsupported) return true
        return try {
            val model = Generation.getClient()
            try { withTimeoutOrNull(15_000) { recordStatus(model.checkStatus()) } == FeatureStatus.UNAVAILABLE }
            finally { model.close() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override suspend fun generate(
        prompt: String, download: Boolean, onProgress: (UsageNoteProgress) -> Unit
    ): UsageNoteResult = try {
        if (unsupported) UsageNoteResult.Unavailable else generateWithModel(prompt, download, onProgress)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        UsageNoteResult.Error
    }

    private suspend fun generateWithModel(
        prompt: String, download: Boolean, onProgress: (UsageNoteProgress) -> Unit
    ): UsageNoteResult {
        val model = Generation.getClient()
        try {
            when (withTimeoutOrNull(15_000) { model.checkStatus() }) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADING -> return UsageNoteResult.Downloading
                FeatureStatus.DOWNLOADABLE -> {
                    if (!download) return UsageNoteResult.Downloadable
                    onProgress(UsageNoteProgress.Downloading)
                    var completed = false
                    val downloaded = withTimeoutOrNull(300_000) {
                        model.download().collect { status ->
                            when (status) {
                                DownloadStatus.DownloadCompleted -> completed = true
                                is DownloadStatus.DownloadFailed -> throw status.e
                                else -> Unit
                            }
                        }
                        completed && model.checkStatus() == FeatureStatus.AVAILABLE
                    }
                    if (downloaded != true) return UsageNoteResult.Error
                }
                FeatureStatus.UNAVAILABLE -> { unsupported = true; return UsageNoteResult.Unavailable }
                else -> return UsageNoteResult.Error
            }
            onProgress(UsageNoteProgress.Generating)
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                maxOutputTokens = 560
            }
            val candidate = withTimeoutOrNull(60_000) {
                model.generateContent(request).candidates.firstOrNull()?.text?.let { validateUsageNote(it) }
            }
            return candidate?.let(UsageNoteResult::Ready) ?: UsageNoteResult.Error
        } finally {
            model.close()
        }
    }
}
