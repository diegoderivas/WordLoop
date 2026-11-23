package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun VoiceOptions(preference: String, onChoose: (String) -> Unit) {
    val player = LocalPronunciationPlayer.current
    var choosing by remember { mutableStateOf(false) }
    val voices = player?.voices.orEmpty()
    val automatic = stringResource(R.string.ui_voice_automatic)
    val labels = voices.groupBy { it.locale.country }.flatMap { (country, group) ->
        val accent = stringResource(when (country) {
            "GB" -> R.string.ui_voice_british
            "US" -> R.string.ui_voice_american
            "AU" -> R.string.ui_voice_australian
            else -> R.string.ui_voice_english
        })
        group.mapIndexed { index, voice ->
            voice.name to if (group.size == 1) accent else stringResource(R.string.ui_voice_variant, accent, index + 1)
        }
    }.toMap()
    val selectedVoice = if (preference.isEmpty()) automatic
        else labels[preference] ?: stringResource(R.string.ui_voice_unavailable)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { player?.refreshVoices(); choosing = true }) { Text(stringResource(R.string.ui_english_voice)) }
        Text(selectedVoice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (choosing) DisposableEffect(player) { onDispose { player?.stop() } }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.ui_pronunciation_voice)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                VoiceChoice(automatic, preference.isEmpty(), Modifier.fillMaxWidth()) {
                    onChoose("")
                    choosing = false
                }
                for (voice in voices) {
                    val name = labels.getValue(voice.name)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        VoiceChoice(name, preference == voice.name, Modifier.weight(1f)) {
                            onChoose(voice.name)
                            choosing = false
                        }
                        IconButton(onClick = { player?.sample(voice.name) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.pronunciation_speaker),
                                stringResource(R.string.ui_preview_voice, name))
                        }
                    }
                }
                if (player?.requestedText == VOICE_SAMPLE && player.state == SpeechState.Unavailable) {
                    Text(stringResource(R.string.ui_could_not_play_this_sample))
                }
                if (voices.isEmpty()) Text(stringResource(R.string.ui_no_local_voices_available))
                if (preference.isNotEmpty() && voices.none { it.name == preference }) {
                    Text(stringResource(R.string.ui_the_selected_voice_is_unavailable_another_installed_engl))
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.ui_close)) } }
    )
}

@Composable
private fun VoiceChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
