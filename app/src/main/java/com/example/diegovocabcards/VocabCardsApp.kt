package com.example.diegovocabcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.review.ReviewSettings

private enum class Screen(val title: String) {
    Home("Inicio"), Library("Biblioteca"), Add("Añadir tarjeta"),
    Review("Repasar"), Settings("Configuración"), ReviewOptions("Ajustes de repaso"), Edit("Editar tarjeta")
}

@Composable
internal fun VocabCardsApp(
    mode: Appearance,
    model: VocabularyModel,
    reviewSettings: ReviewSettings = ReviewSettings(),
    onReviewSettingsChange: (ReviewSettings) -> Unit = {},
    onModeChange: (Appearance) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val home = screen == Screen.Home
    val rows by model.cards.collectAsState(initial = null)
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }
    val knownTags = remember(rows) {
        rows.orEmpty().flatMap { it.tags }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var draftNumber by rememberSaveable { mutableIntStateOf(0) }
    var seedEnglish by rememberSaveable { mutableStateOf("") }
    var seedTags by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var parent by rememberSaveable { mutableStateOf(Screen.Home) }
    fun newDraft(english: String, tags: List<String>?, from: Screen) {
        seedEnglish = english
        seedTags = tags?.let { ArrayList(it) }
        parent = from
        draftNumber++
        screen = Screen.Add
    }
    fun add(from: Screen) = newDraft("", null, from)
    val backTo = when (screen) {
        Screen.Edit -> Screen.Library
        Screen.Add -> parent
        Screen.ReviewOptions -> Screen.Settings
        else -> Screen.Home
    }
    val back = {
        if (!model.saving && model.saved == null) screen = backTo
    }
    BackHandler(enabled = !home, onBack = back)
    LaunchedEffect(model.saved) {
        model.saved?.let { result ->
            val card = result.card
            when (result.next) {
                AfterSave.Library -> {
                    highlighted = card.id
                    screen = Screen.Library
                }
                AfterSave.AnotherCard -> newDraft("", card.tags, Screen.Library)
                AfterSave.AnotherSense -> newDraft(card.english, card.tags, Screen.Library)
            }
            model.acknowledgeSave()
        }
    }

    Scaffold { insets ->
        key(screen, draftNumber) {
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
                    .then(if (screen == Screen.Library) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (home) {
                    Text("DiegoVocabCards", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = back, enabled = !model.saving) {
                        Text("Volver a ${backTo.title}")
                    }
                }
                Text(screen.title, modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge)
                val cards = rows
                if (cards == null && screen !in listOf(Screen.Home, Screen.Settings, Screen.ReviewOptions)) {
                    Text("Cargando biblioteca…")
                    return@Column
                }
                when (screen) {
                    Screen.Home -> {
                        for (destination in listOf(Screen.Library, Screen.Add, Screen.Review, Screen.Settings)) {
                            OutlinedCard(onClick = { if (destination == Screen.Add) add(Screen.Home) else screen = destination }, modifier = Modifier.fillMaxWidth()) {
                                Text(destination.title, Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Screen.Library -> Library(cards.orEmpty(), knownTags, highlighted, onAdd = { add(Screen.Library) }) {
                        editId = it
                        screen = Screen.Edit
                    }
                    Screen.Add -> CardEditor(null, seedEnglish, seedTags ?: cards?.firstOrNull()?.tags.orEmpty(), knownTags, model) {}
                    Screen.Edit -> cards?.find { it.id == editId }?.let { original ->
                        var showProgress by rememberSaveable(original.id) { mutableStateOf(false) }
                        TextButton(onClick = { showProgress = true }) { Text("Progreso de repaso") }
                        if (showProgress) ReviewDetails(original.id, model.reviews) { showProgress = false }
                        CardEditor(original, original.english, original.tags, knownTags, model) {
                            newDraft(it.english, it.tags, Screen.Library)
                        }
                    }
                    Screen.Review -> ScheduledReview(cards.orEmpty(), model.reviews, settings = reviewSettings) {
                        add(Screen.Review)
                    }
                    Screen.Settings -> {
                        OutlinedButton(onClick = { screen = Screen.ReviewOptions }) { Text("Ajustes de repaso") }
                        AppearanceOptions(mode, onModeChange)
                    }
                    Screen.ReviewOptions -> ReviewOptions(reviewSettings) {
                        onReviewSettingsChange(it)
                        screen = Screen.Settings
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceOptions(mode: Appearance, onModeChange: (Appearance) -> Unit) {
    Text("Apariencia", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        for (option in Appearance.entries) {
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = mode == option, role = Role.RadioButton,
                        onClick = { onModeChange(option) })
                    .heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Text(when (option) {
                    Appearance.System -> "Automático (seguir al teléfono)"
                    Appearance.Light -> "Claro"
                    Appearance.Dark -> "Oscuro"
                })
            }
        }
    }
}
