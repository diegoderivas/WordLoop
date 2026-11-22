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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.review.Direction


@Composable
internal fun Library(
    cards: List<Card>, knownTags: List<String>, highlighted: String?,
    onAdd: () -> Unit, onEdit: (String) -> Unit, onDelete: suspend (String) -> Unit,
    onProgress: (String) -> Unit = {},
    filter: String? = null, onFilter: (String?) -> Unit = {}, onPractice: (String) -> Unit = {},
    schedules: List<com.example.diegovocabcards.data.Schedule> = emptyList(), onAnother: (Card) -> Unit = {}
) {
    var exposedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var search by rememberSaveable { mutableStateOf("") }
    var progress by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf(LibraryOrder.Alphabetical) }
    var direction by rememberSaveable { mutableStateOf<Direction?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(cards, schedules, search, filter, direction, status, order) {
        organiseLibrary(cards, schedules, search, filter, direction, status, order)
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var lastHighlighted by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(highlighted, cards) {
        if (highlighted != null && highlighted != lastHighlighted && cards.any { it.id == highlighted }) {
            search = ""; direction = null; status = null
            val index = organiseLibrary(cards, schedules, "", filter, null, null, order).indexOfFirst { it.card.id == highlighted }
            if (index >= 0) list.scrollToItem(index)
            lastHighlighted = highlighted
        }
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_search_english_or_spanish)) }, singleLine = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "List", true to "Progress").forEach { (value, label) ->
            FilterChip(selected = progress == value, onClick = { focus.clearFocus(); progress = value }, label = { Text(label) }, modifier = Modifier.weight(1f))
        }
    }
    val active = listOfNotNull(filter, direction, status).size
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TagMenu(order.label, LibraryOrder.entries.map { it.label }) { choice -> order = LibraryOrder.entries.first { it.label == choice } }
        OutlinedButton(onClick = { focus.clearFocus(); filters = true }) { Text(if (active == 0) "Filters" else "Filters ($active)") }
        TextButton(onClick = onAdd) { Text(stringResource(R.string.ui_add_card)) }
    }
    if (filters) AlertDialog(onDismissRequest = { filters = false }, title = { Text(stringResource(R.string.ui_organise_library)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ui_direction))
            TagMenu(direction?.label ?: "Both directions", listOf("Both directions") + Direction.entries.map { it.label }) { choice ->
                direction = Direction.entries.find { it.label == choice }
            }
            Text(stringResource(R.string.ui_state))
            TagMenu(status ?: "All states", listOf("All states", "New", "Learning", "Review", "Relearning")) { status = it.takeUnless { s -> s == "All states" } }
            Text(stringResource(R.string.ui_tags))
            TagMenu(filter ?: "All tags", knownTags) { onFilter(it) }
            TextButton(onClick = { status = null; direction = null; onFilter(null) }) { Text(stringResource(R.string.ui_clear_filters)) }
        }
    }, confirmButton = { TextButton(onClick = { filters = false }) { Text(stringResource(R.string.ui_done)) } })
    if (filter != null) TextButton(onClick = { onPractice(filter) }) { Text(stringResource(R.string.ui_practise_this_tag)) }
    if (deleteFailed) Text(stringResource(R.string.ui_could_not_delete_the_card_please_try_again), color = MaterialTheme.colorScheme.error)
    if (visible.isEmpty()) Text(if (cards.isEmpty()) "Your library is empty." else "No matching cards.")
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
                    TranslatedText(card.spanish, attributed = GOOGLE_TRANSLATION in card.source)
                    if (GEMINI_TRANSLATION in card.source) Text(stringResource(R.string.ui_translated_by_gemini), style = MaterialTheme.typography.labelSmall)
                    if (progress) row.sides.forEach { side ->
                        val due = side.schedule?.dueAt
                        val availability = when {
                            due == null -> "Available"
                            due <= now -> "Due now"
                            else -> "In " + com.example.diegovocabcards.review.formatReviewInterval(((due - now + 59_999) / 60_000) * 60_000)
                        }
                        val label = if (side.direction == Direction.EN_ES) "EN → ES" else "ES → EN"
                        Text("$label   ${side.status} · $availability", style = MaterialTheme.typography.bodyMedium)
                    } else if (card.tags.isNotEmpty()) Text(card.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    cards.find { it.id == selectedId }?.let { card ->
        DetailPage("Card details", { selectedId = null }) {
            Section {
                EnglishPronunciation(card.english, card.ipa) { Text(card.english, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium) }
                TranslatedText(card.spanish, attributed = GOOGLE_TRANSLATION in card.source)
                listOf(card.definition, card.example, card.generatedExamples, card.synonyms, card.senseCategories, card.tags.joinToString(" · "))
                    .filter(String::isNotBlank).forEach { Text(it) }
                SavedUsageContent(card.usageNote, "")
            }
            Button(onClick = { selectedId = null; onEdit(card.id) }) { Text(stringResource(R.string.ui_edit_card)) }
            TextButton(onClick = { selectedId = null; onAnother(card) }) { Text(stringResource(R.string.ui_add_another_meaning)) }
            TextButton(onClick = { selectedId = null; onProgress(card.id) }) { Text(stringResource(R.string.ui_view_history)) }
            TextButton(onClick = { confirmId = card.id }) { Text(stringResource(R.string.ui_delete_meaning)) }
        }
    }
    cards.find { it.id == confirmId }?.let { card ->
        AlertDialog(onDismissRequest = { if (!deleting) confirmId = null }, title = { Text(stringResource(R.string.ui_delete_this_meaning)) },
            text = { Text(card.english + " · " + card.spanish) },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmId = null }) { Text(stringResource(R.string.ui_cancel)) } },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                deleting = true; deleteFailed = false
                scope.launch {
                    try { onDelete(card.id); selectedId = null; exposedId = null }
                    catch (_: SQLiteException) { deleteFailed = true }
                    finally { confirmId = null; deleting = false }
                }
            }) { Text(stringResource(R.string.ui_delete)) } })
    }
}

@Composable
internal fun Practice(cards: List<Card>, cardTools: @Composable (Card) -> Unit = {}, onAdd: () -> Unit) {
    var direction by rememberSaveable { mutableStateOf<Direction?>(null) }
    var session by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val ids = session
    val card = cards.find { it.id == ids?.getOrNull(index) }
    if (ids != null && card != null) {
        key(card.id, index) {
            val spanishFirst = direction == Direction.ES_EN || (direction == null && index < ids.size / 2)
            StudyPresentation(card, spanishFirst, revealed, "${if (spanishFirst) Direction.ES_EN.label else Direction.EN_ES.label} · ${index + 1} of ${ids.size}",
                onReveal = { revealed = true }, cardTools = cardTools) {
                Button(onClick = { revealed = false; index++ }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_next))
                }
            }
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            cards.isEmpty() -> {
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
                            Text(side?.label ?: "Both directions")
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
