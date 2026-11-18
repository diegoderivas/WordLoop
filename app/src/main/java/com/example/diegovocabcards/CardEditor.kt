package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import java.util.UUID
import kotlinx.coroutines.ensureActive

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditor(
    original: Card?, initialEnglish: String, initialTags: List<String>, knownTags: List<String>,
    model: VocabularyModel, lookup: suspend (String) -> List<Suggestion> = Dictionary::lookup,
    onAnotherSense: (Card) -> Unit
) {
    val id = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var english by rememberSaveable { mutableStateOf(original?.english ?: initialEnglish) }
    var spanish by rememberSaveable { mutableStateOf(original?.spanish.orEmpty()) }
    var example by rememberSaveable { mutableStateOf(original?.example.orEmpty()) }
    var details by rememberSaveable { mutableStateOf(false) }
    var definition by rememberSaveable { mutableStateOf(original?.definition.orEmpty()) }
    var synonyms by rememberSaveable { mutableStateOf(original?.synonyms.orEmpty()) }
    var senseCategories by rememberSaveable { mutableStateOf(original?.senseCategories.orEmpty()) }
    val localCards by model.cards.collectAsState(initial = emptyList())
    var editedEntryCategories by rememberSaveable(english.trim()) { mutableStateOf<String?>(null) }
    val entryCategories = editedEntryCategories ?: localCards.firstOrNull { it.english == english.trim() }?.entryCategories.orEmpty()
    var changedFields by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    fun markChanged(field: String) {
        if (field !in changedFields) changedFields = ArrayList(changedFields + field)
    }
    fun wouldReplace(field: String, current: String, suggested: String): Boolean =
        suggested.isNotBlank() && current != suggested && (current.isNotBlank() || field in changedFields)
    var source by rememberSaveable { mutableStateOf(original?.source.orEmpty()) }
    val currentQuery = english.trim()
    var suggestions by remember(currentQuery) { mutableStateOf(emptyList<Suggestion>()) }
    var queryState by remember(currentQuery) { mutableStateOf("") }
    var proposed by remember(currentQuery) { mutableStateOf<Suggestion?>(null) }
    LaunchedEffect(currentQuery) {
        if (currentQuery.length >= 2) {
            kotlinx.coroutines.delay(600)
            queryState = "Buscando significados…"
            try {
                val result = lookup(currentQuery)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                suggestions = result
                queryState = if (result.isEmpty()) "Sin resultados. Podés completar la tarjeta a mano." else ""
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                queryState = "No se pudo consultar. Podés completar la tarjeta a mano."
            }
        }
    }
    fun applySuggestion(value: Suggestion) {
        definition = value.definition
        val replaced = mutableSetOf("definition")
        if (value.category.isNotBlank()) {
            senseCategories = value.category
            replaced += "categories"
        }
        if (value.synonyms.isNotBlank()) {
            synonyms = value.synonyms
            replaced += "synonyms"
        }
        if (value.example.isNotBlank()) {
            example = value.example
            replaced += "example"
        }
        changedFields = ArrayList(changedFields - replaced)
        source = value.source + "\nContenido seleccionado y editable por el usuario."
        details = true
        proposed = null
    }
    var tags by rememberSaveable { mutableStateOf(ArrayList(original?.tags ?: initialTags)) }
    var pending by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val busy = model.saving || model.saved != null
    val valid = english.isNotBlank() && spanish.isNotBlank() && !busy
    val effectiveTags = (tags + pending.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        .distinctBy { it.lowercase() }
    val draft = Card(id, english.trim(), spanish.trim(), example.trim(), effectiveTags, definition, synonyms, senseCategories, entryCategories, source)
    fun addPending() {
        tags = ArrayList(effectiveTags)
        pending = ""
    }
    fun save(next: AfterSave) {
        focus.clearFocus()
        model.save(draft, next, editedEntryCategories)
    }

    OutlinedTextField(english, { english = it }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Palabra o expresión en inglés") }, singleLine = true)
    OutlinedTextField(spanish, { spanish = it }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Traducción al español") }, singleLine = true)
    OutlinedTextField(example, { example = it; markChanged("example") }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Ejemplo en inglés (opcional)") })
    TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar detalles" else "Detalles opcionales") }
    if (details) {
        OutlinedTextField(definition, { definition = it; markChanged("definition") }, Modifier.fillMaxWidth(), label = { Text("Definición en inglés") })
        OutlinedTextField(synonyms, { synonyms = it; markChanged("synonyms") }, Modifier.fillMaxWidth(), label = { Text("Sinónimos (separados por comas)") })
        OutlinedTextField(senseCategories, { senseCategories = it; markChanged("categories") }, Modifier.fillMaxWidth(), label = { Text("Categorías de esta acepción (separadas por comas)") })
        OutlinedTextField(entryCategories, { editedEntryCategories = it }, Modifier.fillMaxWidth(), label = { Text("Categorías de la expresión (compartidas)") })
        Text("Podés escribir varios ejemplos, uno por línea.")
    }
    Text("Etiquetas", style = MaterialTheme.typography.titleMedium)
    if (tags.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { label ->
                InputChip(selected = true, enabled = !busy,
                    onClick = { tags = ArrayList(tags - label) },
                    label = { Text(label) }, trailingIcon = { Text("×") })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(pending, { pending = it }, Modifier.weight(1f), enabled = !busy,
            label = { Text("Nueva etiqueta") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addPending(); focus.clearFocus() }))
        TextButton(onClick = { addPending() }, enabled = pending.isNotBlank() && !busy) { Text("Añadir") }
    }
    val available = knownTags.filter { label -> tags.none { it.equals(label, ignoreCase = true) } }
    if (available.isNotEmpty()) {
        TagMenu("Elegir etiqueta existente", available, enabled = !busy) {
            tags = ArrayList(tags + it)
        }
    }
    if (model.failedId == id) Text("No se pudo guardar. Podés volver a intentarlo.",
        color = MaterialTheme.colorScheme.error)
    Button(onClick = { save(AfterSave.Library) }, enabled = valid) { Text("Guardar") }
    if (original == null) {
        OutlinedButton(onClick = { save(AfterSave.AnotherCard) }, enabled = valid) {
            Text("Guardar y añadir otra")
        }
    } else {
        OutlinedButton(onClick = {
            if (draft == original) onAnotherSense(original) else confirm = true
        }, enabled = !busy) { Text("Añadir otra acepción") }
    }
    if (source.isNotEmpty()) Text(source, style = MaterialTheme.typography.bodySmall)
    if (queryState.isNotEmpty()) Text(queryState)
    suggestions.forEach { suggestion ->
        OutlinedCard(onClick = {
            val conflict = wouldReplace("definition", definition, suggestion.definition) ||
                wouldReplace("categories", senseCategories, suggestion.category) ||
                wouldReplace("synonyms", synonyms, suggestion.synonyms) ||
                wouldReplace("example", example, suggestion.example)
            if (conflict) proposed = suggestion else applySuggestion(suggestion)
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(suggestion.category)
                Text(suggestion.definition)
                if (suggestion.example.isNotEmpty()) Text(suggestion.example)
                Text(suggestion.source, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    proposed?.let { suggestion ->
        AlertDialog(onDismissRequest = { proposed = null },
            title = { Text("Reemplazar campos con contenido") },
            text = { Text("Esta acepción propone otra definición, categorías y, si existen, sinónimos o ejemplo. La traducción en español se conserva.") },
            confirmButton = { TextButton(onClick = { applySuggestion(suggestion) }) { Text("Usar sugerencia") } },
            dismissButton = { TextButton(onClick = { proposed = null }) { Text("Conservar mis campos") } })
    }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("Hay cambios en esta tarjeta") },
            text = { Text("Guardá los cambios antes de abrir la siguiente acepción.") },
            confirmButton = {
                TextButton(enabled = valid, onClick = { confirm = false; save(AfterSave.AnotherSense) }) {
                    Text("Guardar y continuar")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Volver a editar") } })
    }
}

@Composable
internal fun TagMenu(title: String, choices: List<String>, enabled: Boolean = true, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(title) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { tag ->
                DropdownMenuItem(text = { Text(tag) }, onClick = { expanded = false; onChoose(tag) })
            }
        }
    }
}
