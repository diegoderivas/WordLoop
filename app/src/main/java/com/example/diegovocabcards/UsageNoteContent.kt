package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun UsageNoteOffer(
    request: EditorRequest<UsageNoteResult>,
    onRequest: (Boolean) -> Unit,
    enabled: Boolean,
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
            val note = details.note
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {

                    Text(stringResource(R.string.ui_generated_by_gemini_nano), style = MaterialTheme.typography.labelMedium)
                    if (note.examples.isNotEmpty()) Text(stringResource(R.string.ui_examples), style = MaterialTheme.typography.titleSmall)
                    note.examples.forEachIndexed { index, example ->
                        Text(example)
                        PronunciationButton(example, "Listen to example ${index + 1}")
                        TextButton(onClick = { onAddExample(example) }, enabled = enabled) {
                            Text("Add example ${index + 1}")
                        }
                    }
                    TextButton(onClick = { start() }, enabled = enabled) { Text(stringResource(R.string.ui_generate_again)) }
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
    onRemoveExample: ((String) -> Unit)? = null
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
                Text(note)
                onRemoveNote?.let { remove -> TextButton(onClick = remove) { Text(stringResource(R.string.ui_remove_guide)) } }
            }
        }
    }
}
