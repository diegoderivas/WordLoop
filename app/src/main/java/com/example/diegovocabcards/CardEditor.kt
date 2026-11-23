package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
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

internal class EditorExitGuard {
    var request: ((() -> Unit) -> Unit)? = null
    var pending: (() -> Unit)? = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditor(
    original: Card?, initialEnglish: String, initialTags: List<String>, knownTags: List<String>,
    model: VocabularyModel, lookup: (suspend (String) -> List<Suggestion>)? = null,
    translate: suspend (String, Suggestion) -> String? = LocalSenseTranslation::suggest,
    nanoOptions: suspend (String, Suggestion, SenseRequest) -> List<String> = ::nanoSenseOptions,
    usageNotes: UsageNoteGenerator = NanoUsageNotes,
    ipaSuggestion: suspend (IpaContext) -> String? = ::suggestIpa,
    fromStudy: Boolean = false,
    language: String = original?.language ?: "es",
    exitGuard: EditorExitGuard? = null,
    onOpenExisting: (String) -> Unit = {},
    onAnotherSense: (Card) -> Unit
) {
    val id = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
    var touched by rememberSaveable { mutableStateOf(false) }
    var english by rememberSaveable { mutableStateOf(original?.english ?: initialEnglish) }
    var spanish by rememberSaveable { mutableStateOf(original?.spanish.orEmpty()) }
    var example by rememberSaveable { mutableStateOf(original?.example.orEmpty()) }
    var guideEdited by rememberSaveable { mutableStateOf(false) }
    var changingMeaning by rememberSaveable(english.trim()) { mutableStateOf(false) }
    var comparing by rememberSaveable(english.trim()) { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var editingCategory by rememberSaveable { mutableStateOf(false) }
    var definition by rememberSaveable { mutableStateOf(original?.definition.orEmpty()) }
    var synonyms by rememberSaveable { mutableStateOf(original?.synonyms.orEmpty()) }
    var senseCategories by rememberSaveable { mutableStateOf(original?.senseCategories.orEmpty()) }
    val localCards by model.cards.collectAsState(initial = emptyList())
    var manualFields by rememberSaveable {
        mutableStateOf(if (original == null) arrayListOf() else arrayListOf(MeaningField.Spanish.name))
    }
    var automaticFields by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var retainedSource by rememberSaveable { mutableStateOf(original?.source.orEmpty()) }
    var activeSource by rememberSaveable { mutableStateOf("") }
    var exampleSource by rememberSaveable { mutableStateOf("") }
    var synonymSource by rememberSaveable { mutableStateOf("") }
    var translationSource by rememberSaveable { mutableStateOf("") }
    var translationEdited by rememberSaveable { mutableStateOf(false) }
    var usageNote by rememberSaveable { mutableStateOf(original?.usageNote.orEmpty()) }
    var generatedExamples by rememberSaveable { mutableStateOf(original?.generatedExamples.orEmpty()) }
    var ipa by rememberSaveable { mutableStateOf(preferredIpa(original?.ipa.orEmpty()).orEmpty()) }
    var ipaSource by rememberSaveable { mutableStateOf(original?.ipaSource.orEmpty()) }
    var manualIpa by rememberSaveable { mutableStateOf(original?.ipaSource == MANUAL_IPA ||
        (original?.ipaSource.isNullOrBlank() && ipa.isNotBlank())) }
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
        touched = true
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
        if (field == MeaningField.Spanish) {
            if (value.isNotBlank()) retainedSource = combineSources(retainedSource, translationSource)
            translationSource = ""
            translationEdited = true
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
    var lookupSucceeded by remember(currentQuery) { mutableStateOf<Boolean?>(null) }
    var proposed by remember(currentQuery) { mutableStateOf<Suggestion?>(null) }
    var selectedKey by rememberSaveable(currentQuery) { mutableStateOf<String?>(null) }
    var more by rememberSaveable(currentQuery) { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var choosingExample by rememberSaveable(currentQuery) { mutableStateOf(false) }
    var showAllSynonyms by rememberSaveable(currentQuery, selectedKey) { mutableStateOf(false) }
    val selected = suggestions.firstOrNull { it.selectionKey() == selectedKey }
    val scope = key(id, currentQuery) { rememberCoroutineScope() }
    val nanoMutex = remember(id) { Mutex() }
    val requests = remember(scope) { EditorRequests(scope, model.guideScope, nanoMutex) }
    DisposableEffect(requests) { onDispose { requests.closeDraft() } }
    // A saved card also supplies a context for manually requested translations.
    val providerSense = selected ?: original?.takeIf { it.english == currentQuery }?.let {
        Suggestion(it.definition.ifBlank { it.spanish }, it.senseCategories, it.source,
            examples = it.example.lines().filter(String::isNotBlank), language = language)
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
        if (usageNote.isNotBlank() && !retry) return
        val sense = providerSense ?: return
        providers?.guide?.start(retry, UsageNoteResult.Error) {
            requests.nano.withLock { usageNotes.generate(usageNotePrompt(currentQuery, sense), download) {} }
        }
    }
    fun applyTranslation(value: String, source: String, explicitly: Boolean) {
        retainedSource = combineSources(retainedSource).split("\n\n")
            .filterNot { it == GOOGLE_TRANSLATION || it == GEMINI_TRANSLATION }.joinToString("\n\n")
        spanish = value
        if (explicitly) {
            touched = true
            manualFields = ArrayList((manualFields + MeaningField.Spanish.name).distinct())
            automaticFields = ArrayList(automaticFields - MeaningField.Spanish.name)
        } else {
            automaticFields = ArrayList((automaticFields + MeaningField.Spanish.name).distinct())
        }
        translationSource = source
        translationEdited = false
    }
    fun canFill(field: MeaningField) =
        selected != null && english.trim() == currentQuery && selectedKey == selected.selectionKey() &&
            field.name !in manualFields
    LaunchedEffect(selected) {
        if (selected != null) {
            requestGoogle()
            requestNano(SenseRequest.Translation)
            requestGuide()
            requestNano(SenseRequest.Synonyms)
        }
    }
    val googleResult = providers?.google?.result
    val geminiResult = providers?.translation?.result
    LaunchedEffect(selected, googleResult, geminiResult) {
        if (canFill(MeaningField.Spanish)) {
            val gemini = geminiResult?.firstOrNull()
            val candidate = gemini ?: googleResult?.firstOrNull()
            if (candidate != null) {
                applyTranslation(candidate, if (gemini != null) GEMINI_TRANSLATION else GOOGLE_TRANSLATION, explicitly = false)
            }
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
    val lookingUp = stringResource(R.string.ui_looking_up_meanings)
    val noMeanings = stringResource(R.string.ui_no_meaning_suggestions)
    val lookupUnavailable = stringResource(R.string.ui_meaning_lookup_unavailable)
    LaunchedEffect(currentQuery) {
        if (currentQuery.length >= 2) {
            kotlinx.coroutines.delay(600)
            queryState = lookingUp
            try {
                val result = distinctMeanings(lookup?.invoke(currentQuery) ?: Dictionary.lookup(currentQuery, language))
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                suggestions = result
                lookupSucceeded = true
                queryState = if (result.isEmpty()) noMeanings else ""
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                lookupSucceeded = false
                queryState = lookupUnavailable
            }
        }
    }
    val savedSenseMatches = suggestions.filter {
        original != null && original.english == currentQuery && original.definition.isNotBlank() &&
            senseText(it.definition) == senseText(original.definition) &&
            (original.senseCategories.isBlank() || it.category.equals(original.senseCategories, true))
    }
    val ipaCandidates = selected?.let(::listOf) ?: savedSenseMatches.ifEmpty { suggestions }
    val dictionaryIpa = ipaCandidates.map { candidate ->
        candidate.pronunciation?.let { sound -> preferredIpa(sound.ipa)?.let { sound.copy(ipa = it) } }
    }.let { sounds ->
        val first = sounds.firstOrNull()
        first?.takeIf { sounds.all { sound -> sound?.ipa == it.ipa } }
    }
    val ipaContext = lookupSucceeded?.takeIf { selectedKey == null || selected != null }?.let {
        ipaContext(currentQuery, selected, ipaCandidates, original, lookupSucceeded = it)
    }
    val ipaScope = rememberCoroutineScope()
    val ipaRequests = remember(id) { mutableMapOf<IpaContext, EditorRequest<String>>() }
    val ipaRequest = ipaContext?.takeIf { dictionaryIpa == null }
        ?.let { ipaRequests.getOrPut(it) { EditorRequest(ipaScope) } }
    val ipaAnswer = ipaRequest?.result
    val ipaSelectionKey = selectedKey
    LaunchedEffect(dictionaryIpa, ipaContext, ipaAnswer, ipa, manualIpa, currentQuery, ipaSelectionKey) {
        if (ipa.isNotBlank() || manualIpa || english.trim() != currentQuery || selectedKey != ipaSelectionKey ||
            lookupSucceeded == null || (selectedKey != null && selected == null)) return@LaunchedEffect
        if (dictionaryIpa != null) {
            ipa = dictionaryIpa.ipa
            ipaSource = dictionaryIpa.source
        } else if (ipaContext != null && ipaRequest != null) {
            if (ipaAnswer != null) {
                if (ipaAnswer.isNotBlank()) {
                    ipa = ipaAnswer
                    ipaSource = GEMINI_IPA
                }
            } else {
                ipaRequest.start(onFailure = "") {
                    nanoMutex.withLock { ipaSuggestion(ipaContext)?.let(::parseSuggestedIpa).orEmpty() }
                }
            }
        }
    }
    fun applySuggestion(value: Suggestion, replaceManual: Boolean = false) {
        touched = true
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
        if (MeaningField.Spanish in automatic || MeaningField.Spanish in applied) {
            translationSource = ""
            translationEdited = false
        }
        if (MeaningField.Example in automatic || MeaningField.Example in applied) exampleSource = ""
        if (MeaningField.Synonyms in automatic || MeaningField.Synonyms in applied) synonymSource = ""
        if (!manualIpa) {
            val savedSound = original?.takeIf { it.english == currentQuery &&
                (it.definition.isBlank() || senseText(it.definition) == senseText(value.definition)) }
            val savedIpa = preferredIpa(savedSound?.ipa.orEmpty())
            val dictionarySound = value.pronunciation?.let { sound ->
                preferredIpa(sound.ipa)?.let { sound.copy(ipa = it) }
            }
            ipa = savedIpa ?: dictionarySound?.ipa.orEmpty()
            ipaSource = if (savedIpa != null) savedSound?.ipaSource.orEmpty() else dictionarySound?.source.orEmpty()
        }
        selectedKey = value.selectionKey()
        choosingExample = false
        showAllSynonyms = false
        proposed = null
    }
    fun selectExample(value: String) {
        touched = true
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
        synonyms, senseCategories,
        storedAttribution(combineSources(retainedSource, activeSource, translationSource, synonymSource, exampleSource)),
        if (guideEdited) usageNote else usageNote.ifBlank { suggestedGuide?.forStorage().orEmpty() }, generatedExamples, formattedIpa(ipa), ipaSource, language)
    var discard by remember { mutableStateOf(false) }
    val baseline = original ?: Card(id, initialEnglish.trim(), "", "", initialTags, language = language)
    val dirty by rememberUpdatedState(touched && draft != baseline)
    DisposableEffect(exitGuard) {
        exitGuard?.request = { leave -> if (dirty) { exitGuard.pending = leave; discard = true } else leave() }
        onDispose { exitGuard?.request = null; exitGuard?.pending = null }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false; exitGuard?.pending = null },
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        confirmButton = { TextButton(onClick = { discard = false; exitGuard?.pending?.invoke() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false; exitGuard?.pending = null }) { Text("Keep editing") } }
    )
    fun addPending() {
        tags = ArrayList(effectiveTags)
        pending = ""
    }
    fun save(next: AfterSave) {
        focus.clearFocus()
        val pending = providers?.guide?.pending.takeIf { selected != null && !guideEdited && draft.usageNote.isBlank() }
        model.save(draft, next, requests.retainForSave(pending))
    }

    Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section {
                EnglishPronunciation(currentQuery, null) {
                    OutlinedTextField(english, { value ->
                        if (value.trim() != currentQuery) {
                            automatic.forEach { setValue(it, "") }
                            automaticFields = arrayListOf()
                            activeSource = ""
                            if (MeaningField.Example in automatic) exampleSource = ""
                            if (MeaningField.Synonyms in automatic) synonymSource = ""
                            if (MeaningField.Spanish in automatic) translationSource = ""
                            if (!manualIpa) { ipa = ""; ipaSource = "" }
                        }
                        touched = true
                        english = value
                    }, Modifier.weight(1f), enabled = !busy,
                    label = { Text(stringResource(R.string.ui_headword)) }, singleLine = true)
                }
                var editIpa by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { editIpa = !editIpa }) {
                    when {
                        editIpa -> Text("Hide pronunciation")
                        ipa.isBlank() -> Text("Add IPA")
                        else -> { Text("IPA"); Spacer(Modifier.width(8.dp)); Text(ipa) }
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(androidx.compose.ui.res.painterResource(if (editIpa) R.drawable.expand_less else R.drawable.expand_more), null)
                }
                if (editIpa) OutlinedTextField(ipa, { value ->
                    touched = true
                    ipa = value
                    ipaSource = MANUAL_IPA
                    manualIpa = true
                }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                label = { Text(stringResource(R.string.ui_pronunciation_ipa)) }, placeholder = { Text("/…/") })
            }
            val matches = localCards.filter { it.language == language && it.id != original?.id && currentQuery.isNotBlank() &&
                senseText(it.english) == senseText(currentQuery) }
            var viewingMatches by rememberSaveable(currentQuery) { mutableStateOf(false) }
            if (matches.isNotEmpty()) {
                val exact = selected?.let { sense -> matches.any { card ->
                        sense.definition.isNotBlank() && senseText(card.definition) == senseText(sense.definition) &&
                        card.senseCategories.equals(sense.category, true) &&
                        (sense.spanish.isEmpty() || sense.spanish.any { senseText(it) == senseText(card.spanish) })
                    } } == true
                val possible = selected?.let { sense -> matches.any { card ->
                        card.definition.isBlank() || senseText(card.definition) == senseText(sense.definition) ||
                        sense.spanish.any { senseText(it) == senseText(card.spanish) }
                    } } == true
                val message = when {
                    exact -> "You already have this meaning"
                    possible -> "Possible duplicate"
                    else -> "In your library"
                }
                TextButton(onClick = { viewingMatches = true }, enabled = !busy) {
                    Text("$message · View cards (${matches.size})")
                }
                if (viewingMatches) AlertDialog(onDismissRequest = { viewingMatches = false },
                title = { Text("Cards for $currentQuery") }, text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (card in matches) TextButton(onClick = {
                            viewingMatches = false
                            val open = { onOpenExisting(card.id) }
                            exitGuard?.request?.invoke(open) ?: open()
                        }) {
                            Text(card.spanish)
                        }
                    }
                }, confirmButton = { TextButton(onClick = { viewingMatches = false }) { Text(stringResource(R.string.ui_close)) } })
            }
            if (queryState.isNotEmpty()) {
                Text(queryState, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (suggestions.isNotEmpty()) {
                val showingChoices = selected == null || changingMeaning
                Text(
                    stringResource(if (showingChoices) R.string.ui_meaning_suggestions else R.string.ui_meaning),
                    Modifier.semantics {
                        heading()
                        if (showingChoices) liveRegion = LiveRegionMode.Polite
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                fun choose(suggestion: Suggestion) {
                    changingMeaning = false
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
                    (if (selected != null && !changingMeaning) listOf(MeaningGroup(selected, emptyList())) else visible).forEach { group ->
                        val suggestion = selected?.takeIf { it in group.variants } ?: group.representative
                        MeaningOption(suggestion, suggestion == selected, busy, variantCount = group.variants.size) {
                            choose(suggestion)
                        }
                    }
                }
                if (selected != null && !changingMeaning) TextButton(onClick = { changingMeaning = true }) { Text(stringResource(R.string.ui_change_meaning)) }
                if ((selected == null || changingMeaning) && suggestions.size > top.size) {
                    TextButton(onClick = { focus.clearFocus(); more = true }) { Text(stringResource(R.string.ui_more_meanings)) }
                }
                if (more) {
                    AlertDialog(onDismissRequest = { more = false },
                    title = { Text(stringResource(R.string.ui_choose_a_meaning)) },
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
                    confirmButton = { TextButton(onClick = { more = false }) { Text(stringResource(R.string.ui_close)) } })
                }
            }
            Section {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OutlinedTextField(spanish, { editValue(MeaningField.Spanish, it) }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(stringResource(R.string.ui_card_translation, languageName(language))) },
                    supportingText = if (spanish.isBlank()) {
                        { Text(stringResource(R.string.ui_translation_required)) }
                    } else null,
                    singleLine = true)
                    if (!translationEdited && spanish.isNotBlank() && (translationSource == GOOGLE_TRANSLATION || GOOGLE_TRANSLATION in retainedSource)) {
                        TranslationAttribution()
                    } else if (!translationEdited && spanish.isNotBlank() &&
                        (translationSource == GEMINI_TRANSLATION || GEMINI_TRANSLATION in retainedSource)) {
                        Text(stringResource(R.string.ui_translation_suggested_by_gemini_nano), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (providerSense != null && providers != null) {
                    val comparisonState = stringResource(if (comparing) R.string.ui_expanded else R.string.ui_collapsed)
                    TextButton(
                        modifier = Modifier.semantics { stateDescription = comparisonState },
                        onClick = { focus.clearFocus(); comparing = !comparing },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.ui_compare_translations))
                        Spacer(Modifier.width(6.dp))
                        Icon(androidx.compose.ui.res.painterResource(if (comparing) R.drawable.expand_less else R.drawable.expand_more), null)
                    }
                    if (comparing) {
                        val currentSource = if (translationEdited) null else translationSource.ifBlank {
                            when {
                                GEMINI_TRANSLATION in retainedSource -> GEMINI_TRANSLATION
                                GOOGLE_TRANSLATION in retainedSource -> GOOGLE_TRANSLATION
                                MeaningField.Spanish in automatic -> providerSense.source
                                else -> null
                            }
                        }
                        TranslationComparison(
                            spanish, currentSource, providerSense, providers, !busy,
                            onApply = { value, source ->
                                focus.clearFocus()
                                applyTranslation(value, source, explicitly = true)
                            },
                            onGoogle = { requestGoogle(retry = true) },
                            onGemini = { requestNano(SenseRequest.Translation, retry = true) }
                        )
                    }
                }
            }
            var guideExpanded by rememberSaveable { mutableStateOf(false) }
            var guideEditing by rememberSaveable { mutableStateOf(false) }
            var guideTap by remember(id, currentQuery, selectedKey) { mutableStateOf(false) }
            var unsupportedHere by remember { mutableStateOf(false) }
            LaunchedEffect(guideTap, providerSense) {
                if (guideTap && providerSense == null && usageNote.isBlank() && usageNotes === NanoUsageNotes)
                    unsupportedHere = NanoUsageNotes.checkUnsupported()
            }
            if (guideTap && usageNote.isBlank() && (unsupportedHere || providers?.guide?.result == UsageNoteResult.Unavailable || (usageNotes === NanoUsageNotes && NanoUsageNotes.unsupported))) {
                AlertDialog(onDismissRequest = { guideTap = false }, text = { Text(UNSUPPORTED_GUIDE) },
                    confirmButton = { TextButton(onClick = { guideTap = false }) { Text("OK") } })
            }
            Section {
                val guideState = stringResource(if (guideExpanded) R.string.ui_expanded else R.string.ui_collapsed)
                TextButton(
                    modifier = Modifier.semantics { stateDescription = guideState },
                    onClick = {
                        guideExpanded = !guideExpanded
                        guideTap = guideExpanded
                        if (guideExpanded && usageNote.isBlank() && providers?.guide?.result == null) requestGuide()
                    }
                ) {
                    Text(stringResource(R.string.ui_usage_guide))
                    Spacer(Modifier.width(6.dp))
                    Icon(androidx.compose.ui.res.painterResource(if (guideExpanded) R.drawable.expand_less else R.drawable.expand_more), null)
                }
                if (guideExpanded) {
                    val guideTerms = if (MeaningField.Synonyms.name in manualFields) {
                        guideSynonyms(listOf(synonyms))
                    } else {
                        guideSynonyms(listOf(synonyms), selected?.synonymOptions.orEmpty(), providers?.synonyms?.result.orEmpty())
                    }
                    if (usageNote.isNotBlank() && !guideEditing) UsageGuideContent(usageNote, guideTerms)
                    if (!guideEditing && usageNote.isNotBlank() && providerSense != null &&
                        providers?.guide?.result == null && providers?.guide?.loading == false &&
                        !(usageNotes === NanoUsageNotes && NanoUsageNotes.unsupported)) {
                        TextButton(onClick = { requestGuide(retry = true) }) { Text("Generate again") }
                    }
                    if (!guideEditing && providers != null && providers.guide.result != UsageNoteResult.Unavailable &&
                        (usageNote.isBlank() || providers.guide.loading || providers.guide.result != null)) {
                        UsageNoteOffer(providers.guide, { download -> requestGuide(retry = true, download = download) },
                        enabled = !busy, synonyms = guideTerms,
                        onAddExample = { generated ->
                            if ((example.lines() + generatedExamples.lines()).none { it.trim().equals(generated, true) }) {
                                touched = true
                                generatedExamples = (generatedExamples.lines().filter(String::isNotBlank) + generated)
                                .joinToString("\n")
                            }
                        })
                    }
                    if (guideEditing) OutlinedTextField(
                        draft.usageNote,
                        { touched = true; usageNote = it; guideEdited = true },
                        Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.ui_guide_text)) }
                    )
                    TextButton(onClick = { focus.clearFocus(); guideEditing = !guideEditing }, enabled = !busy) {
                        Text(stringResource(if (guideEditing) R.string.ui_done_editing_guide else R.string.ui_edit_guide))
                    }
                }
            }

            Section {
                TextButton(onClick = { focus.clearFocus(); details = true }) {
                    Text(stringResource(R.string.ui_card_details))
                    Spacer(Modifier.width(6.dp))
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.chevron_right), null)
                }
                Text(detailSummary(listOf(example, generatedExamples).filter(String::isNotBlank).joinToString("\n"), synonyms), style = MaterialTheme.typography.bodySmall)
            }
            if (original != null && !fromStudy) {
                OutlinedButton(onClick = {
                    if (draft == original) onAnotherSense(original) else confirm = true
                }, enabled = !busy) { Text(stringResource(R.string.ui_add_another_meaning)) }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.failedId == id) {
                Text(stringResource(R.string.ui_could_not_save_please_try_again),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { save(if (fromStudy) AfterSave.Study else AfterSave.Library) }, enabled = valid) { Text(if (original == null) "Save card" else "Save changes") }
            if (original == null) {
                OutlinedButton(onClick = { save(AfterSave.AnotherCard) }, enabled = valid) {
                    Text(stringResource(R.string.ui_save_and_add_another))
                }
            }
        }
    }
    if (details) DetailPage("Card details", { details = false }) {
        Text("${english.trim()} · $spanish", style = MaterialTheme.typography.titleMedium)
        Section {
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
            label = { Text(stringResource(R.string.ui_example)) }, supportingText = { Text(stringResource(R.string.ui_enter_one_example_per_line)) })

            Text(stringResource(R.string.ui_word_class), style = MaterialTheme.typography.titleSmall)
            if (editingCategory) {
                OutlinedTextField(senseCategories, { editValue(MeaningField.Categories, it) }, Modifier.fillMaxWidth(),
                enabled = !busy, label = { Text(stringResource(R.string.ui_word_classes)) })
                TextButton(onClick = { editingCategory = false }) { Text(stringResource(R.string.ui_done)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labels = commaSeparatedValues(senseCategories).map { categoryLabel(it.lowercase()) }
                    Text(labels.joinToString(" · ").ifBlank { "Unspecified" }, Modifier.weight(1f))
                    TextButton(onClick = { editingCategory = true }, enabled = !busy) { Text(stringResource(R.string.ui_edit_word_class)) }
                }
            }

        }
        Section {
            val dictionarySynonyms = selected?.synonymOptions.orEmpty().filter(String::isNotBlank)
            val synonymOptions = (dictionarySynonyms + providers?.synonyms?.result.orEmpty()).distinctBy(::senseText)
            if (synonymOptions.isNotEmpty()) {
                Text(stringResource(R.string.ui_synonyms), style = MaterialTheme.typography.titleSmall)
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
                    Text(stringResource(R.string.ui_synonyms_suggested_by_gemini_nano), style = MaterialTheme.typography.labelSmall)
                }
                if (synonymOptions.size > 6) {
                    TextButton(onClick = { showAllSynonyms = !showAllSynonyms }) {
                        Text(if (showAllSynonyms) "Fewer synonyms" else "More synonyms")
                    }
                }
            }
            if (providerSense != null && providers != null && providers.synonyms.result.isNullOrEmpty()) {
                if (providers.synonyms.loading) Text(stringResource(R.string.ui_finding_synonyms))
                else TextButton(onClick = { requestNano(SenseRequest.Synonyms, retry = true) }, enabled = !busy) {
                    Text(stringResource(R.string.ui_suggest_synonyms_with_gemini))
                }
            }
            Column {
                OutlinedTextField(definition, { editValue(MeaningField.Definition, it) }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(stringResource(R.string.ui_english_definition)) })
                OutlinedTextField(synonyms, { editValue(MeaningField.Synonyms, it) }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(stringResource(R.string.ui_synonyms_comma_separated)) })

            }
            Text(stringResource(R.string.ui_tags), style = MaterialTheme.typography.titleMedium)
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { label ->
                        InputChip(selected = true, enabled = !busy,
                        onClick = { touched = true; tags = ArrayList(tags - label) },
                        label = { Text(label) }, trailingIcon = { Text("×") })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pending, { touched = true; pending = it }, Modifier.weight(1f), enabled = !busy,
                label = { Text(stringResource(R.string.ui_new_tag)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addPending(); focus.clearFocus() }))
                TextButton(onClick = { addPending() }, enabled = pending.isNotBlank() && !busy) { Text(stringResource(R.string.ui_add)) }
            }
            val available = knownTags.filter { label -> tags.none { it.equals(label, ignoreCase = true) } }
            if (available.isNotEmpty()) {
                TagMenu("Choose existing tag", available, enabled = !busy) {
                    touched = true
                    tags = ArrayList(tags + it)
                }
            }

        }
        SavedUsageContent("", generatedExamples, onRemoveExample = { removed ->
            touched = true
            generatedExamples = generatedExamples.lines().filterNot { it == removed }.joinToString("\n")
        })
        val sources = combineSources(retainedSource, activeSource, translationSource, synonymSource, exampleSource, ipaSource,
        *suggestions.map { it.source }.toTypedArray())
        if (sources.isNotEmpty()) TextButton(onClick = { showSources = true }) { Text(stringResource(R.string.ui_sources_and_licences)) }
        if (showSources && sources.isNotEmpty()) {
            val uriHandler = LocalUriHandler.current
            AlertDialog(onDismissRequest = { showSources = false }, title = { Text(stringResource(R.string.ui_sources_and_licences)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.lineSequence().filter { it.isNotBlank() }.distinct().forEach { line ->
                        if (line.startsWith("https://")) {
                            TextButton(onClick = { runCatching { uriHandler.openUri(line) } }) {
                                Text(when {
                                    "creativecommons.org" in line -> "View licence"
                                    "developers.google.com/ml-kit" in line -> "About translation"
                                    else -> "View original entry"
                                })
                            }
                        } else Text(sourceDisplay(line))
                    }
                    if (GOOGLE_TRANSLATION in sources) {
                        Text("This app may contain translations powered by Google. Google disclaims all warranties related to the translations, " +
                        "express or implied, including warranties of accuracy, reliability, merchantability, " +
                        "fitness for a particular purpose and non-infringement.")
                    }
                }
            }, confirmButton = { TextButton(onClick = { showSources = false }) { Text(stringResource(R.string.ui_close)) } })
        }

    }
    proposed?.let { suggestion ->
        AlertDialog(onDismissRequest = { proposed = null },
            title = { Text(stringResource(R.string.ui_replace_edited_fields)) },
            text = { Text(stringResource(R.string.ui_keep_your_edits_or_replace_them_with_values_from_the_new)) },
            confirmButton = { TextButton(onClick = {
                applySuggestion(suggestion, replaceManual = true)
                focus.clearFocus()
            }) { Text(stringResource(R.string.ui_use_suggestion)) } },
            dismissButton = { TextButton(onClick = {
                applySuggestion(suggestion)
                focus.clearFocus()
            }) { Text(stringResource(R.string.ui_keep_my_edits)) } })
    }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.ui_unsaved_changes)) },
            text = { Text(stringResource(R.string.ui_save_your_changes_before_adding_another_meaning)) },
            confirmButton = {
                TextButton(enabled = valid, onClick = { confirm = false; save(AfterSave.AnotherSense) }) {
                    Text(stringResource(R.string.ui_save_and_continue))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.ui_keep_editing)) } })
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
            Text(listOf(if (variant) "Variant" else "", categoryLabel(suggestion.category), suggestion.usage)
                .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
            if (variantCount > 0) {
                Text(if (variantCount == 1) "1 related variant" else "$variantCount related variants",
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
