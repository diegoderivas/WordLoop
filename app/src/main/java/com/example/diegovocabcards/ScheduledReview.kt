package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val queues = remember(cards, schedules, now) {
        if (cards == null || schedules == null) null
        else (listOf(null) + Direction.entries).associateWith { studyQueue(cards, schedules, it, now) }
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
    onStart: (Direction?) -> Unit
) {
    if (queues == null) {
        Text(stringResource(R.string.ui_loading_reviews))
        return
    }
    val mixed = queues.getValue(null)
    Card(colors = CardDefaults.cardColors(containerColor = if (home) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (home) "Today’s review" else "Today", style = MaterialTheme.typography.titleLarge)
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.questions, mixed.ready.size, mixed.ready.size))
            if (home) for (side in listOf(Direction.EN_ES, Direction.ES_EN)) {
                SummaryRow(side.labelFor(LocalStudyCards.current.translationLabel()), queues.getValue(side).ready.size)
            } else {
                SummaryRow("Learning", mixed.ready.count { it.learning })
                SummaryRow("Due for review", mixed.dueCount - mixed.ready.count { it.learning })
                SummaryRow("New", mixed.newCount)
            }
            Button(onClick = { onStart(null) }, enabled = mixed.ready.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                colors = if (home) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary) else ButtonDefaults.buttonColors()) { Text(stringResource(R.string.ui_start_review)) }
        }
    }
    if (mixed.ready.isEmpty()) mixed.nextDue?.let { Text("Next review: ${formatReviewDate(it)}") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.ui_advanced_options)) }
    if (advanced) for (direction in Direction.entries) {
        val queue = queues.getValue(direction)
        OutlinedButton(onClick = { onStart(direction) }, modifier = Modifier.fillMaxWidth(),
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
    onHome: () -> Unit
) {
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var turn by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var side by rememberSaveable { mutableStateOf(Direction.ES_EN) }
    val question = queue.ready.find { it.card.id == currentId && it.turn == turn && it.direction == side }
    if (finished) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ui_review_complete), style = MaterialTheme.typography.headlineSmall)
            Text("Ratings saved: $completed")
            if (queue.ready.isEmpty()) {
                Text(stringResource(R.string.ui_no_questions_due))
                queue.nextDue?.let { Text("Next review: ${formatReviewDate(it)}") }
            } else Text(stringResource(R.string.ui_more_questions_are_available))
            Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_back_to_home)) }
        }
    } else if (question == null) {
        // Advance only once Room has published the committed turn.
        LaunchedEffect(currentId, side, turn, queue, schedules) {
            if (schedules.any { it.senseId == currentId && it.direction == side && it.turn > turn }) completed++
            val next = queue.nextAfter(currentId)
            if (next == null) finished = true
            else {
                currentId = next.card.id
                side = next.direction
                turn = next.turn
            }
        }
    } else {
        key(question.card.id, question.direction, turn) {
            StudyQuestion(question.card, question.direction, question.turn, dao, clock, settings, cardTools, completed, queue.ready.size) { rating, revealedAt ->
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
    onRate: suspend (Rating, Long) -> Unit
) {
    var revealedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spanishFirst = direction == Direction.ES_EN
    val at = revealedAt
    StudyPresentation(
        card, spanishFirst, revealed = at != null,
        caption = "${direction.labelFor(languageName(card.language))} · ${completed + 1} of ${completed + remaining}",
        onReveal = { revealedAt = clock.millis() }, cardTools = cardTools
    ) {
        if (at != null) {
            if (failed) Text(stringResource(R.string.ui_could_not_save_tap_a_rating_to_retry), color = MaterialTheme.colorScheme.error)
            val previous by produceState<ReviewEvent?>(null, dao, card.id, direction, turn) {
                if (turn > 0) value = dao.latest(card.id, direction)
            }
            if (turn > 0 && previous?.turn != turn) {
                Text(stringResource(R.string.ui_loading_intervals))
                return@StudyPresentation
            }
            val intervals = remember(previous, settings, at) {
                reviewChoices.associate { (rating, _) ->
                    rating to formatReviewInterval(Fsrs.rate(previous?.memory, rating, at, settings).dueAt - at)
                }
            }
            for (pair in reviewChoices.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((rating, label) in pair) {
                        val submit = {
                            if (!saving) {
                                saving = true
                                failed = false
                                scope.launch {
                                    try {
                                        onRate(rating, at)
                                    } catch (_: SQLiteException) {
                                        failed = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        }
                        val modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                        val caption: @Composable RowScope.() -> Unit = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, textAlign = TextAlign.Center)
                                Text(intervals.getValue(rating), style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center)
                            }
                        }
                        val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        if (rating == Rating.GOOD) {
                            Button(onClick = submit, modifier = modifier, enabled = !saving,
                                contentPadding = padding, content = caption)
                        } else {
                            OutlinedButton(onClick = submit, modifier = modifier, enabled = !saving,
                                contentPadding = padding, content = caption)
                        }
                    }
                }
            }
        }
    }
}

internal fun formatReviewDate(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, java.util.Locale.UK).format(Date(at))
