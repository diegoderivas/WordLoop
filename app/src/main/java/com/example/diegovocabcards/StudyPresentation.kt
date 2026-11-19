package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card

@Composable
internal fun StudyPresentation(
    card: Card,
    spanishFirst: Boolean,
    revealed: Boolean,
    caption: String,
    onReveal: () -> Unit,
    cardTools: @Composable (Card) -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit
) {
    val player = LocalPronunciationPlayer.current
    DisposableEffect(player, card.id) { onDispose { player?.stop() } }
    if (revealed) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(caption, style = MaterialTheme.typography.labelLarge)
                cardTools(card)
                StudyTile(card, spanishFirst, revealed = true)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    } else {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(caption, style = MaterialTheme.typography.labelLarge)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                    contentAlignment = Alignment.Center
                ) {
                    StudyTile(card, spanishFirst, revealed = false)
                }
            }
            Button(onClick = onReveal, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("Mostrar respuesta", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StudyTile(card: Card, spanishFirst: Boolean, revealed: Boolean) {
    val attributed = GOOGLE_TRANSLATION in card.source
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (spanishFirst) card.spanish else card.english,
                    style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (!spanishFirst) IpaContent(card.ipa, card.ipaSource)
                if (spanishFirst && attributed) TranslationAttribution()
                if (spanishFirst && GEMINI_TRANSLATION in card.source) Text(GEMINI_TRANSLATION, style = MaterialTheme.typography.labelSmall)
            }
            if (revealed) {
                HorizontalDivider()
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (spanishFirst) card.english else card.spanish,
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    if (spanishFirst) IpaContent(card.ipa, card.ipaSource)
                    if (!spanishFirst && attributed) TranslationAttribution()
                    if (!spanishFirst && GEMINI_TRANSLATION in card.source) Text(GEMINI_TRANSLATION, style = MaterialTheme.typography.labelSmall)
                }
                PronunciationButton(card.english)
                if (card.example.isNotBlank()) Text(card.example, textAlign = TextAlign.Center)
                SavedUsageContent("", card.generatedExamples)
            }
        }
    }
}
