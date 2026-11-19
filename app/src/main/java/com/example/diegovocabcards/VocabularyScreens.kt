package com.example.diegovocabcards

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card


@Composable
internal fun Library(
    cards: List<Card>, knownTags: List<String>, highlighted: String?,
    onAdd: () -> Unit, onEdit: (String) -> Unit, onDelete: suspend (String) -> Unit
) {
    var exposedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    Button(onClick = onAdd) { Text("Añadir tarjeta") }
    if (cards.isEmpty()) {
        Text("Todavía no hay tarjetas en tu biblioteca.")
        return
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
        label = { Text("Buscar en inglés o español") }, singleLine = true)
    if (knownTags.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TagMenu(filter ?: "Filtrar por etiqueta", knownTags) { filter = it }
            if (filter != null) TextButton(onClick = { filter = null }) { Text("Quitar filtro") }
        }
    }
    val visible = remember(cards, search, filter, highlighted) {
        val query = search.trim()
        cards.filter { card ->
            (card.english.contains(query, true) || card.spanish.contains(query, true)) &&
                (filter == null || card.tags.any { it.equals(filter, ignoreCase = true) })
        }
            .sortedByDescending { it.id == highlighted }
    }
    if (visible.isEmpty()) Text("No hay coincidencias.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(visible, key = { it.id }) { card ->
            SwipeCardActions(
                card = card,
                onEdit = { onEdit(card.id) },
                revealed = exposedId == card.id,
                onReveal = { exposedId = if (it) card.id else null },
                onDelete = { deleteFailed = false; confirmId = card.id }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.english, style = MaterialTheme.typography.titleMedium)
                    TranslatedText(card.spanish, attributed = GOOGLE_TRANSLATION in card.source)
                    if (GEMINI_TRANSLATION in card.source) Text(GEMINI_TRANSLATION, style = MaterialTheme.typography.labelSmall)
                    if (card.example.isNotEmpty()) Text(card.example, style = MaterialTheme.typography.bodyMedium)
                    if (card.tags.isNotEmpty()) Text(card.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    cards.firstOrNull { it.id == confirmId }?.let { card ->
        val close = { if (!deleting) confirmId = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text("¿Borrar esta acepción?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.english, style = MaterialTheme.typography.titleMedium)
                    Text(card.spanish)
                    Text("Se borrará también su progreso de repaso.")
                    if (deleteFailed) {
                        Text("No se pudo borrar la tarjeta. Podés intentarlo de nuevo.",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        try {
                            onDelete(card.id)
                            confirmId = null
                            exposedId = null
                        } catch (_: SQLiteException) {
                            deleteFailed = true
                        } finally {
                            deleting = false
                        }
                    }
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = close) { Text("Cancelar") } }
        )
    }
}

@Composable
internal fun Practice(cards: List<Card>, cardTools: @Composable (Card) -> Unit = {}, onAdd: () -> Unit) {
    var spanishFirst by rememberSaveable { mutableStateOf(true) }
    var session by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val ids = session
    val card = cards.find { it.id == ids?.getOrNull(index) }
    if (ids != null && card != null) {
        key(card.id, index) {
            StudyPresentation(card, spanishFirst, revealed, "Tarjeta ${index + 1} de ${ids.size}",
                onReveal = { revealed = true }, cardTools = cardTools) {
                Button(onClick = { revealed = false; index++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Siguiente")
                }
            }
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            cards.isEmpty() -> {
                Text("Agregá una tarjeta para empezar a practicar.")
                Button(onClick = onAdd) { Text("Añadir tarjeta") }
            }
            ids == null -> {
                Column(Modifier.selectableGroup()) {
                    for (direction in listOf(true, false)) {
                        val selected = spanishFirst == direction
                        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton,
                            onClick = { spanishFirst = direction }).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(if (direction) "Español → Inglés" else "Inglés → Español")
                        }
                    }
                }
                Button(onClick = {
                    session = ArrayList(cards.map { it.id })
                    index = 0
                    revealed = false
                }) { Text("Iniciar práctica") }
            }
            else -> {
                Text("Práctica terminada")
                Button(onClick = { session = null }) { Text("Elegir otra práctica") }
            }
        }
    }
}
