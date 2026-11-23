package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun UsageNoteOffer(
    request: EditorRequest<UsageNoteResult>,
    onRequest: (Boolean) -> Unit,
    enabled: Boolean,
    synonyms: List<String>,
    onAddExample: (String) -> Unit
) {
    if (request.loading) {
        Text(stringResource(R.string.ui_preparing_usage_guide), style = MaterialTheme.typography.bodySmall)
        return
    }
    fun start(download: Boolean = false) = onRequest(download)
    when (val details = request.result) {
        null -> TextButton(onClick = { start() }, enabled = enabled) { Text(stringResource(R.string.ui_generate_guide)) }
        UsageNoteResult.Downloadable -> {
            Text(stringResource(R.string.ui_gemini_nano_is_ready_to_download_use_wi_fi_to_avoid_mobi))
            TextButton(onClick = { start(download = true) }, enabled = enabled) { Text(stringResource(R.string.ui_download_model)) }
        }
        UsageNoteResult.Downloading -> {
            Text(stringResource(R.string.ui_gemini_nano_is_downloading))
            TextButton(onClick = { start() }, enabled = enabled) { Text(stringResource(R.string.ui_check_again)) }
        }
        UsageNoteResult.Unavailable -> {
            Text(stringResource(R.string.ui_gemini_nano_is_unavailable_on_this_device), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { start() }, enabled = enabled) { Text(stringResource(R.string.ui_check_again)) }
        }
        UsageNoteResult.Error -> {
            Text(stringResource(R.string.ui_could_not_prepare_a_guide_you_can_keep_editing),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { start() }, enabled = enabled) { Text(stringResource(R.string.ui_retry_guide)) }
        }
        is UsageNoteResult.Ready -> {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ui_generated_by_gemini_nano), style = MaterialTheme.typography.labelMedium)
                    UsageGuideContent(details.note.forStorage(), synonyms) { index, example ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PronunciationButton(example, "Listen to example ${index + 1}")
                            TextButton(onClick = { onAddExample(example) }, enabled = enabled) {
                                Text("Add example ${index + 1}")
                            }
                        }
                    }
                    TextButton(onClick = { start() }, enabled = enabled) { Text(stringResource(R.string.ui_generate_again)) }
                }
            }
        }
    }
}

@Composable
internal fun UsageGuideContent(
    note: String,
    synonyms: List<String> = emptyList(),
    exampleActions: (@Composable (Int, String) -> Unit)? = null
) {
    val sections = remember(note, synonyms) { storedGuideSections(note, synonyms) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                section.title?.let { title ->
                    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                }
                if (section.title == "Examples" || section.title == "Synonyms") {
                    section.body.map(::guideListItem).filter(String::isNotBlank).forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", Modifier.clearAndSetSemantics {})
                            Column(Modifier.weight(1f)) {
                                Text(item)
                                if (section.title == "Examples") exampleActions?.invoke(index, item)
                            }
                        }
                    }
                } else {
                    Text(section.body.joinToString("\n"))
                }
            }
        }
    }
}

@Composable
internal fun SavedUsageContent(
    note: String,
    examples: String,
    onRemoveNote: (() -> Unit)? = null,
    onRemoveExample: ((String) -> Unit)? = null,
    guideSynonyms: List<String> = emptyList()
) {
    if (note.isBlank() && examples.isBlank()) return
    var showNote by rememberSaveable(note) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (examples.isNotBlank()) {
            Text(stringResource(R.string.ui_usage_examples_generated_by_gemini_nano), style = MaterialTheme.typography.labelMedium)
            examples.lineSequence().filter(String::isNotBlank).forEach { example ->
                Text(example)
                onRemoveExample?.let { remove ->
                    TextButton(onClick = { remove(example) }) { Text(stringResource(R.string.ui_remove_generated_example)) }
                }
            }
        }
        if (note.isNotBlank()) {
            TextButton(onClick = { showNote = !showNote }) {
                Text(if (showNote) "Hide saved guide" else "View saved guide")
            }
            if (showNote) {
                Text(stringResource(R.string.ui_generated_by_gemini_nano), style = MaterialTheme.typography.labelMedium)
                UsageGuideContent(note, guideSynonyms)
                onRemoveNote?.let { remove -> TextButton(onClick = remove) { Text(stringResource(R.string.ui_remove_guide)) } }
            }
        }
    }
}
