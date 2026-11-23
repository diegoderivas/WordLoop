package com.example.diegovocabcards

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme
import io.github.openspacedrepetition.Rating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WordLoopJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun japaneseCardSharesAHeadwordWithSpanishAndCanBeStudiedOffline() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db)
        val running = mutableStateOf(true)
        val guide = "Use bloom when a plant produces flowers."
        fun tap(text: String) = compose.tapControl(text)
        try {
            runBlocking { db.vocabulary().save("spanish", "bloom", "florecer", "", "", usageNote = guide) }
            compose.setContent {
                val settings by db.backup().observeSettings().collectAsState(initial = null)
                if (running.value) VocabCardsApp(Appearance.Light, model,
                    backupDefaults = settings ?: com.example.diegovocabcards.data.LibrarySettings(),
                    dictionaryLookup = { emptyList() }, guideGenerator = { _, _, _ -> UsageNoteResult.Unavailable }) {}
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            tap("Spanish")
            tap("Japanese")
            compose.waitUntil(5000) { runBlocking { db.backup().settings()?.language == "ja" } }
            compose.onNodeWithContentDescription("Back").performClick()
            tap("Add card")
            compose.onNodeWithText("Headword").performTextInput("bloom")
            compose.onNodeWithText("Japanese translation").performScrollTo().performTextInput("咲く")
            tap("Save card")
            compose.waitUntil(5000) { runBlocking { db.vocabulary().cards().first().size == 2 } }
            compose.onNodeWithText("Search English or translation").performTextInput("bloom")
            compose.onNodeWithText("咲く").assertExists()
            compose.onNodeWithText("florecer").assertExists()
            tap("Review")
            tap("Start review")
            repeat(4) {
                tap("Show answer")
                tap("Usage guide")
                compose.waitUntil(5000) { compose.onAllNodesWithText(guide).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(UNSUPPORTED_GUIDE).fetchSemanticsNodes().isNotEmpty() }
                if (compose.onAllNodesWithText(guide).fetchSemanticsNodes().isNotEmpty()) tap("Back to review") else tap("OK")
                tap("Easy")
            }
            compose.waitUntil(5000) { runBlocking { db.backup().events().size == 4 } }
            assertEquals(4, runBlocking { db.backup().events().map { it.senseId to it.direction }.distinct().size })
        } finally {
            compose.runOnIdle { running.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    @Test fun japaneseDraftCanSaveWhileTranslationModelIsMissing() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db)
        val running = mutableStateOf(true)
        val request = kotlinx.coroutines.CompletableDeferred<String?>()
        val called = kotlinx.coroutines.CompletableDeferred<String>()
        val meaning = Suggestion("To produce flowers.", "verb", "Test dictionary", language = "ja")
        try {
            compose.setContent {
                if (running.value) androidx.compose.foundation.layout.Column {
                    CardEditor(null, "bloom", emptyList(), emptyList(), model,
                        lookup = { listOf(meaning) }, language = "ja",
                        translate = { _, sense -> called.complete(sense.language); request.await() },
                        usageNotes = { _, _, _ -> UsageNoteResult.Unavailable },
                        nanoOptions = { _, _, _ -> emptyList() }, ipaSuggestion = { null }) {}
                }
            }
            compose.waitUntil(8000) { compose.onAllNodesWithText(meaning.definition).fetchSemanticsNodes().isNotEmpty() }
            compose.tapControl(meaning.definition)
            compose.waitUntil(5000) { called.isCompleted }
            assertEquals("ja", runBlocking { called.await() })
            compose.tapControl("Usage guide")
            compose.waitUntil(8000) { compose.onAllNodesWithText(UNSUPPORTED_GUIDE).fetchSemanticsNodes().isNotEmpty() }
            compose.tapControl("OK")
            compose.onNodeWithText("Japanese translation").performScrollTo().performTextInput("咲く")
            compose.tapControl("Save card")
            compose.waitUntil(5000) { model.saved != null }
            assertEquals("咲く", model.saved!!.card.spanish)
            assertEquals("ja", model.saved!!.card.language)
        } finally {
            request.complete(null)
            compose.runOnIdle { running.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    @Test fun lightJourney() = journey(false)
    @Test fun darkJourney() = journey(true)

    private fun journey(dark: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val mode = if (dark) "dark" else "light"
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db)
        val bloom = Suggestion("To produce flowers.", "verb", "Dictionary fixture", listOf("florecer"),
            listOf("The cherry trees bloom in spring."), listOf("flower"), usage = "intransitive",
            pronunciation = DictionaryPronunciation("/bluːm/", "Dictionary fixture"))
        val running = mutableStateOf(true)
        fun tap(value: String) = compose.tapControl(value)
        fun waitFor(value: String) = compose.waitUntil(10000) {
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
        fun shot(screen: String) { compose.waitForIdle(); captureEmulator("wordloop-$screen-$mode.png") }
        try {
            assertEquals("WordLoop", app.packageManager.getApplicationLabel(app.applicationInfo).toString())
            runBlocking {
                val dao = db.vocabulary()
                dao.save("1", "fire", "fuego", "", "core", senseCategories = "noun")
                dao.save("2", "fire", "despedir", "", "core", senseCategories = "verb")
                db.reviews().rate("1", Direction.EN_ES, 0, Rating.GOOD, System.currentTimeMillis())
            }
            compose.setContent {
                if (running.value) DiegoVocabCardsTheme(darkTheme = dark) {
                    val view = androidx.compose.ui.platform.LocalView.current
                    SideEffect {
                        (view.context as? android.app.Activity)?.window?.let { window ->
                            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                                isAppearanceLightStatusBars = !dark
                                isAppearanceLightNavigationBars = !dark
                            }
                        }
                    }
                    VocabCardsApp(if (dark) Appearance.Dark else Appearance.Light, model,
                        dictionaryLookup = { if (it == "bloom") listOf(bloom) else emptyList() },
                        guideGenerator = UsageNoteGenerator { _, _, _ -> UsageNoteResult.Unavailable }) {}
                }
            }
            waitFor("Start review")
            shot("home")
            tap("Add card")
            compose.onNodeWithText("Headword").performTextInput("bloom")
            waitFor(bloom.definition)
            tap(bloom.definition)
            compose.onNodeWithText("Change meaning").assertExists()
            compose.onNodeWithText("Spanish translation").assertTextContains("florecer")
            shot("editor")
            tap("Usage guide")
            compose.onNodeWithText("Edit guide").performScrollTo().performTextInput("Use bloom when a plant produces flowers.")
            tap("Card details")
            compose.onNodeWithText("Example").assertTextContains(bloom.examples.first())
            shot("card-details")
            tap("Done")
            compose.onNodeWithText("Edit guide").assertTextContains("Use bloom when a plant produces flowers.")
            compose.onNodeWithText("Spanish translation").performScrollTo().performTouchInput { click() }
            shot("editor-keyboard")
            tap("Save and add another")
            compose.waitUntil(10000) { runBlocking { db.vocabulary().cards().first().size == 3 } }
            compose.onNodeWithText("Headword").assertTextContains("")
            compose.onNodeWithText("Spanish translation").assertTextContains("")
            val saved = runBlocking { db.vocabulary().cards().first().first { it.english == "bloom" } }
            assertEquals("florecer", saved.spanish)
            assertEquals(bloom.examples.first(), saved.example)
            assertTrue(saved.usageNote.startsWith("Use bloom"))
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Search English or Spanish")
            compose.onNodeWithText("Search English or Spanish").performTextInput("fire")
            shot("library")
            tap("Progress")
            shot("library-progress")
            tap("Filters")
            tap("Both directions")
            tap("Spanish → English")
            tap("All states")
            tap("Learning")
            tap("Done")
            compose.onNodeWithText("No matching cards.").assertExists()
            tap("Filters (2)")
            tap("Spanish → English")
            tap("English → Spanish")
            tap("Done")
            compose.onNodeWithText("fuego").assertExists()
            compose.onNodeWithText("despedir").assertDoesNotExist()
            tap("Review")
            tap("Advanced options")
            tap("Spanish → English")
            waitFor("Show answer")
            shot("review-front")
            compose.onNodeWithText("fire").assertDoesNotExist()
            tap("Show answer")
            compose.onNodeWithText("fire").assertExists()
            shot("review-answer")
            tap("Good")
            compose.waitUntil(5000) { runBlocking { db.reviews().latest("1", Direction.ES_EN) != null } }
            compose.onNodeWithContentDescription("Back").performClick()
            tap("Home")
            compose.onNodeWithContentDescription("Settings").performClick()
            shot("settings")
        } finally {
            compose.runOnIdle { running.value = false }
            compose.waitForIdle()
            db.close()
        }
    }
}
