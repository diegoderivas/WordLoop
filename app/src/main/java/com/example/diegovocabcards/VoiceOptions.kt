package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
internal fun VoiceOptions(preference: String, onChoose: (String) -> Unit) {
    val player = LocalPronunciationPlayer.current
    var choosing by remember { mutableStateOf(false) }
    val voices = player?.voices.orEmpty()
    fun label(country: String) = when (country) {
        "GB" -> "British"
        "US" -> "American"
        "AU" -> "Australian"
        else -> "English"
    }
    OutlinedButton(onClick = { player?.refreshVoices(); choosing = true }) { Text(stringResource(R.string.ui_english_voice)) }
    if (choosing) DisposableEffect(player) { onDispose { player?.stop() } }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(stringResource(R.string.ui_pronunciation_voice)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onChoose(""); choosing = false }) {
                    Text(if (preference.isEmpty()) "✓ Automatic (British preferred)" else "Automatic (British preferred)")
                }
                for (voice in voices) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(modifier = Modifier.weight(1f), onClick = { onChoose(voice.name); choosing = false }) {
                            val checked = if (preference == voice.name) "✓ " else ""
                            Text("$checked${label(voice.locale.country)} · ${voice.name}")
                        }
                        IconButton(onClick = { player?.sample(voice.name) }) {
                            Icon(painterResource(R.drawable.pronunciation_speaker), "Preview ${voice.name}")
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
