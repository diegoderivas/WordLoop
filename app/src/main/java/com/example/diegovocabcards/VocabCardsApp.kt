package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.example.diegovocabcards.data.Card
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import java.time.Clock
import com.example.diegovocabcards.review.Direction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.review.ReviewSettings

internal val LocalStudyCards = compositionLocalOf { emptyList<Card>() }
internal val LocalAutomaticSpeech = compositionLocalOf { true }

private enum class Screen(val title: String) {
    Home("Home"), Sample("Sample review"), Library("Library"), Add("Add card"),
    Review("Review"), Study("Review"), Manual("Manual practice"), Settings("Settings"), ReviewOptions("Review intervals"), Edit("Edit card")
}

private val mainTabs = listOf(Screen.Home to R.drawable.nav_home, Screen.Library to R.drawable.nav_library,
    Screen.Review to R.drawable.nav_review)

@Composable
internal fun VocabCardsApp(
    mode: Appearance,
    model: VocabularyModel,
    reviewSettings: ReviewSettings = ReviewSettings(),
    onReviewSettingsChange: (ReviewSettings) -> Unit = {},
    clock: Clock = Clock.systemUTC(),
    guideGenerator: UsageNoteGenerator = NanoUsageNotes,
    automaticSpeech: Boolean = true,
    backupDefaults: com.example.diegovocabcards.data.LibrarySettings = com.example.diegovocabcards.data.LibrarySettings(),
    onVoiceChange: (String) -> Unit = {},
    onAutomaticSpeech: (Boolean) -> Unit = {},
    dictionaryLookup: (suspend (String) -> List<Suggestion>)? = null,
    sharedText: String? = null,
    onSharedTextHandled: () -> Unit = {},
    onModeChange: (Appearance) -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val exitGuard = remember { EditorExitGuard() }
    val home = screen == Screen.Home
    val rows by model.cards.collectAsState(initial = null)
    val scheduleFlow = remember(model) { model.reviews.schedules() }
    val schedules by scheduleFlow.collectAsState(initial = null)
    val queues = rememberReviewQueues(rows, schedules, clock)
    var direction by rememberSaveable { mutableStateOf<Direction?>(null) }
    var libraryTag by rememberSaveable { mutableStateOf<String?>(null) }
    var manualTag by rememberSaveable { mutableStateOf<String?>(null) }
    var studyOrigin by rememberSaveable { mutableStateOf(Screen.Home) }
    var settingsOrigin by rememberSaveable { mutableStateOf(Screen.Home) }
    val mainScreen = mainTabs.any { it.first == screen }
    var quickBatch by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var studySession by rememberSaveable { mutableIntStateOf(0) }
    fun startStudy(selected: Direction?, quick: Boolean) {
        quickBatch = if (quick) ArrayList(queues?.getValue(selected)?.quickBatch().orEmpty()) else null
        studySession++
        direction = selected
        studyOrigin = screen
        screen = Screen.Study
    }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    val sessionState = rememberSaveableStateHolder()
    var editOrigin by rememberSaveable { mutableStateOf(Screen.Library) }
    fun editCard(id: String) {
        editId = id
        editOrigin = screen
        screen = Screen.Edit
    }
    val studyTools: @Composable (Card) -> Unit = { card ->
        ReviewCardTools(card, model, guideGenerator) { editCard(card.id) }
    }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }
    val knownTags = remember(rows) {
        rows.orEmpty().flatMap { it.tags }.distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    var draftNumber by rememberSaveable { mutableIntStateOf(0) }
    var seedEnglish by rememberSaveable { mutableStateOf("") }
    var seedTags by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var draftLanguage by rememberSaveable { mutableStateOf(backupDefaults.language) }
    var sampleLanguage by rememberSaveable { mutableStateOf(backupDefaults.language) }
    var parent by rememberSaveable { mutableStateOf(Screen.Home) }
    fun newDraft(english: String, tags: List<String>?, from: Screen, language: String = backupDefaults.language) {
        draftLanguage = language
        seedEnglish = english
        seedTags = tags?.let { ArrayList(it) }
        parent = from
        draftNumber++
        screen = Screen.Add
    }
    fun add(from: Screen) = newDraft("", null, from)
    val backTo = when (screen) {
        Screen.Edit -> editOrigin
        Screen.Add -> parent
        Screen.ReviewOptions -> Screen.Settings
        Screen.Settings -> settingsOrigin
        Screen.Study -> studyOrigin
        Screen.Manual -> if (manualTag != null) Screen.Library else Screen.Review
        else -> Screen.Home
    }
    val leave = {
        if (!model.saving && model.saved == null) {
            if (screen == Screen.Manual || screen == Screen.Study) sessionState.removeState(screen.name)
            screen = backTo
        }
    }
    val back = {
        if (!model.saving && model.saved == null) {
            if (screen == Screen.Add || screen == Screen.Edit) {
                exitGuard.request?.invoke(leave) ?: leave()
            } else leave()
        }
    }
    val isEditor = screen == Screen.Add || screen == Screen.Edit
    LaunchedEffect(sharedText, isEditor, model.saving, model.saved) {
        if (sharedText != null && !isEditor && !model.saving && model.saved == null) {
            newDraft(sharedText, null, screen)
            onSharedTextHandled()
        }
    }
    sharedText?.takeIf { isEditor }?.let { text ->
        AlertDialog(onDismissRequest = onSharedTextHandled,
            title = { Text("Open a draft from shared text?") },
            text = { Text(text) },
            confirmButton = { TextButton(enabled = !model.saving, onClick = {
                val open = {
                    newDraft(text, null, if (screen in listOf(Screen.Add, Screen.Edit)) backTo else screen)
                    onSharedTextHandled()
                }
                exitGuard.request?.invoke(open) ?: open()
                onSharedTextHandled()
            }) { Text("Open draft") } },
            dismissButton = { TextButton(onClick = onSharedTextHandled) { Text("Cancel") } }
        )
    }
    BackHandler(enabled = !home, onBack = back)
    LaunchedEffect(model.saved) {
        model.saved?.let { result ->
            val card = result.card
            when (result.next) {
                AfterSave.Library -> {
                    libraryTag = null
                    highlighted = card.id
                    screen = Screen.Library
                }
                AfterSave.Study -> screen = editOrigin
                AfterSave.AnotherCard -> newDraft("", card.tags, Screen.Library, card.language)
                AfterSave.AnotherSense -> newDraft(card.english, card.tags, Screen.Library, card.language)
            }
            model.acknowledgeSave()
        }
    }

    CompositionLocalProvider(LocalAutomaticSpeech provides automaticSpeech, LocalStudyCards provides rows.orEmpty()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    ).heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!mainScreen) {
                        IconButton(onClick = back, enabled = !model.saving) {
                            Icon(painterResource(R.drawable.nav_back), stringResource(R.string.ui_back))
                        }
                    }
                    Text(screen.title,
                        Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    if (mainScreen) {
                        IconButton(onClick = { settingsOrigin = screen; screen = Screen.Settings }) {
                            Icon(painterResource(R.drawable.nav_settings), stringResource(R.string.ui_settings))
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (mainScreen) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    for ((tab, icon) in mainTabs) {
                        NavigationBarItem(selected = screen == tab, onClick = { screen = tab },
                            icon = { Icon(painterResource(icon), contentDescription = null) },
                            label = { Text(tab.title) })
                    }
                }
            }
        }
    ) { insets ->
        key(screen, draftNumber, if (screen == Screen.Edit) editId else null) {
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
                    .then(if (screen in listOf(Screen.Library, Screen.Study, Screen.Manual, Screen.Sample, Screen.Add, Screen.Edit)) Modifier
                        else Modifier.verticalScroll(scroll))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val cards = rows
                if (cards == null && screen !in listOf(Screen.Home, Screen.Settings, Screen.ReviewOptions)) {
                    Text(stringResource(R.string.ui_loading_library))
                    return@Column
                }
                when (screen) {
                    Screen.Home -> {
                        if (cards?.isEmpty() == true) {
                            FirstCardOptions(backupDefaults.language,
                                onLanguage = { code -> model.configure(backupDefaults) { it.copy(language = code) } },
                                onAdd = { newDraft("", null, Screen.Home, it) },
                                onSample = { sampleLanguage = it; screen = Screen.Sample })
                        } else {
                            ReviewDirections(queues, home = true, onStart = ::startStudy)
                            OutlinedButton(onClick = { add(Screen.Home) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.ui_add_card))
                            }
                            if (cards?.isNotEmpty() == true) StudyActivity(model.reviews, clock)
                        }
                    }
                    Screen.Library -> sessionState.SaveableStateProvider(Screen.Library.name) {
                        var progressId by rememberSaveable { mutableStateOf<String?>(null) }
                        progressId?.let { ReviewDetails(it, model.reviews) { progressId = null } }
                        Library(cards.orEmpty(), knownTags, highlighted, model = model, onAdd = { add(Screen.Library) },
                        schedules = schedules.orEmpty(), onAnother = { newDraft(it.english, it.tags, Screen.Library, it.language) }, onEdit = ::editCard, onDelete = model::deleteSense, onProgress = { progressId = it },
                            filter = libraryTag, onFilter = { libraryTag = it },
                            onPractice = { manualTag = it; screen = Screen.Manual })
                    }
                    Screen.Add -> CardEditor(null, seedEnglish, seedTags ?: cards?.firstOrNull()?.tags.orEmpty(), knownTags, model, language = draftLanguage, lookup = dictionaryLookup, usageNotes = guideGenerator, exitGuard = exitGuard,
                        onOpenExisting = { editId = it; editOrigin = Screen.Library; screen = Screen.Edit }) {}
                    Screen.Edit -> cards?.find { it.id == editId }?.let { original ->
                        var showProgress by rememberSaveable(original.id) { mutableStateOf(false) }
                        TextButton(onClick = { showProgress = true }) { Text(stringResource(R.string.ui_review_history)) }
                        if (showProgress) ReviewDetails(original.id, model.reviews) { showProgress = false }
                        CardEditor(original, original.english, original.tags, knownTags, model, language = original.language, lookup = dictionaryLookup, usageNotes = guideGenerator, exitGuard = exitGuard,
                            onOpenExisting = { editId = it },
                            fromStudy = editOrigin == Screen.Study || editOrigin == Screen.Manual) {
                            newDraft(it.english, it.tags, Screen.Library)
                        }
                    }
                    Screen.Review -> {
                        ReviewDirections(queues, home = false, onStart = ::startStudy)
                        if (cards.orEmpty().isEmpty()) {
                            TextButton(onClick = { add(Screen.Review) }) { Text(stringResource(R.string.ui_add_card)) }
                        } else {
                            TextButton(onClick = { manualTag = null; screen = Screen.Manual }) {
                                Text(stringResource(R.string.ui_manual_practice))
                            }
                        }
                    }
                    Screen.Sample -> SampleReview(sampleLanguage)
                    Screen.Manual -> sessionState.SaveableStateProvider(Screen.Manual.name) {
                        val selectedCards = cards.orEmpty().filter { card ->
                            manualTag == null || card.tags.any { it.equals(manualTag, true) }
                        }
                        Practice(selectedCards, studyTools, onDelete = model::deleteSense) { add(Screen.Manual) }
                    }
                    Screen.Study -> {
                        val queue = queues?.get(direction)
                        val loadedSchedules = schedules
                        if (queue != null && loadedSchedules != null) {
                            sessionState.SaveableStateProvider(Screen.Study.name) {
                                key(studySession) {
                                    ScheduledReview(queue, loadedSchedules, model.reviews, clock, reviewSettings, studyTools,
                                        quickBatch = quickBatch, onDelete = model::deleteSense,
                                        onContinue = { quickBatch = null }
                                    ) {
                                        sessionState.removeState(Screen.Study.name)
                                        screen = Screen.Home
                                    }
                                }
                            }
                        } else Text(stringResource(R.string.ui_loading_reviews))
                    }
                    Screen.Settings -> {
                        Section {
                            Text("Translation language", style = MaterialTheme.typography.titleMedium)
                            TagMenu(languageName(backupDefaults.language), listOf("Spanish", "Japanese")) { name ->
                                model.configure(backupDefaults) { it.copy(language = if (name == "Japanese") "ja" else "es") }
                            }
                        }
                        Section { AppearanceOptions(mode, onModeChange) }
                        Section {
                        Text(stringResource(R.string.ui_pronunciation), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth().toggleable(value = automaticSpeech, role = Role.Switch,
                            onValueChange = onAutomaticSpeech).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ui_auto_play_audio), Modifier.weight(1f))
                            Switch(automaticSpeech, onCheckedChange = null)
                        }
                        VoiceOptions(backupDefaults.voice, onVoiceChange)
                        }
                        OutlinedButton(onClick = { screen = Screen.ReviewOptions }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_review_intervals)) }
                        Section { BackupOptions(model.backup, backupDefaults) }
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
}

@Composable
private fun AppearanceOptions(mode: Appearance, onModeChange: (Appearance) -> Unit) {
    Text(stringResource(R.string.ui_appearance), style = MaterialTheme.typography.titleMedium)
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
                    Appearance.System -> "System"
                    Appearance.Light -> "Light"
                    Appearance.Dark -> "Dark"
                })
            }
        }
    }
}
