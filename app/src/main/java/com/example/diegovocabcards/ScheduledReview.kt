package com.example.diegovocabcards

import android.database.sqlite.SQLiteException
import androidx.compose.foundation.layout.*
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
import com.example.diegovocabcards.data.ReviewDao
import com.example.diegovocabcards.data.ReviewEvent
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.ReviewSettings
import com.example.diegovocabcards.review.formatReviewInterval
import com.example.diegovocabcards.review.studyQueue
import io.github.openspacedrepetition.Rating
import java.time.Clock
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ReviewPage { Choose, Study, Finished, Manual }

internal val reviewChoices = listOf(
    Rating.AGAIN to "Otra vez", Rating.HARD to "Difícil",
    Rating.GOOD to "Bien", Rating.EASY to "Fácil"
)

@Composable
internal fun ScheduledReview(
    cards: List<Card>,
    dao: ReviewDao,
    clock: Clock = Clock.systemUTC(),
    settings: ReviewSettings = ReviewSettings(),
    onAdd: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(ReviewPage.Choose) }
    if (page == ReviewPage.Manual) {
        TextButton(onClick = { page = ReviewPage.Choose }) { Text("Volver al repaso programado") }
        Practice(cards, onAdd)
        return
    }
    if (cards.isEmpty()) {
        Text("Agregá una tarjeta para empezar a repasar.")
        Button(onClick = onAdd) { Text("Añadir tarjeta") }
        return
    }
    val flow = remember(dao) { dao.schedules() }
    val schedules = flow.collectAsState(initial = null).value
    if (schedules == null) {
        Text("Cargando repasos…")
        return
    }
    var direction by rememberSaveable { mutableStateOf(Direction.ES_EN) }
    var now by remember { mutableLongStateOf(clock.millis()) }
    val queues = remember(cards, schedules, now) {
        Direction.entries.associateWith { studyQueue(cards, schedules, it, now) }
    }
    val queue = queues.getValue(direction)
    val wakeAt = queues.values.mapNotNull { it.nextDue }.minOrNull()
    LaunchedEffect(wakeAt, now, clock) {
        // Also recheck wall-clock changes (for example after the device wakes).
        delay(((wakeAt ?: Long.MAX_VALUE) - clock.millis()).coerceIn(1, 60_000))
        now = clock.millis()
    }
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var turn by rememberSaveable { mutableIntStateOf(0) }
    var completed by rememberSaveable { mutableIntStateOf(0) }
    fun showNext() {
        val next = queue.ready.firstOrNull()
        if (next == null) page = ReviewPage.Finished
        else {
            currentId = next.card.id
            turn = next.turn
            page = ReviewPage.Study
        }
    }
    when (page) {
        ReviewPage.Choose -> {
            Text("Repaso programado", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.selectableGroup()) {
                for ((option, counts) in queues) {
                    Row(Modifier.fillMaxWidth().selectable(selected = direction == option,
                        role = Role.RadioButton, onClick = { direction = option }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = direction == option, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(option.label)
                            Text("Nuevas: ${counts.newCount} · Pendientes: ${counts.dueCount}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (queue.ready.isEmpty()) NextReview(queue.nextDue)
            Button(enabled = queue.ready.isNotEmpty(), onClick = { completed = 0; showNext() }) {
                Text("Iniciar repaso")
            }
            OutlinedButton(onClick = { page = ReviewPage.Manual }) { Text("Práctica manual sin calificar") }
        }
        ReviewPage.Study -> {
            val question = queue.ready.find { it.card.id == currentId && it.turn == turn }
            if (question == null) {
                // Room has published the committed turn. Only then change the question.
                LaunchedEffect(currentId, turn) {
                    if (schedules.any { it.senseId == currentId && it.direction == direction && it.turn > turn }) completed++
                    showNext()
                }
            } else {
                key(question.card.id, turn) {
                    StudyQuestion(question.card, direction, question.turn, dao, clock, settings) { rating, revealedAt ->
                        dao.rate(question.card.id, direction, question.turn, rating, clock.millis(), settings, revealedAt)
                        now = clock.millis()
                    }
                }
            }
        }
        ReviewPage.Finished -> {
            Text("Repaso terminado", style = MaterialTheme.typography.titleLarge)
            Text("Calificaciones guardadas: $completed")
            if (queue.ready.isEmpty()) NextReview(queue.nextDue)
            else Text("Ya hay tarjetas disponibles en este sentido.")
            Button(onClick = { page = ReviewPage.Choose }) { Text("Volver a elegir sentido") }
        }
        ReviewPage.Manual -> Unit
    }
}

@Composable
private fun NextReview(nextDue: Long?) {
    Text("No hay tarjetas pendientes en este sentido.")
    nextDue?.let {
        Text("Próximo repaso: ${formatReviewDate(it)}")
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
    Text(direction.label, style = MaterialTheme.typography.labelLarge)
    Text(if (spanishFirst) card.spanish else card.english, style = MaterialTheme.typography.headlineMedium)
    val at = revealedAt
    if (at == null) {
        Button(onClick = { revealedAt = clock.millis() }) { Text("Mostrar respuesta") }
        return
    }
    Text(if (spanishFirst) card.english else card.spanish, style = MaterialTheme.typography.titleLarge)
    if (card.example.isNotBlank()) Text(card.example)
    if (failed) Text("No se pudo guardar. Tocá la calificación para reintentar.", color = MaterialTheme.colorScheme.error)
    val previous by produceState<ReviewEvent?>(null, dao, card.id, direction, turn) {
        if (turn > 0) value = dao.latest(card.id, direction)
    }
    if (turn > 0 && previous?.turn != turn) {
        Text("Cargando intervalos…")
        return
    }
    val intervals = remember(previous, settings, at) {
        reviewChoices.associate { (rating, _) ->
            rating to formatReviewInterval(Fsrs.rate(previous?.memory, rating, at, settings).dueAt - at)
        }
    }
    for (pair in reviewChoices.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((rating, label) in pair) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = !saving, onClick = {
                    if (!saving) {
                        saving = true
                        failed = false
                        scope.launch {
                            try {
                                onRate(rating, at)
                            } catch (failure: SQLiteException) {
                                failed = true
                            } finally {
                                saving = false
                            }
                        }
                    }
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label)
                        Text(intervals.getValue(rating), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

internal fun formatReviewDate(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(at))
