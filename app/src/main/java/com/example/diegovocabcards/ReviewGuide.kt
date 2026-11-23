package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val UNSUPPORTED_GUIDE = "This device doesn’t support automatic usage guide generation."

@Composable
internal fun ReviewCardTools(card: Card, model: VocabularyModel, generator: UsageNoteGenerator, onEdit: () -> Unit) {
    var open by rememberSaveable(card.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onEdit) { Text(stringResource(R.string.ui_edit_card)) }
        TextButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.usage_book), null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ui_usage_guide))
        }
    }
    if (open) ReviewGuideDialog(card, model, generator) { open = false }
}

@Composable
private fun ReviewGuideDialog(
    card: Card, model: VocabularyModel, generator: UsageNoteGenerator, close: () -> Unit
) {
    var attempt by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UsageNoteResult?>(null) }
    var progress by remember { mutableStateOf(UsageNoteProgress.Checking) }
    LaunchedEffect(card.id, card.usageNote, attempt) {
        if (card.usageNote.isNotBlank()) return@LaunchedEffect
        status = null
        progress = UsageNoteProgress.Checking
        val sense = Suggestion(card.definition, card.senseCategories, card.source, listOf(card.spanish),
            (card.example.lines() + card.generatedExamples.lines()).filter(String::isNotBlank), language = card.language)
        try {
            val result = generator.generate(usageNotePrompt(card.english, sense), download) { progress = it }
            currentCoroutineContext().ensureActive()
            status = if (result is UsageNoteResult.Ready) {
                if (model.saveMissingGuide(card, result.note)) result else UsageNoteResult.Error
            } else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = UsageNoteResult.Error
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.ui_usage_guide)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.english, style = MaterialTheme.typography.titleMedium)
                if (card.usageNote.isNotBlank()) {
                    Text(stringResource(R.string.ui_generated_by_gemini_nano), style = MaterialTheme.typography.labelMedium)
                    UsageGuideContent(card.usageNote, guideSynonyms(listOf(card.synonyms)))
                } else {
                    Text(when (status) {
                        UsageNoteResult.Downloadable -> "Download Gemini Nano to prepare the guide."
                        UsageNoteResult.Downloading -> "Gemini Nano is downloading."
                        UsageNoteResult.Unavailable -> UNSUPPORTED_GUIDE
                        UsageNoteResult.Error -> "Could not save a guide for this meaning."
                        is UsageNoteResult.Ready -> "Opening saved guide…"
                        null -> when (progress) {
                            UsageNoteProgress.Checking -> "Checking local model…"
                            UsageNoteProgress.Downloading -> "Downloading Gemini Nano…"
                            UsageNoteProgress.Generating -> "Preparing guide…"
                        }
                    })
                    if (status != null && status != UsageNoteResult.Unavailable && status !is UsageNoteResult.Ready) {
                        TextButton(onClick = { download = status == UsageNoteResult.Downloadable; attempt++ }) {
                            Text(if (status == UsageNoteResult.Downloadable) "Download model" else "Retry")
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text(if (card.usageNote.isBlank() && status == UsageNoteResult.Unavailable) "OK" else stringResource(R.string.ui_back_to_review)) } })
}
