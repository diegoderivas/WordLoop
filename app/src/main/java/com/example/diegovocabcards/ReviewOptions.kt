package com.example.diegovocabcards

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.ReviewDao
import com.example.diegovocabcards.data.ReviewEvent
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.ReviewSettings
import com.example.diegovocabcards.review.formatReviewInterval
import io.github.openspacedrepetition.State

internal fun SharedPreferences.readReviewSettings(): ReviewSettings {
    val defaults = ReviewSettings()
    return ReviewSettings(
        getInt("retention", defaults.retention),
        getInt("first_learning", defaults.firstLearningMinutes),
        getInt("next_learning", defaults.nextLearningMinutes),
        getInt("relearning", defaults.relearningMinutes)
    ).takeIf { it.isValid } ?: defaults
}

internal fun SharedPreferences.writeReviewSettings(settings: ReviewSettings) {
    edit()
        .putInt("retention", settings.retention)
        .putInt("first_learning", settings.firstLearningMinutes)
        .putInt("next_learning", settings.nextLearningMinutes)
        .putInt("relearning", settings.relearningMinutes)
        .apply()
}

@Composable
internal fun ReviewOptions(current: ReviewSettings, onSave: (ReviewSettings) -> Unit) {
    var retention by rememberSaveable { mutableStateOf(current.retention.toString()) }
    var first by rememberSaveable { mutableStateOf(current.firstLearningMinutes.toString()) }
    var next by rememberSaveable { mutableStateOf(current.nextLearningMinutes.toString()) }
    var relearning by rememberSaveable { mutableStateOf(current.relearningMinutes.toString()) }
    val draft = ReviewSettings(
        retention.toIntOrNull() ?: 0,
        first.toIntOrNull() ?: 0,
        next.toIntOrNull() ?: 0,
        relearning.toIntOrNull() ?: 0
    )
    val stepsValid = draft.firstLearningMinutes in 1..1439 &&
        draft.nextLearningMinutes in 1..1439 && draft.firstLearningMinutes < draft.nextLearningMinutes
    ReviewNumberField("Retención objetivo (%)", retention, { retention = it },
        draft.retention !in 80..97, "80–97 %. Un objetivo más alto acorta los intervalos en días.")
    ReviewNumberField("Primer paso (min)", first, { first = it }, !stepsValid,
        "Entre 1 y 1439 minutos. El primer paso debe ser más corto que el segundo.")
    ReviewNumberField("Segundo paso (min)", next, { next = it }, !stepsValid)
    ReviewNumberField("Reaprendizaje (min)", relearning, { relearning = it },
        draft.relearningMinutes !in 1..1439, "Espera tras olvidar una tarjeta que ya estaba en repaso.")
    if (draft.isValid) {
        val intervals = remember(draft) {
            reviewChoices.map { (rating, label) ->
                "$label · ${formatReviewInterval(Fsrs.rate(null, rating, 0L, draft).dueAt)}"
            }
        }
        Text("Primera calificación de una tarjeta nueva", style = MaterialTheme.typography.titleSmall)
        Text(intervals.joinToString("\n"))
    }
    Text("Los ajustes se aplican al volver a calificar. Las fechas guardadas se conservan.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = draft.isValid && draft != current, onClick = { onSave(draft) }) { Text("Guardar") }
        TextButton(onClick = {
            val defaults = ReviewSettings()
            retention = defaults.retention.toString()
            first = defaults.firstLearningMinutes.toString()
            next = defaults.nextLearningMinutes.toString()
            relearning = defaults.relearningMinutes.toString()
        }) { Text("Valores de fábrica") }
    }
}

@Composable
private fun ReviewNumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    invalid: Boolean,
    help: String? = null
) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = invalid,
        supportingText = if (help == null) null else ({ Text(help) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
internal fun ReviewDetails(senseId: String, dao: ReviewDao, onClose: () -> Unit) {
    val events by produceState<List<Pair<Direction, List<ReviewEvent>>>?>(null, dao, senseId) {
        value = Direction.entries.map { it to dao.recent(senseId, it) }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Progreso de repaso") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val loaded = events
                if (loaded == null) Text("Cargando progreso…")
                else for ((direction, history) in loaded) {
                    val event = history.firstOrNull()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(direction.label, style = MaterialTheme.typography.titleSmall)
                        if (event == null) Text("Nueva")
                        else {
                            val memory = event.memory
                            val phase = when (memory.phase) {
                                State.LEARNING -> "Paso de aprendizaje"
                                State.REVIEW -> "Intervalo calculado por FSRS"
                                State.RELEARNING -> "Paso de reaprendizaje"
                            }
                            val grade = reviewChoices.first { it.first == event.rating }.second
                            Text(if (memory.dueAt <= System.currentTimeMillis()) "Pendiente · $phase" else "Programada · $phase")
                            Text("Última calificación: $grade · ${formatReviewDate(memory.reviewedAt)}")
                            Text("Próximo repaso: ${formatReviewDate(memory.dueAt)}")
                            Text("Historial reciente", style = MaterialTheme.typography.labelLarge)
                            for (item in history) {
                                val label = reviewChoices.first { it.first == item.rating }.second
                                Text("${formatReviewDate(item.memory.reviewedAt)} · $label")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } }
    )
}
