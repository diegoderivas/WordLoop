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
            val attribution = "Traducción automática de Google Translate"
            tap("Biblioteca")
            compose.onNodeWithText("correr").assertIsDisplayed()
            compose.onAllNodesWithContentDescription(attribution, useUnmergedTree = true).assertCountEquals(1)
            captureEmulator("piloto-atribucion-biblioteca-claro.png")
            tap("run")
            compose.onNodeWithText("Editar tarjeta").assertIsDisplayed()
            compose.onNodeWithText("/ɹʌn/").assertIsDisplayed()
            compose.onNodeWithContentDescription("Escuchar pronunciación").assertIsDisplayed()
            captureEmulator("pronunciacion-editor-claro.png")
            compose.onAllNodesWithContentDescription(attribution).assertCountEquals(1)
            captureEmulator("piloto-atribucion-editor-claro.png")
            compose.onNodeWithContentDescription("Volver").performClick()
            tap("Inicio")
            tap("Repasar en español")
            checkQuestionLayout("run")
            compose.onNodeWithText("correr").assertDoesNotExist()
            compose.onNodeWithContentDescription("Escuchar pronunciación").assertExists()
            compose.onNodeWithText("/ɹʌn/").assertExists()
            compose.onNodeWithContentDescription("Traducción automática de Google Translate").assertDoesNotExist()
            captureEmulator("piloto-repasar-antes-claro.png")
            tap("Mostrar respuesta")
            compose.onNodeWithText("correr").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Traducción automática de Google Translate").assertCountEquals(1)
            compose.onNodeWithText("I run when I have time.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Bien").assertIsDisplayed()
            compose.onNodeWithText("10 min").assertIsDisplayed()
            captureEmulator("repaso-controles-claro.png")
            compose.onNodeWithContentDescription("Escuchar pronunciación").performScrollTo().performClick()
            compose.onNodeWithText("No hay una voz inglesa local disponible.").assertIsDisplayed()
            tap("Fácil")
            compose.waitUntil(5000) { runBlocking { db.reviews().schedules().first().size == 1 } }
        }
    }

    @Test fun longManualQuestionAndRevealedContentRemainReachableWithLargerText() {
        val english = "to read between the lines when someone chooses their words very carefully"
        withCard(english, "leer entre líneas", dark = true, fontScale = 1.6f) { db ->
            tap("Repasar")
            tap("Práctica manual sin calificar")
            tap("Inglés → Español")
            tap("Iniciar práctica")
            checkQuestionLayout(english)
            compose.onNodeWithText("leer entre líneas").assertDoesNotExist()
            compose.onNodeWithContentDescription("Escuchar pronunciación").assertExists()
            captureEmulator("piloto-practica-antes-oscuro.png")
            tap("Mostrar respuesta")
            compose.onNodeWithText("leer entre líneas").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("Escuchar pronunciación").assertIsDisplayed()
            captureEmulator("piloto-practica-despues-oscuro.png")
            val next = compose.onNodeWithText("Siguiente")
            val fixedBottom = next.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom
            compose.onNodeWithText("Be attentive", substring = true).performScrollTo()
            assertEquals(fixedBottom, next.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom, 1f)
            val book = compose.onNodeWithContentDescription("Guía de uso")
            book.performScrollTo().performClick()
            compose.onNodeWithText("Una nota guardada para esta acepción.").assertExists()
            tap("Volver al repaso")
            tap("Editar tarjeta")
            compose.onNodeWithText("Traducción al español").performScrollTo().performTextReplacement("descartar")
            compose.onNodeWithContentDescription("Volver").performClick()
            compose.onNodeWithText("leer entre líneas").assertExists()
            compose.onNodeWithText("Mostrar respuesta").assertDoesNotExist()
            compose.onNodeWithContentDescription("Guía de uso").performScrollTo()
            captureEmulator("practica-controles-oscuro.png")
            tap("Siguiente")
            compose.onNodeWithText("Práctica terminada").assertExists()
            assertTrue(runBlocking { db.reviews().schedules().first().isEmpty() })
        }
    }

    private fun withCard(
        english: String, spanish: String, dark: Boolean, fontScale: Float, actions: (VocabularyDatabase) -> Unit
    ) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
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
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    DiegoVocabCardsTheme(darkTheme = dark) {
                        VocabCardsApp(if (dark) Appearance.Dark else Appearance.Light, model) {}
                    }
                }
            }
            actions(db)
        } finally {
            db.close()
        }
    }

    private fun checkQuestionLayout(question: String) {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText(question).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("Mostrar respuesta").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(root.center.x, text.center.x, 2f)
        assertTrue(text.center.y in (root.height * 0.34f)..(root.height * 0.66f))
        assertTrue(button.center.y > root.height * 0.78f)
    }

    private fun tap(label: String) {
        val node = compose.onNodeWithText(label)
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }
}
