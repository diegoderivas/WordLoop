package com.example.diegovocabcards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SuggestionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingMeaningKeepsOnlyTheNewAutomaticFieldsAndSavesOffline() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            val model = VocabularyModel(app, db)
            val work = Suggestion("Give someone a job", "verb", "SOURCE",
                listOf("contratar"), listOf("They hired a technician.", "We hired more staff."), listOf("employ"))
            val rent = Suggestion("Pay to use a vehicle", "verb", "SOURCE",
                listOf("alquilar"), listOf("They hired a van."), listOf("rent"))
            val incomplete = Suggestion("Accept employment", "verb", "SOURCE")
            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CardEditor(null, "", emptyList(), emptyList(), model, lookup = { query ->
                        if (query == "offline") throw java.io.IOException("disconnected")
                        listOf(work, work, rent, incomplete, Suggestion("Payment", "adjective", "SOURCE"))
                    }) {}
                }
            }
            fun tap(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
            fun waitFor(label: String, substring: Boolean = false) {
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
                }
            }

            val english = compose.onNodeWithText("Palabra o expresión en inglés")
            english.performTextInput("hire")
            waitFor(work.definition)
            compose.onAllNodesWithText(work.definition).assertCountEquals(1)
            compose.onNodeWithText("Payment").assertDoesNotExist()
            compose.onNodeWithText("Ver más significados").assertExists()
            tap(work.definition)
            compose.onNodeWithText("Elegí un ejemplo").assertExists()
            compose.onNodeWithText("We hired more staff.").performClick()
            compose.onNodeWithText("We hired more staff.").assertExists()
            compose.onNodeWithText("employ").performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
            compose.onNodeWithText("employ").performClick().assertIsNotSelected()
            compose.onNodeWithText("Categoría gramatical").assertExists()
            tap("Elegir ejemplo")
            compose.onNodeWithText("Continuar sin ejemplo").performClick()
            compose.onNodeWithText("We hired more staff.").assertDoesNotExist()
            tap(rent.definition)
            compose.onNodeWithText("Conservar mis campos").performClick()
            compose.onNodeWithText("Ejemplo en inglés (opcional)").assertTextContains("")
            compose.onNodeWithText("employ").assertDoesNotExist()
            compose.onNodeWithText("rent").assertExists()
            tap(incomplete.definition)
            compose.onNodeWithText("They hired a van.").assertDoesNotExist()
            compose.onNodeWithText("rent").assertDoesNotExist()
            compose.onNodeWithText("Guardar").assertIsNotEnabled()
            english.performScrollTo().performTextReplacement("offline")
            waitFor("No se pudo consultar", substring = true)
            compose.onNodeWithText("Traducción al español").performScrollTo().performTextInput("sin conexión")
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("sin conexión", saved.spanish)
            assertEquals("", saved.definition)
            assertEquals("", saved.source)
        } finally {
            db.close()
        }
    }

    @Test fun lateTranslationFromAnotherSenseCannotReplaceTheCurrentProposal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val showing = androidx.compose.runtime.mutableStateOf(true)
        try {
            val model = VocabularyModel(app, db)
            val flight = Suggestion("To leave the ground.", "verb", "dictionary")
            val breakTime = Suggestion("To take a break from work.", "verb", "dictionary")
            val removal = Suggestion("To remove.", "verb", "dictionary", listOf("quitarse"))
            var googleCalls = 0
            val firstStarted = CompletableDeferred<Unit>()
            val firstResult = CompletableDeferred<String>()
            val secondResult = CompletableDeferred<String>()
            compose.setContent {
                if (showing.value) Column(Modifier.verticalScroll(rememberScrollState())) {
                    CardEditor(null, "take off", emptyList(), emptyList(), model,
                        lookup = { listOf(flight, breakTime, removal) },
                        translate = { _, meaning ->
                            googleCalls++
                            if (meaning == flight) withContext(NonCancellable) {
                                firstStarted.complete(Unit)
                                firstResult.await()
                            } else secondResult.await()
                        }
                    ) {}
                }
            }
            fun tap(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(removal.definition).fetchSemanticsNodes().isNotEmpty() }
            tap(removal.definition)
            compose.onNodeWithText("Traducir con Google").assertExists()
            tap(flight.definition)
            compose.waitUntil(5000) { firstStarted.isCompleted }
            tap(breakTime.definition)
            val spanish = compose.onNodeWithText("Traducción al español")
            spanish.performScrollTo().performTextReplacement("mi corrección")
            secondResult.complete("tomarse un descanso")
            firstResult.complete("despegar")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("tomarse un descanso").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("despegar").assertDoesNotExist()
            spanish.assertTextContains("mi corrección")
            tap(flight.definition)
            tap(breakTime.definition)
            spanish.assertTextContains("mi corrección")
            assertEquals(2, googleCalls)
            tap("Usar traducción de Google")
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("tomarse un descanso", saved.spanish)
            assertEquals(breakTime.definition, saved.definition)
            assertTrue(GOOGLE_TRANSLATION in saved.source)
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    @Test fun compareSenseTranslationsAndChooseSynonymsWithoutChangingManualSpanish() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            val model = VocabularyModel(app, db)
            val flame = Suggestion("Burning material.", "noun", "Dictionary", listOf("fuego"),
                synonymOptions = listOf("flame"))
            val dismiss = Suggestion("Dismiss an employee.", "verb", "Dictionary", listOf("despedir"))
            val dismissed = Suggestion("Lose your job.", "phrase", "Dictionary", listOf("ser despedido"))
            val delayed = CompletableDeferred<String>()
            val started = CompletableDeferred<Unit>()
            compose.setContent {
                Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())) {
                    CardEditor(null, "fire", emptyList(), emptyList(), model,
                        lookup = { if (it == "fire") listOf(flame, dismiss) else listOf(dismissed) },
                        translate = { _, _ -> "echar" },
                        nanoOptions = { _, sense, task ->
                            when {
                                sense == flame && task == SenseRequest.Translation -> withContext(NonCancellable) {
                                    started.complete(Unit)
                                    listOf(delayed.await())
                                }
                                task == SenseRequest.Synonyms -> listOf("sack", "discharge")
                                sense == dismiss -> listOf("destituir")
                                else -> listOf("ser despedido")
                            }
                        }) {}
                }
            }
            fun tap(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
            fun waitFor(label: String) = compose.waitUntil(5000) {
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            val spanish = compose.onNodeWithText("Traducción al español")
            waitFor(flame.definition)
            tap(flame.definition)
            compose.onNodeWithText("Sugerir sinónimos con Gemini").assertDoesNotExist()
            tap("Traducir con Gemini")
            compose.waitUntil(5000) { started.isCompleted }
            tap(dismiss.definition)
            delayed.complete("llama")
            spanish.performScrollTo().performTextReplacement("mi traducción")
            tap("Traducir con Google")
            tap("Traducir con Gemini")
            waitFor("destituir")
            compose.onNodeWithText("llama").assertDoesNotExist()
            compose.onNodeWithText("echar").assertExists()
            compose.onNodeWithText("Usar del diccionario · despedir").assertExists()
            spanish.assertTextContains("mi traducción")
            spanish.performScrollTo().performTouchInput { click() }
            captureEmulator("autocompletado-editor-teclado-claro.png")
            compose.onNodeWithText("Usar traducción de Gemini").performScrollTo()
            captureEmulator("editor-propuestas-claro.png")
            tap("Usar traducción de Google")
            tap("Usar traducción de Gemini")
            spanish.assertTextContains("destituir")
            waitFor("sack")
            tap("sack")
            tap("Detalles opcionales")
            compose.onNodeWithText("Sinónimos (separados por comas)").assertTextContains("sack")
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("destituir", saved.spanish)
            assertTrue(GEMINI_TRANSLATION in saved.source && GOOGLE_TRANSLATION !in saved.source)
            assertTrue(GEMINI_SYNONYMS in saved.source)
            compose.runOnIdle { model.acknowledgeSave() }
            compose.onNodeWithText("Palabra o expresión en inglés").performScrollTo().performTextReplacement("get fired")
            waitFor(dismissed.definition)
            tap(dismissed.definition)
            compose.onNodeWithText("Conservar mis campos").performClick()
            tap("Traducir con Gemini")
            tap("Usar traducción de Gemini")
            spanish.assertTextContains("ser despedido")
        } finally { db.close() }
    }

    @Test fun staleResultsAndManualCorrectionsAreProtected() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db)
        val started = java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CardEditor(null, "", emptyList(), emptyList(), model, lookup = { query ->
                    if (query == "run") {
                        started.set(true)
                        withContext(NonCancellable) { delay(1500) }
                    }
                    listOf(Suggestion("Definition for $query", "verb", "TEST DATA",
                        examples = listOf("Example for $query")))
                }) {}
            }
        }
        try {
            compose.onNodeWithText("Palabra o expresión en inglés").performTextInput("run")
            compose.waitUntil(5000) { started.get() }
            compose.onNodeWithText("Palabra o expresión en inglés").performTextReplacement("look up")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Definition for look up").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Definition for run").assertDoesNotExist()
            compose.onNodeWithText("Detalles opcionales").performScrollTo().performClick()
            compose.onNodeWithText("Definición en inglés").performScrollTo().performTextInput("My own definition")
            compose.onNodeWithText("Definition for look up").performScrollTo().performClick()
            compose.onNodeWithText("Conservar mis campos").performClick()
            compose.onNodeWithText("My own definition").assertExists()
            compose.onNodeWithText("Definition for look up").performScrollTo().performClick()
            compose.onNodeWithText("Usar sugerencia").performClick()
            compose.onNodeWithText("Traducción al español").performScrollTo().performTextInput("buscar")
            compose.onNodeWithText("Guardar").performScrollTo().performClick()
            compose.waitUntil(5000) { model.saved != null }
            val card = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("Definition for look up", card.definition)
            assertEquals("Example for look up", card.example)
            assertTrue(card.source.contains("TEST DATA"))
        } finally {
            db.close()
        }
    }
}
