package com.example.diegovocabcards

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StudyLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scheduledQuestionIsCenteredWithRevealBelowAndRatingsAfterReveal() {
        withCard("run", "correr", dark = false, fontScale = 1f) { db ->
            val attribution = "Translated by Google"
            tap("Library")
            compose.onNodeWithText("correr").assertIsDisplayed()
            compose.onAllNodesWithContentDescription(attribution, useUnmergedTree = true).assertCountEquals(1)
            captureEmulator("piloto-atribucion-biblioteca-claro.png")
            tap("run")
            tap("Edit card")
            compose.onNodeWithText("Edit card").assertIsDisplayed()
            compose.onNodeWithText("/ɹʌn/").assertIsDisplayed()
            compose.onNodeWithContentDescription("Listen to pronunciation").assertIsDisplayed()
            captureEmulator("editor-ipa-visible-claro.png")
            compose.onAllNodesWithContentDescription(attribution).assertCountEquals(1)
            captureEmulator("piloto-atribucion-editor-claro.png")
            compose.onNodeWithContentDescription("Back").performClick()
            tap("Home")
            tap("Advanced options")
            tap("English → Spanish")
            checkQuestionLayout("run")
            compose.onNodeWithText("correr").assertDoesNotExist()
            compose.onNodeWithContentDescription("Listen to pronunciation").assertExists()
            compose.onNodeWithText("/ɹʌn/").assertExists()
            compose.onNodeWithContentDescription("Translated by Google").assertDoesNotExist()
            val question = compose.onNodeWithText("run")
            val questionTop = question.fetchSemanticsNode().boundsInRoot.top
            val cardBody = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("run")))
            val caption = compose.onNodeWithText("English → Spanish ·", substring = true)
                .fetchSemanticsNode().boundsInRoot
            val cardTop = cardBody.fetchSemanticsNode().boundsInRoot.top
            assertTrue(cardTop - caption.bottom < caption.height * 2)
            captureEmulator("study-polish-review-front-light.png")
            tap("Show answer")
            assertEquals(questionTop, question.fetchSemanticsNode().boundsInRoot.top, 1f)
            compose.onNodeWithText("correr").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Translated by Google").assertCountEquals(1)
            compose.onNodeWithText("I run when I have time.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Good").assertIsDisplayed()
            compose.onNodeWithText("10 min").assertIsDisplayed()
            captureEmulator("study-polish-review-revealed-light.png")
            compose.onNodeWithContentDescription("Listen to pronunciation").performScrollTo().performClick()
            compose.onNodeWithText("No local English voice available.").assertIsDisplayed()
            tap("Easy")
            compose.waitUntil(5000) { runBlocking { db.reviews().schedules().first().size == 1 } }
        }
    }

    @Test fun longManualQuestionAndRevealedContentRemainReachableWithLargerText() {
        val english = "to read between the lines when someone chooses their words very carefully"
        withCard(english, "leer entre líneas", dark = true, fontScale = 1.6f) { db ->
            tap("Review")
            tap("Manual practice")
            tap("English → Spanish")
            tap("Start practice")
            checkQuestionLayout(english)
            compose.onNodeWithText("IPA unavailable").assertDoesNotExist()
            compose.onNodeWithText("leer entre líneas").assertDoesNotExist()
            compose.onNodeWithContentDescription("Listen to pronunciation").assertExists()
            captureEmulator("piloto-practica-antes-oscuro.png")
            tap("Show answer")
            compose.onNodeWithText("leer entre líneas").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Listen to pronunciation").performScrollTo().assertIsDisplayed()
            captureEmulator("study-polish-practice-revealed-dark.png")
            val next = compose.onNodeWithText("Next")
            val fixedBottom = next.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom
            compose.onNodeWithText("Be attentive", substring = true).performScrollTo()
            assertEquals(fixedBottom, next.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom, 1f)
            val book = compose.onNodeWithText("Usage guide")
            book.assertIsDisplayed().performClick()
            compose.onNodeWithText("Una nota guardada para esta acepción.").assertExists()
            tap("Back to review")
            tap("Edit card")
            compose.onNodeWithText("Spanish translation").performScrollTo().performTextReplacement("descartar")
            compose.onNodeWithContentDescription("Back").performClick()
            tap("Discard")
            compose.onNodeWithText("leer entre líneas").assertExists()
            compose.onNodeWithText("Show answer").assertDoesNotExist()
            compose.onNodeWithText("Usage guide").assertIsDisplayed()
            captureEmulator("practica-controles-oscuro.png")
            tap("Next")
            compose.onNodeWithText("Practice complete").assertExists()
            assertTrue(runBlocking { db.reviews().schedules().first().isEmpty() })
        }
    }

    private fun withCard(
        english: String, spanish: String, dark: Boolean, fontScale: Float, actions: (VocabularyDatabase) -> Unit
    ) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val showing = androidx.compose.runtime.mutableStateOf(true)
        try {
            val example = if (dark) List(10) { "Be attentive to what the speaker is implying." }.joinToString("\n")
                else "I run when I have time."
            runBlocking {
                db.vocabulary().save("card", english, spanish, example, "", source = GOOGLE_TRANSLATION,
                    usageNote = if (dark) "Una nota guardada para esta acepción." else "",
                    ipa = if (dark) "" else "/ɹʌn/",
                    ipaSource = if (dark) "" else "Colaboradores de Wiktionary · run · CC BY-SA 4.0\nhttps://en.wiktionary.org/wiki/run#Pronunciation")
            }
            val model = VocabularyModel(app, db)
            compose.setContent {
                if (showing.value) CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    DiegoVocabCardsTheme(darkTheme = dark) {
                        VocabCardsApp(if (dark) Appearance.Dark else Appearance.Light, model) {}
                    }
                }
            }
            actions(db)
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun checkQuestionLayout(question: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(question).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("Show answer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, text.center.x, 2f)
        assertTrue(text.top >= root.top && text.bottom < button.top)
        assertTrue(button.center.y > root.height * 0.78f)
    }

    private fun tap(label: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNode(hasText(label) and hasClickAction())
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }
}
