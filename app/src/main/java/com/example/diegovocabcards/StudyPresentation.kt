package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    onDelete: (suspend (String) -> Unit)? = null,
    ratingPending: Boolean = false,
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
    var confirming by rememberSaveable(card.id) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (onDelete != null) {
                IconButton(onClick = { deleteFailed = false; confirming = true },
                    enabled = !ratingPending && !deleting, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.delete_card_outline), stringResource(R.string.ui_delete_card),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        StudyTile(card, spanishFirst, revealed, Modifier.weight(1f).fillMaxWidth())
        StudyFooter(revealed, onReveal) {
            cardTools(card)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
    if (confirming && onDelete != null) {
        DeleteMeaningDialog(
            card = card,
            deleting = deleting,
            failed = deleteFailed,
            confirmEnabled = !ratingPending,
            onCancel = { confirming = false },
            onConfirm = {
                if (!deleting && !ratingPending) {
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        try {
                            onDelete(card.id)
                            confirming = false
                        } catch (_: SQLiteException) {
                            deleteFailed = true
                        } finally {
                            deleting = false
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun StudyFooter(
    revealed: Boolean,
    onReveal: () -> Unit,
    controls: @Composable ColumnScope.() -> Unit
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Column(
                if (revealed) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = controls
            )
            if (!revealed) {
                FilledTonalButton(
                    onClick = onReveal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Text(stringResource(R.string.ui_show_answer), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { measurables, constraints ->
        // Measure the same controls in both phases and place only the active panel.
        val answer = measurables.first().measure(constraints)
        val reservedHeight = maxOf(answer.height, 54.dp.roundToPx()).coerceAtMost(constraints.maxHeight)
        val reveal = measurables.getOrNull(1)?.measure(constraints.copy(minHeight = reservedHeight, maxHeight = reservedHeight))
        layout(answer.width, reservedHeight) {
            if (reveal != null) reveal.placeRelative(0, 0)
            else answer.placeRelative(0, reservedHeight - answer.height)
        }
    }
}

@Composable
private fun StudyTile(card: Card, spanishFirst: Boolean, revealed: Boolean, modifier: Modifier) {
    val attributed = GOOGLE_TRANSLATION in card.source
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
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
                        Text("Hint · $it", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
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
}

@Composable
private fun EnglishStudyText(card: Card, style: androidx.compose.ui.text.TextStyle) {
    EnglishPronunciation(card.english, preferredIpa(card.ipa)) {
        Spacer(Modifier.width(48.dp))
        Text(card.english, Modifier.weight(1f), style = style, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SpanishStudyText(card: Card, style: androidx.compose.ui.text.TextStyle, attributed: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.spanish, style = style, textAlign = TextAlign.Center)
        if (attributed) TranslationAttribution()
        if (GEMINI_TRANSLATION in card.source) Text(stringResource(R.string.ui_translation_suggested_by_gemini_nano), style = MaterialTheme.typography.labelSmall)
    }
}
