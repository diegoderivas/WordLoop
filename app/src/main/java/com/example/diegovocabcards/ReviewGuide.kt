package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun ReviewCardTools(card: Card, model: VocabularyModel, generator: UsageNoteGenerator, onEdit: () -> Unit) {
    var open by rememberSaveable(card.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onEdit) { Text("Editar tarjeta") }
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.usage_book), "Guía de uso")
        }
    }
    if (open) ReviewGuideDialog(card, model, generator) { open = false }
}

@Composable
private fun ReviewGuideDialog(
    card: Card, model: VocabularyModel, generator: UsageNoteGenerator, close: () -> Unit
) {
    var attempt by remember { mutableIntStateOf(0) }
    var download by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<UsageNoteResult?>(null) }
    var progress by remember { mutableStateOf(UsageNoteProgress.Checking) }
    LaunchedEffect(card.id, card.usageNote, attempt) {
        if (card.usageNote.isNotBlank()) return@LaunchedEffect
        status = null
        progress = UsageNoteProgress.Checking
        val sense = Suggestion(card.definition, card.senseCategories, card.source, listOf(card.spanish),
            (card.example.lines() + card.generatedExamples.lines()).filter(String::isNotBlank))
        try {
            val result = generator.generate(usageNotePrompt(card.english, sense), download) { progress = it }
            currentCoroutineContext().ensureActive()
            status = if (result is UsageNoteResult.Ready) {
                if (model.saveMissingGuide(card, result.note)) result else UsageNoteResult.Error
            } else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = UsageNoteResult.Error
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Guía de uso") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.english, style = MaterialTheme.typography.titleMedium)
                if (card.usageNote.isNotBlank()) {
                    Text(GENERATED_WITH_NANO, style = MaterialTheme.typography.labelMedium)
                    Text(card.usageNote)
                } else {
                    Text(when (status) {
                        UsageNoteResult.Downloadable -> "Para preparar la guía, descargá Gemini Nano en el dispositivo."
                        UsageNoteResult.Downloading -> "La descarga de Gemini Nano está en curso."
                        UsageNoteResult.Unavailable -> "Gemini Nano no está disponible en este dispositivo."
                        UsageNoteResult.Error -> "No se pudo guardar una guía para esta acepción."
                        is UsageNoteResult.Ready -> "Abriendo la guía guardada…"
                        null -> when (progress) {
                            UsageNoteProgress.Checking -> "Comprobando el modelo local…"
                            UsageNoteProgress.Downloading -> "Descargando Gemini Nano…"
                            UsageNoteProgress.Generating -> "Preparando la guía…"
                        }
                    })
                    if (status != null && status !is UsageNoteResult.Ready) {
                        TextButton(onClick = { download = status == UsageNoteResult.Downloadable; attempt++ }) {
                            Text(if (status == UsageNoteResult.Downloadable) "Descargar modelo" else "Reintentar")
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text("Volver al repaso") } })
}
