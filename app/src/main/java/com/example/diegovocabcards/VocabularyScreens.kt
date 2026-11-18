package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.util.UUID

@Composable
internal fun CardEditor(card: Card?, suggestedTags: List<String>, model: VocabularyModel) {
    val id = rememberSaveable { card?.id ?: UUID.randomUUID().toString() }
    var english by rememberSaveable { mutableStateOf(card?.english.orEmpty()) }
    var spanish by rememberSaveable { mutableStateOf(card?.spanish.orEmpty()) }
    var example by rememberSaveable { mutableStateOf(card?.example.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf((card?.tags ?: suggestedTags).joinToString(", ")) }
    OutlinedTextField(english, { english = it }, Modifier.fillMaxWidth(),
        label = { Text("Palabra o expresión en inglés") }, singleLine = true)
    OutlinedTextField(spanish, { spanish = it }, Modifier.fillMaxWidth(),
        label = { Text("Traducción al español") }, singleLine = true)
    OutlinedTextField(example, { example = it }, Modifier.fillMaxWidth(),
        label = { Text("Ejemplo en inglés (opcional)") })
    OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(),
        label = { Text("Etiquetas (opcional)") }, supportingText = { Text("Separalas con comas.") })
    if (model.saveError) Text("No se pudo guardar. Podés volver a intentarlo.",
        color = MaterialTheme.colorScheme.error)
    Button(enabled = english.isNotBlank() && spanish.isNotBlank() && !model.saving,
        onClick = { model.save(id, english, spanish, example, tags) }) {
        Text(if (model.saving) "Guardando…" else "Guardar")
    }
}

@Composable
internal fun Library(cards: List<Card>, highlighted: String?, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    if (cards.isEmpty()) {
        Text("Todavía no hay tarjetas en tu biblioteca.")
        Button(onClick = onAdd) { Text("Crear la primera tarjeta") }
        return
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
        label = { Text("Buscar en inglés o español") }, singleLine = true)
    val visible = remember(cards, search, highlighted) {
        val query = search.trim()
        cards.filter { it.english.contains(query, true) || it.spanish.contains(query, true) }
            .sortedByDescending { it.id == highlighted }
    }
    if (visible.isEmpty()) Text("No hay coincidencias.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(visible, key = { it.id }) { card ->
            OutlinedCard(onClick = { onEdit(card.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(card.english, style = MaterialTheme.typography.titleMedium)
                    Text(card.spanish)
                    if (card.example.isNotEmpty()) Text(card.example, style = MaterialTheme.typography.bodyMedium)
                    if (card.tags.isNotEmpty()) Text(card.tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun Practice(cards: List<Card>, onAdd: () -> Unit) {
    var spanishFirst by rememberSaveable { mutableStateOf(true) }
    var session by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val ids = session
    Text("Práctica manual", style = MaterialTheme.typography.titleMedium)
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
        index >= ids.size -> {
            Text("Práctica terminada")
            Button(onClick = { session = null }) { Text("Elegir otra práctica") }
        }
        else -> {
            val card = cards.first { it.id == ids[index] }
            Text("Tarjeta ${index + 1} de ${ids.size}")
            Text(if (spanishFirst) card.spanish else card.english, style = MaterialTheme.typography.headlineSmall)
            if (revealed) {
                Text(if (spanishFirst) card.english else card.spanish, style = MaterialTheme.typography.titleLarge)
                if (card.example.isNotEmpty()) Text(card.example)
                Button(onClick = { revealed = false; index++ }) { Text("Siguiente") }
            } else {
                Button(onClick = { revealed = true }) { Text("Mostrar respuesta") }
            }
        }
    }
}
