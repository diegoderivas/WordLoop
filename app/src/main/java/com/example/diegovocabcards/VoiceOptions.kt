package com.example.diegovocabcards

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
        "GB" -> "Británica"
        "US" -> "Estadounidense"
        "AU" -> "Australiana"
        else -> "Inglesa"
    }
    OutlinedButton(onClick = { player?.refreshVoices(); choosing = true }) { Text("Elegir voz inglesa") }
    if (choosing) DisposableEffect(player) { onDispose { player?.stop() } }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Voz de pronunciación") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onChoose(""); choosing = false }) {
                    Text(if (preference.isEmpty()) "✓ Automática (preferencia británica)" else "Automática (preferencia británica)")
                }
                for (voice in voices) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(modifier = Modifier.weight(1f), onClick = { onChoose(voice.name); choosing = false }) {
                            val checked = if (preference == voice.name) "✓ " else ""
                            Text("$checked${label(voice.locale.country)} · ${voice.name}")
                        }
                        IconButton(onClick = { player?.sample(voice.name) }) {
                            Icon(painterResource(R.drawable.pronunciation_speaker), "Probar voz ${voice.name}")
                        }
                    }
                }
                if (player?.requestedText == VOICE_SAMPLE && player.state == SpeechState.Unavailable) {
                    Text("No se pudo reproducir la muestra.")
                }
                if (voices.isEmpty()) Text("Sin variantes locales disponibles para elegir.")
                if (preference.isNotEmpty() && voices.none { it.name == preference }) {
                    Text("La voz elegida no está disponible. Se usará otra voz inglesa instalada cuando sea posible.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cerrar") } }
    )
}
