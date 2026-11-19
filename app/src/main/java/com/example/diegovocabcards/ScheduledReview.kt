package com.example.diegovocabcards

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
    Rating.AGAIN to "Otra vez", Rating.HARD to "Difícil",
    Rating.GOOD to "Bien", Rating.EASY to "Fácil"
)

@Composable
internal fun rememberReviewQueues(
    cards: List<Card>?,
    schedules: List<Schedule>?,
    clock: Clock
): Map<Direction, StudyQueue>? {
    var now by remember(clock) { mutableLongStateOf(clock.millis()) }
    val queues = remember(cards, schedules, now) {
        if (cards == null || schedules == null) null
        else Direction.entries.associateWith { studyQueue(cards, schedules, it, now) }
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
    queues: Map<Direction, StudyQueue>?,
    home: Boolean,
    onStart: (Direction) -> Unit
) {
    Text(if (home) "Para repasar" else "Elegí el sentido", style = MaterialTheme.typography.headlineSmall)
    if (queues == null) {
        Text("Cargando repasos…")
        return
    }
    for ((direction, queue) in queues) {
        val colors = if (home && direction == Direction.ES_EN) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.outlinedCardColors()
        val enabled = queue.ready.isNotEmpty()
        val details: @Composable () -> Unit = {
            Text(direction.label, style = MaterialTheme.typography.titleMedium)
            Text("Pendientes: ${queue.dueCount} · Nuevas: ${queue.newCount}",
                style = MaterialTheme.typography.bodyMedium)
            if (!enabled) queue.nextDue?.let { Text("Próximo repaso: ${formatReviewDate(it)}") }
        }
        if (home) {
            OutlinedCard(Modifier.fillMaxWidth(), colors = colors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    details()
                    val label = if (direction == Direction.ES_EN) "Repasar en inglés" else "Repasar en español"
                    if (direction == Direction.ES_EN) {
                        Button(onClick = { onStart(direction) }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { onStart(direction) }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                            Text(label)
                        }
                    }
                }
            }
        } else {
            OutlinedCard(onClick = { onStart(direction) }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { details() }
            }
        }
    }
}

@Composable
internal fun ScheduledReview(
    direction: Direction,
    queue: StudyQueue,
    schedules: List<Schedule>,
    dao: ReviewDao,
    clock: Clock,
    settings: ReviewSettings,
    onHome: () -> Unit
) {
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var turn by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    val question = queue.ready.find { it.card.id == currentId && it.turn == turn }
    if (finished) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Repaso terminado", style = MaterialTheme.typography.headlineSmall)
            Text("Calificaciones guardadas: $completed")
            if (queue.ready.isEmpty()) {
                Text("No hay tarjetas pendientes en este sentido.")
                queue.nextDue?.let { Text("Próximo repaso: ${formatReviewDate(it)}") }
            } else Text("Ya hay tarjetas disponibles en este sentido.")
            Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Volver a Inicio") }
        }
    } else if (question == null) {
        // Advance only once Room has published the committed turn.
        LaunchedEffect(currentId, turn, queue, schedules) {
            if (schedules.any { it.senseId == currentId && it.direction == direction && it.turn > turn }) completed++
            val next = queue.ready.firstOrNull()
            if (next == null) finished = true
            else {
                currentId = next.card.id
                turn = next.turn
            }
        }
    } else {
        key(question.card.id, turn) {
            StudyQuestion(question.card, direction, question.turn, dao, clock, settings) { rating, revealedAt ->
                dao.rate(question.card.id, direction, question.turn, rating, clock.millis(), settings, revealedAt)
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
        caption = "${direction.label} · ${if (turn == 0) "Nueva" else "Pendiente"}",
        onReveal = { revealedAt = clock.millis() }
    ) {
        if (at != null) {
            if (failed) Text("No se pudo guardar. Tocá la calificación para reintentar.", color = MaterialTheme.colorScheme.error)
            val previous by produceState<ReviewEvent?>(null, dao, card.id, direction, turn) {
                if (turn > 0) value = dao.latest(card.id, direction)
            }
            if (turn > 0 && previous?.turn != turn) {
                Text("Cargando intervalos…")
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
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(at))
