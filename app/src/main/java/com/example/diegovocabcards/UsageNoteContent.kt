package com.example.diegovocabcards

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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun UsageNoteOffer(
    term: String,
    sense: Suggestion,
    generator: UsageNoteGenerator,
    enabled: Boolean,
    hasNote: Boolean,
    onSaveNote: (UsageNote) -> Unit,
    onAddExample: (String) -> Unit
) {
    val focus = LocalFocusManager.current
    var request by remember { mutableStateOf<Boolean?>(null) }
    var progress by remember { mutableStateOf(UsageNoteProgress.Checking) }
    var result by remember { mutableStateOf<UsageNoteResult?>(null) }
    LaunchedEffect(request) {
        val download = request ?: return@LaunchedEffect
        val generated = generator.generate(usageNotePrompt(term, sense), download) { progress = it }
        currentCoroutineContext().ensureActive()
        result = generated
        request = null
    }

    fun start(download: Boolean = false) {
        focus.clearFocus()
        progress = UsageNoteProgress.Checking
        result = null
        request = download
    }

    if (request != null) {
        Text(when (progress) {
            UsageNoteProgress.Checking -> "Comprobando el modelo local…"
            UsageNoteProgress.Downloading -> "Descargando Gemini Nano…"
            UsageNoteProgress.Generating -> "Preparando la nota de uso…"
        }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { request = null }) { Text("Cancelar nota") }
        return
    }

    when (val details = result) {
        null -> TextButton(onClick = { start() }, enabled = enabled) { Text("Entender el uso") }
        UsageNoteResult.Downloadable -> {
            Text("Gemini Nano está listo para descargar. Usá Wi-Fi si querés evitar el consumo de datos móviles.")
            TextButton(onClick = { start(download = true) }, enabled = enabled) { Text("Descargar modelo") }
        }
        UsageNoteResult.Downloading -> {
            Text("La descarga de Gemini Nano está en curso.")
            TextButton(onClick = { start() }, enabled = enabled) { Text("Volver a comprobar") }
        }
        UsageNoteResult.Unavailable -> {
            Text("Gemini Nano no está disponible en este dispositivo.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { start() }, enabled = enabled) { Text("Volver a comprobar") }
        }
        UsageNoteResult.Error -> {
            Text("No se pudo preparar una nota. Podés seguir editando la tarjeta.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { start() }, enabled = enabled) { Text("Reintentar nota") }
        }
        is UsageNoteResult.Ready -> {
            val note = details.note
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Nota de uso", style = MaterialTheme.typography.titleMedium)
                    Text(GENERATED_WITH_NANO, style = MaterialTheme.typography.labelMedium)
                    note.sections().forEach { (title, content) ->
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(content)
                    }
                    if (note.examples.isNotEmpty()) Text("Examples", style = MaterialTheme.typography.titleSmall)
                    note.examples.forEachIndexed { index, example ->
                        Text(example)
                        PronunciationButton(example, "Escuchar ejemplo ${index + 1}")
                        TextButton(onClick = { onAddExample(example); result = null }, enabled = enabled) {
                            Text("Añadir solo el ejemplo ${index + 1}")
                        }
                    }
                    TextButton(onClick = { onSaveNote(note); result = null }, enabled = enabled) {
                        Text(if (hasNote) "Reemplazar nota" else "Guardar nota")
                    }
                    TextButton(onClick = { start() }, enabled = enabled) { Text("Regenerar nota") }
                    TextButton(onClick = { result = null }) { Text("Seguir sin usarla") }
                }
            }
        }
    }
    if (result != null && result !is UsageNoteResult.Ready) {
        TextButton(onClick = { result = null }) { Text("Cerrar nota") }
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
    var showNote by rememberSaveable(note) { mutableStateOf(onRemoveNote != null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (examples.isNotBlank()) {
            Text("Ejemplos de uso · $GENERATED_WITH_NANO", style = MaterialTheme.typography.labelMedium)
            examples.lineSequence().filter(String::isNotBlank).forEach { example ->
                Text(example)
                onRemoveExample?.let { remove ->
                    TextButton(onClick = { remove(example) }) { Text("Quitar ejemplo generado") }
                }
            }
        }
        if (note.isNotBlank()) {
            TextButton(onClick = { showNote = !showNote }) {
                Text(if (showNote) "Ocultar nota de uso" else "Ver nota de uso")
            }
            if (showNote) {
                Text(GENERATED_WITH_NANO, style = MaterialTheme.typography.labelMedium)
                Text(note)
                onRemoveNote?.let { remove -> TextButton(onClick = remove) { Text("Quitar nota de uso") } }
            }
        }
    }
}
