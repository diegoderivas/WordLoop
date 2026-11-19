package com.example.diegovocabcards

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import java.util.UUID
import kotlinx.coroutines.ensureActive

internal enum class MeaningField { Spanish, Definition, Categories, Example, Synonyms }

internal fun fieldsFrom(suggestion: Suggestion): Map<MeaningField, String> = mapOf(
    MeaningField.Spanish to suggestion.spanish.firstOrNull().orEmpty(),
    MeaningField.Definition to suggestion.definition,
    MeaningField.Categories to suggestion.category,
    MeaningField.Example to suggestion.examples.firstOrNull().orEmpty(),
    MeaningField.Synonyms to suggestion.synonymOptions.firstOrNull().orEmpty()
)

internal fun mergeMeaningFields(
    current: Map<MeaningField, String>,
    incoming: Map<MeaningField, String>,
    automatic: Set<MeaningField>,
    manuallyEdited: Set<MeaningField>,
    replaceManual: Boolean
): Map<MeaningField, String> = current.mapValues { (field, existing) ->
    val suggested = incoming.getValue(field)
    when {
        field in automatic -> suggested
        suggested.isBlank() -> existing
        replaceManual || (existing.isBlank() && field !in manuallyEdited) -> suggested
        else -> existing
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditor(
    original: Card?, initialEnglish: String, initialTags: List<String>, knownTags: List<String>,
    model: VocabularyModel, lookup: suspend (String) -> List<Suggestion> = Dictionary::lookup,
    translate: suspend (String, Suggestion) -> String? = LocalSenseTranslation::suggest,
    onAnotherSense: (Card) -> Unit
) {
    val id = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var english by rememberSaveable { mutableStateOf(original?.english ?: initialEnglish) }
    var spanish by rememberSaveable { mutableStateOf(original?.spanish.orEmpty()) }
    var example by rememberSaveable { mutableStateOf(original?.example.orEmpty()) }
    var details by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editingCategory by rememberSaveable { mutableStateOf(false) }
    var definition by rememberSaveable { mutableStateOf(original?.definition.orEmpty()) }
    var synonyms by rememberSaveable { mutableStateOf(original?.synonyms.orEmpty()) }
    var senseCategories by rememberSaveable { mutableStateOf(original?.senseCategories.orEmpty()) }
    val localCards by model.cards.collectAsState(initial = emptyList())
    var editedEntryCategories by rememberSaveable(english.trim()) { mutableStateOf<String?>(null) }
    val entryCategories = editedEntryCategories ?: localCards.firstOrNull { it.english == english.trim() }?.entryCategories.orEmpty()
    var manualFields by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var automaticFields by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var retainedSource by rememberSaveable { mutableStateOf(original?.source.orEmpty()) }
    var activeSource by rememberSaveable { mutableStateOf("") }
    var translationSource by rememberSaveable { mutableStateOf("") }
    val currentValues = mapOf(
        MeaningField.Spanish to spanish, MeaningField.Definition to definition,
        MeaningField.Categories to senseCategories, MeaningField.Example to example,
        MeaningField.Synonyms to synonyms
    )
    val automatic = MeaningField.entries.filter { it.name in automaticFields }.toSet()
    val manual = MeaningField.entries.filter { it.name in manualFields }.toSet()
    fun setValue(field: MeaningField, value: String) {
        when (field) {
            MeaningField.Spanish -> spanish = value
            MeaningField.Definition -> definition = value
            MeaningField.Categories -> senseCategories = value
            MeaningField.Example -> example = value
            MeaningField.Synonyms -> synonyms = value
        }
    }
    fun editValue(field: MeaningField, value: String) {
        if (field in automatic && value.isNotBlank()) {
            retainedSource = combineSources(retainedSource, activeSource)
        }
        if (field == MeaningField.Spanish && translationSource.isNotEmpty()) {
            if (value.isNotBlank()) retainedSource = combineSources(retainedSource, translationSource)
            translationSource = ""
        }
        manualFields = ArrayList((manualFields + field.name).distinct())
        automaticFields = ArrayList(automaticFields - field.name)
        setValue(field, value)
    }

    val currentQuery = english.trim()
    var suggestions by remember(currentQuery) { mutableStateOf(emptyList<Suggestion>()) }
    var queryState by remember(currentQuery) { mutableStateOf("") }
    var proposed by remember(currentQuery) { mutableStateOf<Suggestion?>(null) }
    var selectedKey by rememberSaveable(currentQuery) { mutableStateOf<String?>(null) }
    var more by rememberSaveable(currentQuery) { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var choosingExample by rememberSaveable(currentQuery) { mutableStateOf(false) }
    var showAllSynonyms by rememberSaveable(currentQuery, selectedKey) { mutableStateOf(false) }
    val selected = suggestions.firstOrNull { it.selectionKey() == selectedKey }
    LaunchedEffect(currentQuery) {
        if (currentQuery.length >= 2) {
            kotlinx.coroutines.delay(600)
            queryState = "Buscando significados…"
            try {
                val result = distinctMeanings(lookup(currentQuery))
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                suggestions = result
                queryState = if (result.isEmpty()) "Sin resultados. Podés completar la tarjeta a mano." else ""
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                queryState = "No se pudo consultar. Podés completar la tarjeta a mano."
            }
        }
    }
    fun applySuggestion(value: Suggestion, replaceManual: Boolean = false) {
        val incoming = fieldsFrom(value)
        val merged = mergeMeaningFields(currentValues, incoming, automatic, manual, replaceManual)
        val applied = merged.keys.filter { field ->
            incoming.getValue(field).isNotBlank() &&
                (field in automatic || currentValues.getValue(field) != merged.getValue(field))
        }
        merged.forEach(::setValue)
        automaticFields = ArrayList(applied.map { it.name })
        manualFields = ArrayList(manualFields - automaticFields.toSet())
        activeSource = value.source.takeIf { applied.isNotEmpty() }.orEmpty()
        translationSource = ""
        selectedKey = value.selectionKey()
        choosingExample = value.examples.distinct().size > 1 &&
            (MeaningField.Example in automatic || MeaningField.Example in applied)
        showAllSynonyms = false
        proposed = null
    }
    fun selectExample(value: String) {
        setValue(MeaningField.Example, value)
        automaticFields = ArrayList((automaticFields + MeaningField.Example.name).distinct())
        manualFields = ArrayList(manualFields - MeaningField.Example.name)
        if (value.isNotBlank()) activeSource = selected?.source.orEmpty()
        choosingExample = false
    }
    fun toggleSynonym(value: String) {
        val field = MeaningField.Synonyms
        val terms = commaSeparatedValues(synonyms)
        val updated = (if (value in terms) terms - value else terms + value).joinToString(", ")
        if (field in automatic || (synonyms.isBlank() && field !in manual)) {
            setValue(field, updated)
            automaticFields = ArrayList((automaticFields + field.name).distinct())
            activeSource = selected?.source.orEmpty()
        } else {
            editValue(field, updated)
            if (value !in terms) retainedSource = combineSources(retainedSource, selected?.source.orEmpty())
        }
    }
    var tags by rememberSaveable { mutableStateOf(ArrayList(original?.tags ?: initialTags)) }
    var pending by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val busy = model.saving || model.saved != null
    val valid = english.isNotBlank() && spanish.isNotBlank() && !busy
    val effectiveTags = (tags + pending.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        .distinctBy { it.lowercase() }
    val draft = Card(id, english.trim(), spanish.trim(), example.trim(), effectiveTags, definition, synonyms, senseCategories, entryCategories, storedAttribution(combineSources(retainedSource, activeSource, translationSource)))
    fun addPending() {
        tags = ArrayList(effectiveTags)
        pending = ""
    }
    fun save(next: AfterSave) {
        focus.clearFocus()
        model.save(draft, next, editedEntryCategories)
    }

    OutlinedTextField(english, { value ->
        if (value.trim() != currentQuery) {
            automatic.forEach { setValue(it, "") }
            automaticFields = arrayListOf()
            activeSource = ""
            translationSource = ""
        }
        english = value
    }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Palabra o expresión en inglés") }, singleLine = true)
    if (suggestions.isNotEmpty() || queryState.isNotEmpty()) {
        Text("Significados", style = MaterialTheme.typography.titleMedium)
        if (queryState.isNotEmpty()) Text(queryState, style = MaterialTheme.typography.bodyMedium)
        fun choose(suggestion: Suggestion) {
            more = false
            val incoming = fieldsFrom(suggestion)
            val preserving = mergeMeaningFields(currentValues, incoming, automatic, manual, false)
            val replacing = mergeMeaningFields(currentValues, incoming, automatic, manual, true)
            if (preserving != replacing) proposed = suggestion else {
                applySuggestion(suggestion)
                focus.clearFocus()
            }
        }
        val groups = remember(suggestions, currentQuery) { rankedMeaningGroups(suggestions, currentQuery) }
        val top = groups.take(3)
        val visible = if (selected != null && top.none { selected == it.representative || selected in it.variants }) {
            top.take(2) + MeaningGroup(selected, emptyList())
        } else top
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.forEach { group ->
                val suggestion = selected?.takeIf { it in group.variants } ?: group.representative
                MeaningOption(suggestion, suggestion == selected, busy, variantCount = group.variants.size) {
                    choose(suggestion)
                }
            }
        }
        if (suggestions.size > top.size) {
            TextButton(onClick = { focus.clearFocus(); more = true }) { Text("Ver más significados") }
        }
        if (more) {
            AlertDialog(onDismissRequest = { more = false },
                title = { Text("Significados") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.forEach { group ->
                            item {
                                val sense = group.representative
                                MeaningOption(sense, sense == selected, busy, expanded = true) { choose(sense) }
                            }
                            items(group.variants) { variant ->
                                Box(Modifier.padding(start = 12.dp)) {
                                    MeaningOption(variant, variant == selected, busy, expanded = true, variant = true) {
                                        choose(variant)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { more = false }) { Text("Cerrar") } })
        }
    }
    val sources = combineSources(retainedSource, activeSource, translationSource,
        *suggestions.map { it.source }.toTypedArray())
    if (sources.isNotEmpty()) TextButton(onClick = { showSources = true }) { Text("Fuente y licencia") }
    if (showSources && sources.isNotEmpty()) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(onDismissRequest = { showSources = false }, title = { Text("Fuente y licencia") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.lineSequence().filter { it.isNotBlank() }.distinct().forEach { line ->
                        if (line.startsWith("https://")) {
                            TextButton(onClick = { runCatching { uriHandler.openUri(line) } }) {
                                Text(when {
                                    "creativecommons.org" in line -> "Ver licencia"
                                    "developers.google.com/ml-kit" in line -> "Acerca de la traducción"
                                    else -> "Ver entrada original"
                                })
                            }
                        } else Text(line)
                    }
                    if (GOOGLE_TRANSLATION in sources) {
                        Text("Esta app puede contener traducciones de Google. Google excluye toda garantía " +
                            "expresa o implícita sobre ellas, incluidas las de precisión, fiabilidad, comerciabilidad, " +
                            "idoneidad para un fin particular y ausencia de infracción.")
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSources = false }) { Text("Cerrar") } })
    }
    OutlinedTextField(spanish, { editValue(MeaningField.Spanish, it) }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Traducción al español") }, singleLine = true)
    if (spanish.isNotBlank() && (translationSource.isNotEmpty() || GOOGLE_TRANSLATION in retainedSource)) {
        TranslationAttribution()
    }
    if (selected != null && selected.spanish.isEmpty() && spanish.isBlank() && senseTranslationInput(currentQuery, selected) != null) {
        key(currentQuery, selectedKey) {
            SenseTranslationOffer(currentQuery, selected, enabled = !busy,
                translate = { term, sense ->
                    val result = translate(term, sense)
                    result?.takeUnless { candidate ->
                        suggestions.any { other ->
                            other != sense && other.spanish.any { senseText(it) == senseText(candidate) }
                        }
                    }
                },
                onUse = { candidate ->
                    spanish = candidate
                    automaticFields = ArrayList((automaticFields + MeaningField.Spanish.name).distinct())
                    manualFields = ArrayList(manualFields - MeaningField.Spanish.name)
                    translationSource = GOOGLE_TRANSLATION
                })
        }
    }
    val examples = selected?.examples.orEmpty().filter(String::isNotBlank).distinct()
    if (examples.isNotEmpty()) {
        SupportingSenseExample(examples, example, enabled = !busy,
            onChoose = { choosingExample = true },
            onUse = ::selectExample,
            onRemove = {
                if (MeaningField.Example in automatic) selectExample("")
                else editValue(MeaningField.Example, "")
            })
        if (choosingExample && examples.size > 1) {
            ExamplePicker(examples, example, onClose = { choosingExample = false }, onSelect = ::selectExample)
        }
    }
    OutlinedTextField(example, { editValue(MeaningField.Example, it) }, Modifier.fillMaxWidth(), enabled = !busy,
        label = { Text("Ejemplo en inglés (opcional)") })

    if (selected != null || senseCategories.isNotBlank() || details) {
        Text("Categoría gramatical", style = MaterialTheme.typography.titleSmall)
        if (editingCategory) {
            OutlinedTextField(senseCategories, { editValue(MeaningField.Categories, it) }, Modifier.fillMaxWidth(),
                enabled = !busy, label = { Text("Categorías de la acepción") })
            TextButton(onClick = { editingCategory = false }) { Text("Listo") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = commaSeparatedValues(senseCategories).map { categoryLabel(it.lowercase()) }
                Text(labels.joinToString(" · ").ifBlank { "Sin categoría" }, Modifier.weight(1f))
                TextButton(onClick = { editingCategory = true }, enabled = !busy) { Text("Editar categoría") }
            }
        }
    }

    val synonymOptions = selected?.synonymOptions.orEmpty().filter(String::isNotBlank).distinct()
    if (synonymOptions.isNotEmpty()) {
        Text("Sinónimos en inglés", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val terms = commaSeparatedValues(synonyms)
            val visible = if (showAllSynonyms) synonymOptions else synonymOptions.take(6)
            visible.forEach { term ->
                FilterChip(selected = term in terms, enabled = !busy, onClick = { toggleSynonym(term) },
                    label = { Text(term) })
            }
        }
        if (synonymOptions.size > 6) {
            TextButton(onClick = { showAllSynonyms = !showAllSynonyms }) {
                Text(if (showAllSynonyms) "Ver menos sinónimos" else "Ver más sinónimos")
            }
        }
    }
    TextButton(onClick = { details = !details }) { Text(if (details) "Ocultar detalles" else "Detalles opcionales") }
    if (details) {
        OutlinedTextField(definition, { editValue(MeaningField.Definition, it) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("Definición en inglés") })
        OutlinedTextField(synonyms, { editValue(MeaningField.Synonyms, it) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("Sinónimos (separados por comas)") })
        Text("Podés escribir varios ejemplos, uno por línea.")
        TextButton(onClick = { advanced = !advanced }) {
            Text(if (advanced) "Ocultar detalles avanzados" else "Detalles avanzados")
        }
        if (advanced) {
            OutlinedTextField(entryCategories, { editedEntryCategories = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("Categorías de la expresión (compartidas)") })
        }
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
    proposed?.let { suggestion ->
        AlertDialog(onDismissRequest = { proposed = null },
            title = { Text("Esta acepción cambia campos editados") },
            text = { Text("Podés aplicar el nuevo significado conservando tus campos o reemplazarlos cuando hay un valor sugerido.") },
            confirmButton = { TextButton(onClick = {
                applySuggestion(suggestion, replaceManual = true)
                focus.clearFocus()
            }) { Text("Usar sugerencia") } },
            dismissButton = { TextButton(onClick = {
                applySuggestion(suggestion)
                focus.clearFocus()
            }) { Text("Conservar mis campos") } })
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
private fun MeaningOption(
    suggestion: Suggestion,
    chosen: Boolean,
    busy: Boolean,
    expanded: Boolean = false,
    variant: Boolean = false,
    variantCount: Int = 0,
    onClick: () -> Unit
) {
    OutlinedCard(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = if (chosen)
            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOf(if (variant) "Variante" else "", categoryLabel(suggestion.category), suggestion.usage)
                .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            if (variantCount > 0) {
                Text(if (variantCount == 1) "1 variante en el detalle" else "$variantCount variantes en el detalle",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (suggestion.spanish.isNotEmpty()) {
                val equivalents = if (expanded) suggestion.spanish else suggestion.spanish.take(2)
                Text(equivalents.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
            }
            Text(suggestion.definition, maxLines = if (expanded || chosen) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun commaSeparatedValues(value: String): List<String> =
    value.split(',').map(String::trim).filter(String::isNotEmpty)

private const val EDIT_NOTICE = "Contenido seleccionado y editable por el usuario."

private fun combineSources(vararg sources: String): String = sources.asSequence()
    .flatMap { it.split("\n\n").asSequence() }
    .map { it.removeSuffix(EDIT_NOTICE).trim() }
    .filter { it.isNotBlank() }
    .distinct()
    .joinToString("\n\n")

private fun storedAttribution(source: String): String =
    if (source.isBlank()) "" else "$source\n$EDIT_NOTICE"

private fun Suggestion.selectionKey(): String =
    listOf(definition, category, usage, spanish.joinToString("|"), source).joinToString("\n")

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
