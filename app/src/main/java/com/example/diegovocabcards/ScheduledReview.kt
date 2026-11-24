package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.ReviewDao
import com.example.diegovocabcards.data.ReviewEvent
import com.example.diegovocabcards.data.Schedule
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.ReviewSettings
import com.example.diegovocabcards.review.formatReviewInterval
import com.example.diegovocabcards.review.StudyQueue
import com.example.diegovocabcards.review.studyQueue
import com.example.diegovocabcards.review.nextStudyQuestion
import com.example.diegovocabcards.review.planStudyQuestions
import com.example.diegovocabcards.review.recordStudyRating
import io.github.openspacedrepetition.Rating
import java.time.Clock
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val reviewChoices = listOf(
    Rating.AGAIN to "Again", Rating.HARD to "Hard",
    Rating.GOOD to "Good", Rating.EASY to "Easy"
)

@Composable
internal fun rememberReviewQueues(
    cards: List<Card>?,
    schedules: List<Schedule>?,
    clock: Clock
): Map<Direction?, StudyQueue>? {
    var now by remember(clock) { mutableLongStateOf(clock.millis()) }
    val queues = remember(cards, schedules, now, clock) {
        if (cards == null || schedules == null) null
        else {
            val at = clock.millis()
            (listOf(null) + Direction.entries).associateWith { studyQueue(cards, schedules, it, at) }
        }
    }
    val wakeAt = queues?.values?.mapNotNull { it.nextDue }?.minOrNull()
    LaunchedEffect(wakeAt, clock, schedules) {
        while (true) {
            now = clock.millis()
            val untilDue = wakeAt?.minus(now)?.takeIf { it > 0 } ?: 60_000L
            delay(untilDue.coerceAtMost(60_000L))
        }
    }
    return queues
}

@Composable
internal fun ReviewDirections(
    queues: Map<Direction?, StudyQueue>?,
    home: Boolean,
    onStart: (Direction?, Boolean) -> Unit
) {
    if (queues == null) {
        Text(stringResource(R.string.ui_loading_reviews))
        return
    }
    val mixed = queues.getValue(null)
    val caughtUp = mixed.ready.isEmpty() && (mixed.nextDue != null || LocalStudyCards.current.isNotEmpty())
    Card(colors = CardDefaults.cardColors(containerColor = if (home) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (caughtUp) stringResource(R.string.ui_caught_up) else if (home) "Today’s review" else "Today",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge
            )
            if (caughtUp) {
                mixed.nextDue?.let { Text(stringResource(R.string.ui_next_review_date, formatReviewDate(it))) }
            } else {
                Text(pluralStringResource(R.plurals.questions, mixed.ready.size, mixed.ready.size))
            }
            if (!caughtUp && home) for (side in listOf(Direction.EN_ES, Direction.ES_EN)) {
                SummaryRow(side.labelFor(LocalStudyCards.current.translationLabel()), queues.getValue(side).ready.size)
            } else if (!caughtUp) {
                SummaryRow("Learning", mixed.ready.count { it.learning })
                SummaryRow("Due for review", mixed.dueCount - mixed.ready.count { it.learning })
                SummaryRow("New", mixed.newCount)
            }
            Button(onClick = { onStart(null, false) }, enabled = mixed.ready.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                colors = if (home) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)
                ) else ButtonDefaults.buttonColors()) { Text(stringResource(R.string.ui_start_review)) }
        }
    }
    val quickCount = remember(mixed) { mixed.quickBatch().size }
    OutlinedButton(onClick = { onStart(null, true) }, enabled = mixed.ready.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()) {
        Text(if (quickCount == 0) stringResource(R.string.ui_quick_review)
            else "Quick review · " + pluralStringResource(R.plurals.questions, quickCount, quickCount))
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.ui_advanced_options)) }
    if (advanced) for (direction in Direction.entries) {
        val queue = queues.getValue(direction)
        OutlinedButton(onClick = { onStart(direction, false) }, modifier = Modifier.fillMaxWidth(),
            enabled = queue.ready.isNotEmpty()) {
            Text(direction.labelFor(LocalStudyCards.current.translationLabel()))
        }
        Text("Due: ${queue.dueCount} · New: ${queue.newCount}", style = MaterialTheme.typography.bodySmall)
        if (queue.ready.isEmpty()) queue.nextDue?.let { Text("Next review: ${formatReviewDate(it)}") }
    }
}

@Composable
internal fun ScheduledReview(
    queue: StudyQueue,
    schedules: List<Schedule>,
    dao: ReviewDao,
    clock: Clock,
    settings: ReviewSettings,
    cardTools: @Composable (Card) -> Unit,
    quickBatch: List<String>? = null,
    onDelete: suspend (String) -> Unit,
    onContinue: () -> Unit,
    onHome: () -> Unit
) {
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentWord by rememberSaveable { mutableStateOf("") }
    var recentWords by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var quickTotal by rememberSaveable { mutableIntStateOf(quickBatch?.size ?: 0) }
    var turn by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var side by rememberSaveable { mutableStateOf(Direction.ES_EN) }
    var deletedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val available = queue.ready.filterNot { it.card.id in deletedIds }
    val pending = if (quickBatch == null) available else available.filter { it.key in quickBatch }
    val question = pending.find { it.card.id == currentId && it.turn == turn && it.direction == side }
    if (finished) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ui_review_complete), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ui_ratings_saved, completed))
            val blocked = available.isNotEmpty() && nextStudyQuestion(available, recentWords) == null
            Text(if (blocked) pluralStringResource(R.plurals.questions_deferred, available.size, available.size)
                else pluralStringResource(R.plurals.questions_available, available.size, available.size))
            if (available.isEmpty()) {
                Text(stringResource(R.string.ui_no_questions_due))
                queue.nextDue?.let { Text("Next review: ${formatReviewDate(it)}") }
            } else if (blocked) {
                Text(stringResource(R.string.ui_remaining_words_later))
            } else {
                Button(onClick = {
                    currentId = null
                    finished = false
                    onContinue()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ui_continue_review))
                }
            }
            Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_back_to_home)) }
        }
    } else if (question == null) {
        // Advance only once Room has published the committed turn.
        LaunchedEffect(currentId, side, turn, pending, schedules) {
            if (currentId !in deletedIds && schedules.any {
                    it.senseId == currentId && it.direction == side && it.turn > turn
                }) {
                completed++
                recentWords = ArrayList(recordStudyRating(currentWord, recentWords))
            }
            val next = if (quickBatch != null && completed >= quickTotal) null
                else nextStudyQuestion(pending, recentWords)
            if (next == null) finished = true
            else {
                currentId = next.card.id
                currentWord = next.word
                side = next.direction
                turn = next.turn
            }
        }
    } else {
        val remaining = if (quickBatch == null) pending.size else {
            // Keep the displayed question when an edit changes its English term.
            val afterCurrent = recordStudyRating(question.word, recentWords)
            val rest = planStudyQuestions(pending.filterNot { it.key == question.key },
                afterCurrent, limit = (quickTotal - completed - 1).coerceAtLeast(0))
            (1 + rest.size).also { reachable -> SideEffect { quickTotal = completed + reachable } }
        }
        key(question.card.id, question.direction, turn) {
            StudyQuestion(question.card, question.direction, question.turn, dao, clock, settings, cardTools, completed, remaining,
                onDelete = { id ->
                    onDelete(id)
                    deletedIds = ArrayList(deletedIds + id)
                }
            ) { rating, revealedAt ->
                currentWord = question.word
                dao.rate(question.card.id, question.direction, question.turn, rating, clock.millis(), settings, revealedAt)
            }
        }
    }
}

@Composable
private fun StudyQuestion(
    card: Card,
    direction: Direction,
    turn: Int,
    dao: ReviewDao,
    clock: Clock,
    settings: ReviewSettings,
    cardTools: @Composable (Card) -> Unit,
    completed: Int, remaining: Int,
    onDelete: suspend (String) -> Unit,
    onRate: suspend (Rating, Long) -> Unit
) {
    var revealedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spanishFirst = direction == Direction.ES_EN
    val at = revealedAt
    val previewAt = remember(clock) { clock.millis() }
    val intervalAt = at ?: previewAt
    val previous by produceState<ReviewEvent?>(null, dao, card.id, direction, turn) {
        if (turn > 0) value = dao.latest(card.id, direction)
    }
    val intervals = remember(previous, turn, settings, intervalAt) {
        if (turn > 0 && previous?.turn != turn) null
        else reviewChoices.associate { (rating, _) ->
            rating to formatReviewInterval(Fsrs.rate(previous?.memory, rating, intervalAt, settings).dueAt - intervalAt)
        }
    }
    val loadingIntervals = stringResource(R.string.ui_loading_intervals)
    StudyPresentation(
        card, spanishFirst, revealed = at != null,
        caption = "${direction.labelFor(languageName(card.language))} · ${completed + 1} of ${completed + remaining}",
        onReveal = { revealedAt = clock.millis() }, cardTools = cardTools,
        onDelete = onDelete, ratingPending = saving
    ) {
        if (at != null && failed) {
            Text(stringResource(R.string.ui_could_not_save_tap_a_rating_to_retry), color = MaterialTheme.colorScheme.error)
        }
        for (pair in reviewChoices.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((rating, label) in pair) {
                    val submit = {
                        if (at != null && intervals != null && !saving) {
                            saving = true
                            failed = false
                            scope.launch {
                                try {
                                    onRate(rating, at)
                                } catch (_: SQLiteException) {
                                    failed = true
                                    saving = false
                                }
                            }
                        }
                    }
                    val modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                    val caption: @Composable RowScope.() -> Unit = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, textAlign = TextAlign.Center)
                            Text(intervals?.getValue(rating) ?: "…",
                                modifier = if (intervals == null) Modifier.semantics { contentDescription = loadingIntervals } else Modifier,
                                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        }
                    }
                    OutlinedButton(onClick = submit, modifier = modifier, enabled = at != null && intervals != null && !saving,
                        contentPadding = PaddingValues(8.dp), content = caption)
                }
            }
        }
    }
}

internal fun formatReviewDate(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, java.util.Locale.UK).format(Date(at))
