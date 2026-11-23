package com.example.diegovocabcards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.ReviewDao
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.delay

@Composable
internal fun FirstCardOptions(language: String, onLanguage: (String) -> Unit, onAdd: (String) -> Unit, onSample: (String) -> Unit) {
    var selected by rememberSaveable(language) { mutableStateOf(language) }
    Section {
        Text("Welcome to WordLoop", style = MaterialTheme.typography.titleLarge)
        Text("Save words you find and practise them in both directions.")
        Text("Translation language", style = MaterialTheme.typography.titleSmall)
        TagMenu(languageName(selected), listOf("Spanish", "Japanese").filterNot { it == languageName(selected) }) {
            selected = if (it == "Japanese") "ja" else "es"
            onLanguage(selected)
        }
        Button(onClick = { onAdd(selected) }, modifier = Modifier.fillMaxWidth()) { Text("Add card") }
        OutlinedButton(onClick = { onSample(selected) }, modifier = Modifier.fillMaxWidth()) { Text("Try a sample review") }
    }
}

@Composable
internal fun SampleReview(language: String) {
    val sample = remember(language) {
        val japanese = language == "ja"
        listOf(
            Card("sample-mist", "mist", if (japanese) "霧" else "niebla",
                "A light mist covered the lake.", emptyList(), language = language),
            Card("sample-bloom", "bloom", if (japanese) "咲く" else "florecer",
                "These flowers bloom in spring.", emptyList(), language = language),
            Card("sample-shelter", "shelter", if (japanese) "避難所" else "refugio",
                "They found shelter from the rain.", emptyList(), language = language)
        )
    }
    Text("Sample cards are kept separate from your library.", style = MaterialTheme.typography.bodySmall)
    Practice(sample) {}
}

@Composable
internal fun StudyActivity(dao: ReviewDao, clock: Clock) {
    val zone = ZoneId.systemDefault()
    var today by remember(clock, zone) { mutableStateOf(clock.instant().atZone(zone).toLocalDate()) }
    LaunchedEffect(clock, zone) {
        while (true) {
            today = clock.instant().atZone(zone).toLocalDate()
            delay(60_000)
        }
    }
    val weekStart = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrow = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val ratings by remember(dao, weekStart) { dao.recentRatingsSince(weekStart) }.collectAsState(emptyList())
    if (ratings.isNotEmpty()) Section {
        Text("Study activity", style = MaterialTheme.typography.titleMedium)
        SummaryRow("Answered today", ratings.count { it >= dayStart && it < tomorrow })
        SummaryRow("Answered in the last 7 days", ratings.count { it < tomorrow })
    }
}
