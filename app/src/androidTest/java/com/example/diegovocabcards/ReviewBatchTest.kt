package com.example.diegovocabcards

import android.app.Application
import androidx.compose.runtime.mutableStateOf
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewBatchTest {
    @get:Rule val compose = createComposeRule()
    private val quick = "Quick review · up to 10 questions"
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)

    @Test fun quickIsDisabledWhenNothingIsAvailableOnBothEntryScreens() = withApp {
        for (screen in listOf("Home", "Review")) {
            if (screen == "Review") tap(screen)
            waitFor(quick)
            compose.onNodeWithText("Start review").assertIsNotEnabled()
            compose.onNodeWithText(quick).assertIsNotEnabled()
        }
    }

    @Test fun fullReviewUsesAllQuestionsAndQuickNeverExpands() = withApp { db ->
        runBlocking {
            repeat(6) { db.vocabulary().save("sense-$it", "Term $it", "Translation $it", "", "") }
        }
        tap("Start review")
        waitFor("1 of 12", substring = true)
        compose.onNodeWithContentDescription("Back").performClick()
        tap("Review")
        tap(quick)
        waitFor("1 of 10", substring = true)
        runBlocking { db.vocabulary().save("late", "Late card", "Tarjeta tardía", "", "") }
        repeat(10) {
            waitFor("${it + 1} of 10", substring = true)
            tap("Show answer")
            tap(if (it == 0) "Again" else "Easy")
        }
        waitFor("Ratings saved: 10")
        compose.onNodeWithText("4 questions available").assertExists()
        assertEquals(10, runBlocking { db.backup().countEvents() })
        tap("Continue review")
        waitFor("1 of 4", substring = true)
    }

    @Test fun quickDeleteAfterRevealShrinksTheBatchAndPreservesTheOtherSense() = withApp { db ->
        seedMixed(db)
        val retained = runBlocking { db.reviews().latest("a", Direction.EN_ES) }
        tap(quick)
        waitFor("1 of 6", substring = true)
        tap("Show answer")
        tap("Easy")
        waitFor("2 of 6", substring = true)
        tap("Show answer")
        openDelete()
        waitFor("Delete “fire → despedir”?")
        tap("Cancel")
        compose.onNodeWithText("despedir").assertExists()
        compose.onNodeWithText("Show answer").assertDoesNotExist()
        openDelete()
        tap("Delete")
        waitFor("2 of 4", substring = true)
        repeat(3) {
            tap("Show answer")
            tap("Easy")
        }
        waitFor("Ratings saved: 4")
        compose.onNodeWithText("0 questions available").assertExists()
        assertEquals(setOf("a", "c"), runBlocking { db.vocabulary().cards().first().map { it.id }.toSet() })
        assertTrue(runBlocking { Direction.entries.all { db.reviews().recent("b", it).isEmpty() } })
        assertTrue(runBlocking { db.reviews().recent("a", Direction.EN_ES).contains(retained) })
    }

    @Test fun lastManualCardCanBeDeletedBeforeRevealAfterAWriteFailure() = withApp { db ->
        runBlocking { db.vocabulary().save("ja", "bloom", "咲く", "", "", language = "ja") }
        tap("Review")
        tap("Manual practice")
        tap("Start practice")
        openDelete()
        waitFor("Delete “bloom → 咲く”?")
        tap("Cancel")
        compose.onNodeWithText("Show answer").assertIsDisplayed()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER reject_delete BEFORE DELETE ON Sense BEGIN SELECT RAISE(ABORT, 'test'); END")
        openDelete()
        tap("Delete")
        waitFor("Could not delete the card. Please try again.")
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_delete")
        tap("Delete")
        waitFor("Practice complete")
        assertTrue(runBlocking { db.vocabulary().cards().first().isEmpty() })
        assertEquals(0, runBlocking { db.backup().countEvents() })
    }

    @Test fun lastScheduledCardCanBeDeletedBeforeReveal() = withApp { db ->
        runBlocking { db.vocabulary().save("ja", "bloom", "咲く", "", "", language = "ja") }
        tap("Start review")
        openDelete()
        tap("Delete")
        waitFor("Ratings saved: 0")
        compose.onNodeWithText("No questions due.").assertIsDisplayed()
    }

    @Test fun lightStudyAndLibrary() = inspect(false)
    @Test fun darkStudyAndLibrary() = inspect(true)

    private fun inspect(dark: Boolean) = withApp(dark) { db ->
        seedMixed(db)
        val mode = if (dark) "dark" else "light"
        waitFor("6 questions")
        compose.onNodeWithText("Start review").assertIsDisplayed()
        compose.onNodeWithText(quick).assertIsDisplayed()
        captureEmulator("quick-review-home-$mode.png")
        tap("Library")
        waitFor("despedir")
        compose.onNodeWithText("Verb").assertIsDisplayed()
        compose.onNodeWithText("Noun").assertIsDisplayed()
        captureEmulator("quick-review-library-$mode.png")
        tap("Review")
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText(quick).assertIsDisplayed()
        captureEmulator("quick-review-entry-$mode.png")
        tap(quick)
        openDelete()
        tap("Cancel")
        compose.onNodeWithText("Delete “fire → fuego”?").assertDoesNotExist()
        compose.onNodeWithText("Show answer").assertIsDisplayed()
        captureEmulator("quick-review-study-$mode.png")
        tap("Show answer")
        compose.onNodeWithContentDescription("Delete card").assertIsDisplayed()
        captureEmulator("quick-review-revealed-$mode.png")
    }

    private fun seedMixed(db: VocabularyDatabase) = runBlocking {
        val dao = db.vocabulary()
        dao.save("a", "fire", "fuego", "The fire spread.", "", senseCategories = "noun", ipa = "/faɪə/")
        dao.save("b", "fire", "despedir", "", "", senseCategories = "verb")
        dao.save("c", "bloom", "咲く", "", "", language = "ja")
        for (id in listOf("a", "b")) for (side in Direction.entries) {
            db.reviews().rate(id, side, 0, Rating.GOOD, clock.millis() - 86_400_000)
        }
    }

    private fun openDelete() {
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Delete card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Delete card").assertIsDisplayed().performClick()
    }

    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(label: String) {
        compose.waitUntil(5000) { compose.onAllNodes(hasText(label) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.tapControl(label)
    }

    private fun withApp(dark: Boolean = false, actions: (VocabularyDatabase) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db)
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                if (visible.value) DiegoVocabCardsTheme(darkTheme = dark) {
                    val view = androidx.compose.ui.platform.LocalView.current
                    androidx.compose.runtime.SideEffect {
                        (view.context as? android.app.Activity)?.window?.let { window ->
                            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                                isAppearanceLightStatusBars = !dark
                                isAppearanceLightNavigationBars = !dark
                            }
                        }
                    }
                    VocabCardsApp(if (dark) Appearance.Dark else Appearance.Light, model,
                        clock = clock, automaticSpeech = false) {}
                }
            }
            actions(db)
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            db.close()
        }
    }
}
