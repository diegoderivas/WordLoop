package com.example.diegovocabcards

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.CardPack
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme
import io.github.openspacedrepetition.Rating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonalEditionTest {
    @get:Rule val compose = createComposeRule()
    private val incoming = mutableStateOf<String?>(null)

    @Test fun sampleIsSeparateAndSharingProtectsTheExistingDraft() = withApp { db, _ ->
        waitFor("Welcome to WordLoop")
        captureEmulator("personal-edition-first-run-light.png")
        tap("Try a sample review")
        tap("Start practice")
        tap("Show answer")
        compose.onNodeWithText("mist").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(runBlocking { db.vocabulary().cards().first().isEmpty() })
        assertEquals(0, runBlocking { db.backup().countEvents() })

        compose.runOnIdle { incoming.value = "read between the lines" }
        waitFor("read between the lines")
        compose.onNodeWithText("Spanish translation").performScrollTo().performTextInput("leer entre líneas")
        compose.onNodeWithContentDescription("Back").performClick()
        tap("Keep editing")
        compose.onNodeWithText("leer entre líneas").assertExists()

        compose.runOnIdle { incoming.value = "brush up on" }
        tap("Open draft")
        waitFor("Discard changes?")
        tap("Discard")
        waitFor("brush up on")
        compose.onNodeWithText("leer entre líneas").assertDoesNotExist()
        captureEmulator("personal-edition-shared-draft-light.png")
    }

    @Test fun libraryLanguageFilterCanBeRemovedAndPackUsesTheFilteredCards() = withApp { db, _ ->
        runBlocking {
            db.vocabulary().save("es", "bloom", "florecer", "", "")
            db.vocabulary().save("ja", "bloom", "咲く", "The roses bloom.", "gardening",
                definition = "To produce flowers.", synonyms = "flower", senseCategories = "verb", language = "ja")
            db.reviews().rate("es", Direction.EN_ES, 0, Rating.GOOD, System.currentTimeMillis())
        }
        waitFor("Answered today")
        tap("Library")
        tap("Filters")
        tap("All languages")
        compose.onNode(hasText("Japanese") and hasClickAction() and hasAnyAncestor(isPopup())).performClick()
        tap("Done")
        waitFor("咲く")
        compose.onNodeWithText("florecer").assertDoesNotExist()
        captureEmulator("ui-audit-library-light.png")
        tap("咲く")
        compose.onNodeWithText("Meaning").assertIsDisplayed()
        compose.onNodeWithText("Examples").assertIsDisplayed()
        compose.onNodeWithText("Synonyms").assertIsDisplayed()
        captureEmulator("ui-audit-card-details-light.png")
        tap("Close")
        compose.onNodeWithContentDescription("Card packs").performClick()
        tap("Share cards")
        tap("Select all shown")
        compose.onNodeWithText("Share 1 card").assertExists()
        compose.onNodeWithText("bloom → florecer").assertDoesNotExist()
        tap("Cancel")
        compose.onNodeWithContentDescription("Remove Japanese filter").performClick()
        waitFor("florecer")
    }

    @Test fun homeShowsTheNextReviewWhenCaughtUp() = withApp { db, _ ->
        runBlocking {
            db.vocabulary().save("mist", "mist", "niebla", "", "")
            for (direction in Direction.entries) {
                db.reviews().rate("mist", direction, 0, Rating.GOOD, System.currentTimeMillis())
            }
        }
        waitFor("You’re caught up")
        compose.onNodeWithText("Next review:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Start review").assertIsNotEnabled()
        compose.onNode(hasText("Add card") and hasClickAction()).assertIsDisplayed()
        captureEmulator("ui-audit-home-caught-up-light.png")
    }

    @Test fun packAddsMixedCardsOnceAndKeepsExistingHistoryAndEdits() = withApp { db, model ->
        runBlocking {
            db.vocabulary().save("existing", "bloom", "florecer", "My own example.", "my tag")
            db.reviews().rate("existing", Direction.EN_ES, 0, Rating.GOOD, 1_800_000_000_000L)
            val existingReview = db.reviews().latest("existing", Direction.EN_ES)
            val selected = listOf(
                Card("remote-one", "bloom", "florecer", "Different example.", emptyList()),
                Card("remote-two", "bloom", "咲く", "The roses bloom.", listOf("flowers"), language = "ja",
                    ipa = "/bluːm/", usageNote = "Use for flowers.")
            )
            val encoded = CardPack.encode(selected)
            val decoded = CardPack.read(encoded.inputStream())
            assertEquals(selected.map { it.copy(id = "") }, decoded)
            assertEquals(1, model.importCards(decoded))
            assertEquals(0, model.importCards(decoded))
            val cards = db.vocabulary().cards().first()
            assertEquals(2, cards.size)
            assertEquals("My own example.", cards.single { it.id == "existing" }.example)
            assertEquals(selected[1].copy(id = ""), cards.single { it.language == "ja" }.copy(id = ""))
            assertEquals(existingReview, db.reviews().latest("existing", Direction.EN_ES))
            assertEquals(1, db.backup().countEvents())
            assertTrue(runCatching { CardPack.read(encoded.copyOf(encoded.size / 2).inputStream()) }.isFailure)
        }
    }

    private fun withApp(actions: (VocabularyDatabase, VocabularyModel) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db)
        val showing = mutableStateOf(true)
        try {
            compose.setContent {
                if (showing.value) DiegoVocabCardsTheme(darkTheme = false) {
                    val view = androidx.compose.ui.platform.LocalView.current
                    androidx.compose.runtime.SideEffect {
                        (view.context as? android.app.Activity)?.window?.let {
                            androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = true
                        }
                    }
                    VocabCardsApp(Appearance.Light, model, dictionaryLookup = { emptyList() },
                        automaticSpeech = false, sharedText = incoming.value, onSharedTextHandled = { incoming.value = null }) {}
                }
            }
            actions(db, model)
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun waitFor(label: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(label: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.tapControl(label)
    }
}
