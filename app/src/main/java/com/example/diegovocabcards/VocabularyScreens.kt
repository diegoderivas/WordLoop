package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.formatReviewRemaining


@Composable
internal fun Library(
    cards: List<Card>, knownTags: List<String>, highlighted: String?,
    onAdd: () -> Unit, onEdit: (String) -> Unit, onDelete: suspend (String) -> Unit,
    model: VocabularyModel? = null,
    onProgress: (String) -> Unit = {},
    filter: String? = null, onFilter: (String?) -> Unit = {}, onPractice: (String) -> Unit = {},
    schedules: List<com.example.diegovocabcards.data.Schedule> = emptyList(), onAnother: (Card) -> Unit = {}
) {
    var exposedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    var packMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var search by rememberSaveable { mutableStateOf("") }
    var progress by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf(LibraryOrder.Alphabetical) }
    var direction by rememberSaveable { mutableStateOf<Direction?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(cards, schedules, search, filter, direction, status, order, language) {
        organiseLibrary(cards, schedules, search, filter, direction, status, order, language)
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var lastHighlighted by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(highlighted, cards) {
        if (highlighted != null && highlighted != lastHighlighted && cards.any { it.id == highlighted }) {
            search = ""; direction = null; status = null; language = null
            val index = organiseLibrary(cards, schedules, "", filter, null, null, order).indexOfFirst { it.card.id == highlighted }
            if (index >= 0) list.scrollToItem(index)
            lastHighlighted = highlighted
        }
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text(if (cards.any { it.language == "ja" }) "Search English or translation" else stringResource(R.string.ui_search_english_or_spanish)) }, singleLine = true)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("List", "Progress").forEachIndexed { index, label ->
            SegmentedButton(selected = progress == (index == 1),
                onClick = { focus.clearFocus(); progress = index == 1 },
                shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
        }
    }
    val active = listOfNotNull(filter, direction, status, language).size
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TagMenu(order.label, LibraryOrder.entries.map { it.label }) { choice -> order = LibraryOrder.entries.first { it.label == choice } }
            OutlinedButton(onClick = { focus.clearFocus(); filters = true }) { Text(if (active == 0) "Filters" else "Filters ($active)") }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.ui_add_card)) }
        }
        if (model != null) CardPackActions(visible.map { it.card }, model::importCards) { packMessage = it }
    }
    if (filters) AlertDialog(onDismissRequest = { filters = false }, title = { Text(stringResource(R.string.ui_organise_library)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ui_direction))
            TagMenu(direction?.labelFor(cards.translationLabel()) ?: "Both directions", listOf("Both directions") + Direction.entries.map { it.labelFor(cards.translationLabel()) }) { choice ->
                direction = Direction.entries.find { it.labelFor(cards.translationLabel()) == choice }
            }
            Text(stringResource(R.string.ui_state))
            TagMenu(status ?: "All states", listOf("All states", "New", "Learning", "Review", "Relearning")) { status = it.takeUnless { s -> s == "All states" } }
            Text(stringResource(R.string.ui_tags))
            TagMenu(filter ?: "All tags", knownTags) { onFilter(it) }
            Text("Translation language")
            TagMenu(language?.let(::languageName) ?: "All languages", listOf("All languages", "Spanish", "Japanese")) {
                language = when (it) { "Spanish" -> "es"; "Japanese" -> "ja"; else -> null }
            }
            TextButton(onClick = { language = null; status = null; direction = null; onFilter(null) }) { Text(stringResource(R.string.ui_clear_filters)) }
        }
    }, confirmButton = { TextButton(onClick = { filters = false }) { Text(stringResource(R.string.ui_done)) } })
    if (active > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        direction?.let { ActiveLibraryFilter("Direction · ${it.labelFor(cards.translationLabel())}") { direction = null } }
        status?.let { ActiveLibraryFilter("State · $it") { status = null } }
        language?.let { ActiveLibraryFilter(languageName(it)) { language = null } }
        filter?.let { ActiveLibraryFilter("Tag · $it") { onFilter(null) } }
    }
    if (packMessage.isNotBlank()) Text(
        packMessage, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall
    )
    if (filter != null) TextButton(onClick = { onPractice(filter) }) { Text(stringResource(R.string.ui_practise_this_tag)) }
    if (deleteFailed) Text(stringResource(R.string.ui_could_not_delete_the_card_please_try_again), color = MaterialTheme.colorScheme.error)
    if (visible.isEmpty()) {
        Text(stringResource(if (cards.isEmpty()) R.string.ui_library_empty else R.string.ui_no_matching_cards))
        if (cards.isNotEmpty() && (search.isNotBlank() || active > 0)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (search.isNotBlank()) TextButton(onClick = { search = ""; focus.clearFocus() }) {
                    Text(stringResource(R.string.ui_clear_search))
                }
                if (active > 0) TextButton(onClick = {
                    direction = null
                    status = null
                    language = null
                    onFilter(null)
                }) { Text(stringResource(R.string.ui_clear_filters)) }
            }
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress, schedules) {
        while (progress) {
            now = System.currentTimeMillis()
            val until = schedules.filter { it.dueAt > now }.minOfOrNull { it.dueAt - now } ?: 60_000L
            kotlinx.coroutines.delay(until.coerceIn(1L, 60_000L))
        }
    }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(visible, key = { it.card.id }) { row ->
            val card = row.card
            SwipeCardActions(card, onEdit = { selectedId = card.id }, revealed = exposedId == card.id,
                onReveal = { exposedId = if (it) card.id else null }, onDelete = { confirmId = card.id }) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(card.english, style = MaterialTheme.typography.titleMedium)
                    val category = card.senseCategories.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString(" · ", transform = ::categoryLabel)
                    if (category.isNotEmpty()) Text(category, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TranslatedText(card.spanish, attributed = GOOGLE_TRANSLATION in card.source,
                        style = MaterialTheme.typography.bodyLarge)
                    if (cards.any { it.language == "ja" }) Text(languageName(card.language), style = MaterialTheme.typography.labelSmall)
                    if (GEMINI_TRANSLATION in card.source) Text(stringResource(R.string.ui_translated_by_gemini), style = MaterialTheme.typography.labelSmall)
                    if (progress) row.sides.forEach { side ->
                        val due = side.schedule?.dueAt
                        val availability = when {
                            due == null -> "Available"
                            due <= now -> "Due now"
                            else -> "In " + formatReviewRemaining(due - now)
                        }
                        val label = if (side.direction == Direction.EN_ES) "EN → ${card.language.uppercase()}" else "${card.language.uppercase()} → EN"
                        Text("$label   ${side.status} · $availability", style = MaterialTheme.typography.bodyMedium)
                    } else if (card.tags.isNotEmpty()) Text(card.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    cards.find { it.id == selectedId }?.let { card ->
        DetailPage(stringResource(R.string.ui_card_details), { selectedId = null }, stringResource(R.string.ui_close)) {
            Section {
                EnglishPronunciation(card.english, card.ipa) { Text(card.english, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium) }
                if (cards.any { it.language == "ja" }) Text(languageName(card.language), style = MaterialTheme.typography.labelSmall)
                TranslatedText(card.spanish, attributed = GOOGLE_TRANSLATION in card.source)
                if (card.definition.isNotBlank()) {
                    CardDetailHeading(stringResource(R.string.ui_meaning))
                    Text(card.definition)
                }
                if (card.example.isNotBlank() || card.generatedExamples.isNotBlank()) {
                    CardDetailHeading(stringResource(R.string.ui_examples))
                    card.example.lines().filter(String::isNotBlank).forEach { Text(it) }
                    SavedUsageContent("", card.generatedExamples)
                }
                if (card.synonyms.isNotBlank()) {
                    CardDetailHeading(stringResource(R.string.ui_synonyms))
                    Text(card.synonyms)
                }
                if (card.senseCategories.isNotBlank()) {
                    CardDetailHeading(stringResource(R.string.ui_word_class))
                    Text(card.senseCategories.split(',').joinToString(" · ") { categoryLabel(it.trim()) })
                }
                if (card.tags.isNotEmpty()) {
                    CardDetailHeading(stringResource(R.string.ui_tags))
                    Text(card.tags.joinToString(" · "))
                }
                SavedUsageContent(card.usageNote, "", guideSynonyms = guideSynonyms(listOf(card.synonyms)))
            }
            Button(onClick = { selectedId = null; onEdit(card.id) }) { Text(stringResource(R.string.ui_edit_card)) }
            TextButton(onClick = { selectedId = null; onAnother(card) }) { Text(stringResource(R.string.ui_add_another_meaning)) }
            TextButton(onClick = { selectedId = null; onProgress(card.id) }) { Text(stringResource(R.string.ui_view_history)) }
            TextButton(onClick = { confirmId = card.id }) { Text(stringResource(R.string.ui_delete_meaning)) }
        }
    }
    cards.find { it.id == confirmId }?.let { card ->
        DeleteMeaningDialog(
            card = card,
            deleting = deleting,
            onCancel = { confirmId = null },
            onConfirm = {
                if (!deleting) {
                    deleting = true
                    deleteFailed = false
                    scope.launch {
                        try { onDelete(card.id); selectedId = null; exposedId = null }
                        catch (_: SQLiteException) { deleteFailed = true }
                        finally { confirmId = null; deleting = false }
                    }
                }
            }
        )
    }
}

@Composable
private fun CardDetailHeading(title: String) {
    Text(
        title, Modifier.padding(top = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ActiveLibraryFilter(label: String, remove: () -> Unit) {
    InputChip(selected = true, onClick = remove, label = { Text(label) },
        trailingIcon = { Text("×", modifier = Modifier.semantics { contentDescription = "Remove $label filter" }) })
}

@Composable
internal fun Practice(
    cards: List<Card>,
    cardTools: @Composable (Card) -> Unit = {},
    onDelete: (suspend (String) -> Unit)? = null,
    onAdd: () -> Unit
) {
    var direction by rememberSaveable { mutableStateOf<Direction?>(null) }
    var session by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val ids = session
    val byId = cards.associateBy { it.id }
    val position = ids?.let { (index until it.size).firstOrNull { position -> it[position] in byId } }
    if (ids != null && position != null) {
        val card = byId.getValue(ids[position])
        key(card.id, position) {
            val spanishFirst = direction == Direction.ES_EN || (direction == null && position < ids.size / 2)
            val side = if (spanishFirst) Direction.ES_EN else Direction.EN_ES
            val number = ids.take(position + 1).count { it in byId }
            val total = ids.count { it in byId }
            StudyPresentation(card, spanishFirst, revealed && position == index,
                "${side.labelFor(languageName(card.language))} · $number of $total",
                onReveal = { index = position; revealed = true }, cardTools = cardTools,
                onDelete = onDelete?.let { remove ->
                    { id ->
                        remove(id)
                        val nextIndex = ids.take(position).count { it != id }
                        session = ArrayList(ids.filterNot { it == id })
                        index = nextIndex
                        revealed = false
                    }
                }) {
                Button(onClick = { revealed = false; index = position + 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_next))
                }
            }
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            cards.isEmpty() && ids == null -> {
                Text(stringResource(R.string.ui_add_a_card_to_start_practising))
                Button(onClick = onAdd) { Text(stringResource(R.string.ui_add_card)) }
            }
            ids == null -> {
                Column(Modifier.selectableGroup()) {
                    for (side in listOf(null) + Direction.entries) {
                        val selected = direction == side
                        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton,
                            onClick = { direction = side }).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(side?.labelFor(cards.translationLabel()) ?: "Both directions")
                        }
                    }
                }
                Button(onClick = {
                    val senses = cards.map { it.id }
                    session = ArrayList(if (direction == null) senses + senses else senses)
                    index = 0
                    revealed = false
                }) { Text(stringResource(R.string.ui_start_practice)) }
            }
            else -> {
                Text(stringResource(R.string.ui_practice_complete))
                Button(onClick = { session = null }) { Text(stringResource(R.string.ui_practise_again)) }
            }
        }
    }
}
