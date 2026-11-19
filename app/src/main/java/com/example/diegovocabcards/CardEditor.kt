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
import kotlinx.coroutines.sync.withLock

internal enum class MeaningField { Spanish, Definition, Categories, Example, Synonyms }

internal fun fieldsFrom(suggestion: Suggestion): Map<MeaningField, String> = mapOf(
    MeaningField.Spanish to suggestion.spanish.firstOrNull().orEmpty(),
    MeaningField.Definition to suggestion.definition,
    MeaningField.Categories to suggestion.category,
    MeaningField.Example to suggestion.examples.firstOrNull().orEmpty(),
    MeaningField.Synonyms to ""
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
    nanoOptions: suspend (String, Suggestion, SenseRequest) -> List<String> = ::nanoSenseOptions,
    usageNotes: UsageNoteGenerator = NanoUsageNotes,
    fromStudy: Boolean = false,
    onOpenExisting: (String) -> Unit = {},
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
    var exampleSource by rememberSaveable { mutableStateOf("") }
    var synonymSource by rememberSaveable { mutableStateOf("") }
    var translationSource by rememberSaveable { mutableStateOf("") }
    var usageNote by rememberSaveable { mutableStateOf(original?.usageNote.orEmpty()) }
    var generatedExamples by rememberSaveable { mutableStateOf(original?.generatedExamples.orEmpty()) }
    var ipa by rememberSaveable { mutableStateOf(original?.ipa.orEmpty()) }
    var ipaSource by rememberSaveable { mutableStateOf(original?.ipaSource.orEmpty()) }
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
        if (field == MeaningField.Example && exampleSource.isNotBlank()) {
            if (value.isNotBlank()) retainedSource = combineSources(retainedSource, exampleSource)
            exampleSource = ""
        }
        if (field == MeaningField.Synonyms && synonymSource.isNotBlank()) {
            if (value.isNotBlank()) retainedSource = combineSources(retainedSource, synonymSource)
            synonymSource = ""
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
    val pronunciationPlayer = LocalPronunciationPlayer.current
    DisposableEffect(pronunciationPlayer, currentQuery) { onDispose { pronunciationPlayer?.stop() } }
    var suggestions by remember(currentQuery) { mutableStateOf(emptyList<Suggestion>()) }
    var queryState by remember(currentQuery) { mutableStateOf("") }
    var proposed by remember(currentQuery) { mutableStateOf<Suggestion?>(null) }
    var selectedKey by rememberSaveable(currentQuery) { mutableStateOf<String?>(null) }
    var more by rememberSaveable(currentQuery) { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var choosingExample by rememberSaveable(currentQuery) { mutableStateOf(false) }
    var showAllSynonyms by rememberSaveable(currentQuery, selectedKey) { mutableStateOf(false) }
    val selected = suggestions.firstOrNull { it.selectionKey() == selectedKey }
    val scope = key(id, currentQuery) { rememberCoroutineScope() }
    val requests = remember(scope) { EditorRequests(scope) }
    // A saved card also supplies a context for manually requested translations.
    val providerSense = selected ?: original?.takeIf { it.english == currentQuery }?.let {
        Suggestion(it.definition.ifBlank { it.spanish }, it.senseCategories, it.source,
            examples = it.example.lines().filter(String::isNotBlank))
    }
    val providers = providerSense?.let(requests::forSense)
    fun requestGoogle(retry: Boolean = false) {
        val sense = providerSense ?: return
        providers?.google?.start(retry, emptyList()) {
            listOfNotNull(translate(currentQuery, sense)?.takeIf { candidate ->
                candidate.isNotBlank() && suggestions.none { other ->
                    other != sense && other.spanish.any { senseText(it) == senseText(candidate) }
                }
            })
        }
    }
    fun requestNano(task: SenseRequest, retry: Boolean = false) {
        val sense = providerSense ?: return
        val service = providers ?: return
        val request = if (task == SenseRequest.Translation) service.translation else service.synonyms
        request.start(retry, emptyList()) {
            requests.nano.withLock { nanoOptions(currentQuery, sense, task) }
                .map(String::trim).filter(String::isNotBlank)
        }
    }
    fun requestGuide(retry: Boolean = false, download: Boolean = false) {
        val sense = selected ?: return
        providers?.guide?.start(retry, UsageNoteResult.Error) {
            requests.nano.withLock { usageNotes.generate(usageNotePrompt(currentQuery, sense), download) {} }
        }
    }
    fun applyTranslation(value: String, source: String, explicitly: Boolean) {
        retainedSource = combineSources(retainedSource).split("\n\n")
            .filterNot { it == GOOGLE_TRANSLATION || it == GEMINI_TRANSLATION }.joinToString("\n\n")
        spanish = value
        if (explicitly) {
            manualFields = ArrayList((manualFields + MeaningField.Spanish.name).distinct())
            automaticFields = ArrayList(automaticFields - MeaningField.Spanish.name)
        } else {
            automaticFields = ArrayList((automaticFields + MeaningField.Spanish.name).distinct())
        }
        translationSource = source
    }
    fun canFill(field: MeaningField) =
        selected != null && english.trim() == currentQuery && selectedKey == selected.selectionKey() &&
            field.name !in manualFields
    LaunchedEffect(selected) {
        if (selected != null) {
            if (selected.spanish.isEmpty()) requestGoogle()
            requestGuide()
            requestNano(SenseRequest.Synonyms)
        }
    }
    val googleResult = providers?.google?.result
    val geminiResult = providers?.translation?.result
    LaunchedEffect(selected, googleResult, geminiResult) {
        if (selected != null && selected.spanish.isEmpty() && googleResult != null && canFill(MeaningField.Spanish)) {
            val google = googleResult.firstOrNull()
            val candidate = google ?: geminiResult?.firstOrNull()
            if (candidate != null && spanish.isBlank()) {
                applyTranslation(candidate, if (google != null) GOOGLE_TRANSLATION else GEMINI_TRANSLATION, explicitly = false)
            } else if (google == null) requestNano(SenseRequest.Translation)
        }
    }
    val suggestedGuide = (providers?.guide?.result as? UsageNoteResult.Ready)?.note
    LaunchedEffect(selected, suggestedGuide) {
        if (selected != null && selected.examples.none(String::isNotBlank) &&
            canFill(MeaningField.Example) && example.isBlank()) {
            suggestedGuide?.examples?.firstOrNull(String::isNotBlank)?.let {
                example = it
                exampleSource = GENERATED_WITH_NANO
                automaticFields = ArrayList((automaticFields + MeaningField.Example.name).distinct())
            }
        }
    }
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
        if (MeaningField.Spanish in automatic || MeaningField.Spanish in applied) translationSource = ""
        if (MeaningField.Example in automatic || MeaningField.Example in applied) exampleSource = ""
        if (MeaningField.Synonyms in automatic || MeaningField.Synonyms in applied) synonymSource = ""
        ipa = value.pronunciation?.ipa.orEmpty()
        ipaSource = value.pronunciation?.source.orEmpty()
        selectedKey = value.selectionKey()
        choosingExample = value.examples.distinct().size > 1 &&
            (MeaningField.Example in automatic || MeaningField.Example in applied)
        showAllSynonyms = false
        proposed = null
    }
    fun selectExample(value: String) {
        example = value
        manualFields = ArrayList((manualFields + MeaningField.Example.name).distinct())
        automaticFields = ArrayList(automaticFields - MeaningField.Example.name)
        exampleSource = when {
            value.isBlank() -> ""
            value in selected?.examples.orEmpty() -> selected?.source.orEmpty()
            else -> GENERATED_WITH_NANO
        }
        choosingExample = false
    }
    fun toggleSynonym(value: String, source: String) {
        val terms = commaSeparatedValues(synonyms)
        val existing = terms.firstOrNull { senseText(it) == senseText(value) }
        val updated = if (existing != null) terms - existing else terms + value
        editValue(MeaningField.Synonyms, updated.joinToString(", "))
        if (existing == null) synonymSource = combineSources(synonymSource, source)
    }
    var tags by rememberSaveable { mutableStateOf(ArrayList(original?.tags ?: initialTags)) }
    var pending by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val busy = model.saving || model.saved != null
    val valid = english.isNotBlank() && spanish.isNotBlank() && !busy
    val effectiveTags = (tags + pending.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        .distinctBy { it.lowercase() }
    val draft = Card(id, english.trim(), spanish.trim(), example.trim(), effectiveTags, definition,
        synonyms, senseCategories, entryCategories,
        storedAttribution(combineSources(retainedSource, activeSource, translationSource, synonymSource, exampleSource)),
        usageNote, generatedExamples, ipa, ipaSource)
    fun addPending() {
        tags = ArrayList(effectiveTags)
        pending = ""
    }
    fun save(next: AfterSave) {
        focus.clearFocus()
        model.save(draft, next, editedEntryCategories)
    }

    EnglishPronunciation(currentQuery, ipa) {
        OutlinedTextField(english, { value ->
            if (value.trim() != currentQuery) {
                automatic.forEach { setValue(it, "") }
                automaticFields = arrayListOf()
                activeSource = ""
                if (MeaningField.Example in automatic) exampleSource = ""
                if (MeaningField.Synonyms in automatic) synonymSource = ""
                if (MeaningField.Spanish in automatic) translationSource = ""
                ipa = ""
                ipaSource = ""
            }
            english = value
        }, Modifier.weight(1f), enabled = !busy,
            label = { Text("Palabra o expresión en inglés") }, singleLine = true)
    }
    if (suggestions.isNotEmpty() || queryState.isNotEmpty()) {
        Text("Significados", style = MaterialTheme.typography.titleMedium)
        if (queryState.isNotEmpty()) Text(queryState, style = MaterialTheme.typography.bodyMedium)
        fun choose(suggestion: Suggestion) {
            more = false
            val incoming = fieldsFrom(suggestion)
            val preserving = mergeMeaningFields(currentValues, incoming, automatic, manual, false)
            val replacing = mergeMeaningFields(currentValues, incoming, automatic, manual, true)
            if (preserving != replacing) proposed = suggestion else if (suggestion != selected) {
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
    val sources = combineSources(retainedSource, activeSource, translationSource, synonymSource, exampleSource, ipaSource,
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(spanish, { editValue(MeaningField.Spanish, it) }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("Traducción al español") }, singleLine = true)
        if (spanish.isNotBlank() && (translationSource == GOOGLE_TRANSLATION || GOOGLE_TRANSLATION in retainedSource)) {
            TranslationAttribution()
        } else if (spanish.isNotBlank() && (translationSource == GEMINI_TRANSLATION || GEMINI_TRANSLATION in retainedSource)) {
            Text(GEMINI_TRANSLATION, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (selected != null && original == null && selected.definition.isNotBlank()) {
        val duplicate = localCards.firstOrNull {
            it.english.equals(currentQuery, true) && senseText(it.definition) == senseText(selected.definition) &&
                it.senseCategories.equals(selected.category, true) &&
                selected.spanish.any { term -> senseText(it.spanish) == senseText(term) }
        }
        if (duplicate != null) {
            Text("Esta acepción ya está en tu biblioteca.")
            TextButton(onClick = { onOpenExisting(duplicate.id) }, enabled = !busy) { Text("Abrir tarjeta existente") }
        }
    }
    if (providerSense != null && providers != null) {
        selected?.spanish?.distinct()?.forEach { value ->
            TextButton(onClick = { applyTranslation(value, providerSense.source, explicitly = true) }, enabled = !busy) {
                Text("Usar del diccionario · $value")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { focus.clearFocus(); requestGoogle(retry = googleResult?.isEmpty() == true) },
                enabled = !busy && !providers.google.loading) { Text("Traducir con Google") }
            TextButton(onClick = { focus.clearFocus(); requestNano(SenseRequest.Translation, retry = geminiResult?.isEmpty() == true) },
                enabled = !busy && !providers.translation.loading) { Text("Traducir con Gemini") }
        }
        googleResult?.firstOrNull()?.let { value ->
            TranslatedText(value, attributed = true)
            TextButton(onClick = { applyTranslation(value, GOOGLE_TRANSLATION, explicitly = true) }, enabled = !busy) {
                Text("Usar traducción de Google")
            }
        }
        geminiResult?.firstOrNull()?.let { value ->
            Text(value)
            TextButton(onClick = { applyTranslation(value, GEMINI_TRANSLATION, explicitly = true) }, enabled = !busy) {
                Text("Usar traducción de Gemini")
            }
        }
        if (providers.google.loading || providers.translation.loading) Text("Buscando traducción…")
        else if (googleResult?.isEmpty() == true || geminiResult?.isEmpty() == true) {
            Text("Alguna consulta quedó sin propuesta. Podés reintentar o escribir la traducción.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    val dictionaryExamples = selected?.examples.orEmpty().filter(String::isNotBlank)
    val examples = dictionaryExamples.ifEmpty { suggestedGuide?.examples.orEmpty() }.distinct()
    if (examples.isNotEmpty()) {
        SupportingSenseExample(examples, example, enabled = !busy,
            onChoose = { choosingExample = true },
            onUse = ::selectExample,
            onRemove = { selectExample("") })
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

    if (selected != null && providers != null) {
        key(currentQuery, selectedKey) {
            UsageNoteOffer(providers.guide, { download -> requestGuide(retry = true, download = download) },
                enabled = !busy, hasNote = usageNote.isNotBlank(),
                onSaveNote = { usageNote = it.forStorage() },
                onAddExample = { generated ->
                    if ((example.lines() + generatedExamples.lines()).none { it.trim().equals(generated, true) }) {
                        generatedExamples = (generatedExamples.lines().filter(String::isNotBlank) + generated)
                            .joinToString("\n")
                    }
                })
        }
    }
    SavedUsageContent(usageNote, generatedExamples,
        onRemoveNote = if (busy) null else ({ usageNote = "" }),
        onRemoveExample = if (busy) null else {
            { generated ->
                generatedExamples = generatedExamples.lines().filterNot { it == generated }.joinToString("\n").trim()
            }
        })

    val dictionarySynonyms = selected?.synonymOptions.orEmpty().filter(String::isNotBlank)
    val synonymOptions = (dictionarySynonyms + providers?.synonyms?.result.orEmpty()).distinctBy(::senseText)
    if (synonymOptions.isNotEmpty()) {
        Text("Sinónimos en inglés", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val terms = commaSeparatedValues(synonyms).map(::senseText)
            val visible = if (showAllSynonyms) synonymOptions else synonymOptions.take(6)
            visible.forEach { term ->
                val fromDictionary = term in dictionarySynonyms
                FilterChip(selected = senseText(term) in terms, enabled = !busy,
                    onClick = { toggleSynonym(term, if (fromDictionary) selected?.source.orEmpty() else GEMINI_SYNONYMS) },
                    label = { Text(term) })
            }
        }
        if (synonymOptions.any { it !in dictionarySynonyms }) {
            Text(GEMINI_SYNONYMS, style = MaterialTheme.typography.labelSmall)
        }
        if (synonymOptions.size > 6) {
            TextButton(onClick = { showAllSynonyms = !showAllSynonyms }) {
                Text(if (showAllSynonyms) "Ver menos sinónimos" else "Ver más sinónimos")
            }
        }
    }
    if (providerSense != null && providers != null && providers.synonyms.result.isNullOrEmpty()) {
        if (providers.synonyms.loading) Text("Preparando sinónimos…")
        else TextButton(onClick = { requestNano(SenseRequest.Synonyms, retry = true) }, enabled = !busy) {
            Text("Sugerir sinónimos con Gemini")
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
    Button(onClick = { save(if (fromStudy) AfterSave.Study else AfterSave.Library) }, enabled = valid) { Text("Guardar") }
    if (original == null) {
        OutlinedButton(onClick = { save(AfterSave.AnotherCard) }, enabled = valid) {
            Text("Guardar y añadir otra")
        }
    } else if (!fromStudy) {
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
