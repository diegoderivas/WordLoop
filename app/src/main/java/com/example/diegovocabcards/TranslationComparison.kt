package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun TranslationComparison(
    current: String,
    currentSource: String?,
    sense: Suggestion,
    requests: EditorSenseRequests,
    enabled: Boolean,
    onApply: (String, String) -> Unit,
    onGoogle: () -> Unit,
    onGemini: () -> Unit
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ui_choose_translation_suggestion),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TranslationProvider(
            stringResource(R.string.ui_gemini_nano), requests.translation, R.string.ui_translate_with_gemini,
            current.takeIf { currentSource == GEMINI_TRANSLATION }, enabled, onGemini
        ) { onApply(it, GEMINI_TRANSLATION) }
        HorizontalDivider()
        TranslationProvider(
            stringResource(R.string.ui_google_translate), requests.google, R.string.ui_translate_with_google,
            current.takeIf { currentSource == GOOGLE_TRANSLATION }, enabled, onGoogle,
            showGoogleAttribution = currentSource != GOOGLE_TRANSLATION
        ) { onApply(it, GOOGLE_TRANSLATION) }
        val dictionary = sense.spanish.filter(String::isNotBlank).distinctBy(::senseText)
        if (dictionary.isNotEmpty()) {
            HorizontalDivider()
            Column {
                TranslationSourceTitle(stringResource(R.string.ui_translation_dictionary))
                dictionary.forEach { option ->
                    TranslationChoice(option, currentSource == sense.source && current == option, enabled) {
                        onApply(option, sense.source)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationProvider(
    name: String,
    request: EditorRequest<List<String>>,
    action: Int,
    selected: String?,
    enabled: Boolean,
    onRequest: () -> Unit,
    showGoogleAttribution: Boolean = false,
    onApply: (String) -> Unit
) {
    val option = request.result?.firstOrNull()
    Column {
        TranslationSourceTitle(name)
        if (option != null) {
            TranslationChoice(option, option == selected, enabled) { onApply(option) }
            if (showGoogleAttribution) TranslationAttribution()
        }
        when {
            request.loading -> Text(
                stringResource(R.string.ui_finding_translations),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall
            )
            option == null -> {
                if (request.result != null) Text(stringResource(R.string.ui_translation_no_suggestion),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRequest, enabled = enabled) { Text(stringResource(action)) }
            }
        }
    }
}

@Composable
private fun TranslationSourceTitle(name: String) {
    Text(name, Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TranslationChoice(value: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
