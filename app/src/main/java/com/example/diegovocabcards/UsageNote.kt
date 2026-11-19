package com.example.diegovocabcards

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal const val GENERATED_WITH_NANO = "Generado con Gemini Nano"

internal data class UsageNote(val explanation: String, val examples: List<String>, val nuance: String?) {
    fun forStorage(): String = buildList {
        add(explanation)
        add(examples.joinToString("\n"))
        nuance?.let { add("Matiz · $it") }
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
    appendLine("Help a Spanish speaker learn this English expression for the specified sense only.")
    appendLine("Write one brief explanation in Spanish and two distinct, natural English sentences.")
    appendLine("If useful, add one short contrast in Spanish with a related English word.")
    appendLine("Use one line per label, in this order: USO:, EJEMPLO 1:, EJEMPLO 2:, MATIZ: (omit if unnecessary).")
    appendLine()
    appendLine("## Dictionary data")
    appendLine("Expression: ${term.oneLine().take(240)}")
    appendLine("Category: ${categoryLabel(sense.category).oneLine().take(80)}")
    appendLine("Definition: ${sense.definition.oneLine().take(400)}")
    sense.examples.take(2).forEach { appendLine("Dictionary example: ${it.oneLine().take(220)}") }
}

private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

internal fun parseUsageNote(response: String): UsageNote? {
    val labels = listOf("USO", "EJEMPLO 1", "EJEMPLO 2", "MATIZ")
    val lines = response.lineSequence().map { it.trim().replace("**", "") }
        .filter { it.isNotBlank() && !it.startsWith("```") }.toList()
    if (lines.size !in 3..4) return null
    val fields = lines.mapIndexed { index, line ->
        val label = labels[index] + ":"
        if (!line.startsWith(label, ignoreCase = true)) return null
        line.substring(label.length).trim()
    }
    val (explanation, first, second) = fields
    if (explanation.isBlank() || explanation.length > 320) return null
    if (first.length !in 5..240 || second.length !in 5..240 || first.equals(second, true)) return null
    val nuance = fields.getOrNull(3)?.takeIf { it.isNotBlank() && it.length <= 240 }
    return UsageNote(explanation, listOf(first, second), nuance)
}

internal object NanoUsageNotes : UsageNoteGenerator {
    override suspend fun generate(
        prompt: String, download: Boolean, onProgress: (UsageNoteProgress) -> Unit
    ): UsageNoteResult = try {
        generateWithModel(prompt, download, onProgress)
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
                FeatureStatus.UNAVAILABLE -> return UsageNoteResult.Unavailable
                else -> return UsageNoteResult.Error
            }
            onProgress(UsageNoteProgress.Generating)
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = 0.2f
                maxOutputTokens = 280
            }
            val candidate = withTimeoutOrNull(60_000) {
                model.generateContent(request).candidates.firstOrNull()?.text
            }
            return candidate?.let(::parseUsageNote)?.let(UsageNoteResult::Ready) ?: UsageNoteResult.Error
        } finally {
            model.close()
        }
    }
}
