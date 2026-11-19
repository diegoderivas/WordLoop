package com.example.diegovocabcards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
internal fun VoiceOptions(preference: String, onChoose: (String) -> Unit) {
    val player = LocalPronunciationPlayer.current
    var choosing by remember { mutableStateOf(false) }
    val voices = player?.voices.orEmpty().filter { it.locale.country in listOf("GB", "US", "AU") }
    fun label(country: String) = when (country) {
        "GB" -> "Británica"
        "US" -> "Estadounidense"
        "AU" -> "Australiana"
        else -> "Inglesa"
    }
    OutlinedButton(onClick = { player?.refreshVoices(); choosing = true }) { Text("Elegir voz inglesa") }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Voz de pronunciación") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onChoose(""); choosing = false }) {
                    Text(if (preference.isEmpty()) "✓ Automática (preferencia británica)" else "Automática (preferencia británica)")
                }
                for (voice in voices) {
                    TextButton(onClick = { onChoose(voice.name); choosing = false }) {
                        val checked = if (preference == voice.name) "✓ " else ""
                        Text("$checked${label(voice.locale.country)} · ${voice.name}")
                    }
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
