package com.example.diegovocabcards

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.withTimeoutOrNull

internal const val GENERATED_WITH_NANO = "Generado con Gemini Nano"

internal data class UsageNote(
    val meaning: String,
    val usage: String,
    val examples: List<String>,
    val nuance: String? = null,
    val similar: String? = null,
    val synonyms: List<String> = emptyList()
) {
    fun sections(): List<Pair<String, String>> = buildList {
        add("Meaning" to meaning)
        if (synonyms.isNotEmpty()) add("Synonyms" to guideSynonyms(synonyms).joinToString("\n"))
        add("How to use it" to usage)
        nuance?.let { add("Nuance" to it) }
        similar?.let { add("Similar words" to it) }
    }

    fun forStorage(): String = buildList {
        addAll(sections().map { (title, content) -> "$title\n$content" })
        if (examples.isNotEmpty()) add("Examples\n${examples.joinToString("\n")}")
    }.joinToString("\n\n")
}

internal fun guideSynonyms(vararg groups: List<String>): List<String> =
    groups.asSequence().flatMap { it.asSequence() }
        .flatMap { it.splitToSequence(',', '\n') }
        .map(::guideListItem)
        .filter { it.isNotBlank() && !placeholder.matches(it.trim('(', ')')) }
        .distinctBy(::senseText)
        .take(6).toList()

private val guideBullet = Regex("""^(?:[•*-]|\d+[.)])\s+""")
internal fun guideListItem(line: String): String = line.trim().replaceFirst(guideBullet, "")

internal data class GuideSection(val title: String?, val body: List<String>)

internal fun storedGuideSections(note: String, synonyms: List<String>): List<GuideSection> {
    val headings = listOf("Meaning", "Synonyms", "How to use it", "Nuance", "Similar words", "Examples")
    val sections = buildList {
        var title: String? = null
        val body = mutableListOf<String>()
        fun addSection() {
            val content = body.dropWhile(String::isBlank).dropLastWhile(String::isBlank)
            if (content.isNotEmpty()) add(GuideSection(title, content))
        }
        for (line in note.lineSequence()) {
            val heading = line.trim().trimEnd(':').removeSurrounding("**").trimEnd(':')
            val knownHeading = headings.firstOrNull { it.equals(heading, ignoreCase = true) }
            if (knownHeading == null) {
                body += line
            } else {
                addSection()
                title = knownHeading
                body.clear()
            }
        }
        addSection()
    }
    val words = guideSynonyms(synonyms, sections.filter { it.title == "Synonyms" }.flatMap { it.body })
    val ordered = sections.filterNot { it.title == "Synonyms" }.toMutableList()
    if (words.isNotEmpty()) {
        val meaning = ordered.indexOfFirst { it.title == "Meaning" }
        val usage = ordered.indexOfFirst { it.title == "How to use it" }
        val position = if (meaning >= 0) meaning + 1 else if (usage >= 0) usage else ordered.size
        ordered.add(position, GuideSection("Synonyms", words))
    }
    return ordered
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
    appendLine("SYNONYMS: Optional. Up to six interchangeable English words or phrases for this sense, separated by commas.")
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

private val noteFields = listOf("meaning", "synonyms", "how to use it", "nuance", "similar words", "example 1", "example 2", "example 3")
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
    val synonyms = field("synonyms", 300)?.split(',').orEmpty()
        .filter { it.trim().matches(Regex("""[A-Za-z][A-Za-z'’ -]{0,49}""")) }
    return UsageNote(meaning, usage, examples, field("nuance", 320), field("similar words", 320), guideSynonyms(synonyms))
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
        return optionalNanoCall(false) {
            val model = Generation.getClient()
            try { withTimeoutOrNull(15_000) { recordStatus(model.checkStatus()) } == FeatureStatus.UNAVAILABLE }
            finally { model.close() }
        }
    }

    override suspend fun generate(
        prompt: String, download: Boolean, onProgress: (UsageNoteProgress) -> Unit
    ): UsageNoteResult = optionalNanoCall(UsageNoteResult.Error) {
        if (unsupported) UsageNoteResult.Unavailable else generateWithModel(prompt, download, onProgress)
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
                maxOutputTokens = 620
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
