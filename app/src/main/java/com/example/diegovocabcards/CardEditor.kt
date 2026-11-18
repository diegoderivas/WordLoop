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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditor(
    original: Card?, initialEnglish: String, initialTags: List<String>, knownTags: List<String>,
    model: VocabularyModel, onAnotherSense: (Card) -> Unit
) {
    val id = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var english by rememberSaveable { mutableStateOf(original?.english ?: initialEnglish) }
    var spanish by rememberSaveable { mutableStateOf(original?.spanish.orEmpty()) }
    var example by rememberSaveable { mutableStateOf(original?.example.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(ArrayList(original?.tags ?: initialTags)) }
    var pending by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val busy = model.saving || model.saved != null
    val valid = english.isNotBlank() && spanish.isNotBlank() && !busy
    val effectiveTags = (tags + pending.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        .distinctBy { it.lowercase() }
    val draft = Card(id, english.trim(), spanish.trim(), example.trim(), effectiveTags)
    fun addPending() {
        tags = ArrayList(effectiveTags)
        pending = ""
    }
    fun save(next: AfterSave) {
        focus.clearFocus()
        model.save(draft, next)
    }

    OutlinedTextField(english, { english = it }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Palabra o expresión en inglés") }, singleLine = true)
    OutlinedTextField(spanish, { spanish = it }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Traducción al español") }, singleLine = true)
    OutlinedTextField(example, { example = it }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Ejemplo en inglés (opcional)") })
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
