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
import androidx.compose.ui.unit.dp

@Composable
internal fun UsageNoteOffer(
    request: EditorRequest<UsageNoteResult>,
    onRequest: (Boolean) -> Unit,
    enabled: Boolean,
    hasNote: Boolean,
    onSaveNote: (UsageNote) -> Unit,
    onAddExample: (String) -> Unit
) {
    var hidden by remember(request.result) { mutableStateOf(false) }
    fun start(download: Boolean = false) {
        hidden = false
        onRequest(download)
    }

    if (request.loading) {
        Text("Preparando la guía de uso…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val result = request.result.takeUnless { hidden }
    when (val details = result) {
        null -> TextButton(onClick = { if (hidden) hidden = false else start() }, enabled = enabled) { Text("Entender el uso") }
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
                        TextButton(onClick = { onAddExample(example); hidden = true }, enabled = enabled) {
                            Text("Añadir solo el ejemplo ${index + 1}")
                        }
                    }
                    TextButton(onClick = { onSaveNote(note); hidden = true }, enabled = enabled) {
                        Text(if (hasNote) "Reemplazar nota" else "Guardar nota")
                    }
                    TextButton(onClick = { start() }, enabled = enabled) { Text("Regenerar nota") }
                    TextButton(onClick = { hidden = true }) { Text("Seguir sin usarla") }
                }
            }
        }
    }
    if (result != null && result !is UsageNoteResult.Ready) {
        TextButton(onClick = { hidden = true }) { Text("Cerrar nota") }
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
