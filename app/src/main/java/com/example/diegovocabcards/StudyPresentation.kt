package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    val automatic = LocalAutomaticSpeech.current
    var pronounced by rememberSaveable(card.id, spanishFirst) { mutableStateOf(false) }
    LaunchedEffect(player, automatic, spanishFirst, revealed) {
        if (automatic && player != null && !pronounced && (!spanishFirst || revealed)) {
            pronounced = true
            player.speak(card.english)
        }
    }
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
            val questionStyle = MaterialTheme.typography.headlineMedium
            val answerStyle = MaterialTheme.typography.titleLarge
            if (spanishFirst) {
                SpanishStudyText(card, questionStyle, attributed)
            } else {
                EnglishStudyText(card, questionStyle)
            }
            if (!revealed) {
                val library = LocalStudyCards.current
                remember(card, library, spanishFirst) { studyHint(card, library, spanishFirst) }?.let {
                    Text("Pista · $it", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            if (revealed) {
                HorizontalDivider()
                if (spanishFirst) {
                    EnglishStudyText(card, answerStyle)
                } else {
                    SpanishStudyText(card, answerStyle, attributed)
                }
                if (card.example.isNotBlank()) Text(card.example, textAlign = TextAlign.Center)
                SavedUsageContent("", card.generatedExamples)
            }
        }
    }
}

@Composable
private fun EnglishStudyText(card: Card, style: androidx.compose.ui.text.TextStyle) {
    EnglishPronunciation(card.english, card.ipa) {
        Spacer(Modifier.width(48.dp))
        Text(card.english, Modifier.weight(1f), style = style, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SpanishStudyText(card: Card, style: androidx.compose.ui.text.TextStyle, attributed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.spanish, style = style, textAlign = TextAlign.Center)
        if (attributed) TranslationAttribution()
        if (GEMINI_TRANSLATION in card.source) Text(GEMINI_TRANSLATION, style = MaterialTheme.typography.labelSmall)
    }
}
