package com.example.diegovocabcards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class UsageNoteEditorTest {
    @get:Rule val compose = createComposeRule()

    private val flight = Suggestion("To begin flight.", "verb", "Dictionary", listOf("despegar"))
    private val removing = Suggestion("To remove clothing.", "verb", "Dictionary", listOf("quitarse"))
    private val note = UsageNote("Se usa cuando comienza el vuelo.",
        listOf("The flight took off on time.", "We took off just before dawn."), "Land indica el final del vuelo.")

    @Test fun savesOnlyAcceptedContentAndReopensItAlongsideManualExamples() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val name = "usage-editor-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(app, VocabularyDatabase::class.java, name).build()
        var db = open()
        try {
            val model = VocabularyModel(app, db)
            var downloaded = false
            showEditor(model, lookup = { listOf(flight) }) { prompt, download, progress ->
                assertTrue("Expression: take off" in prompt)
                assertTrue(flight.definition in prompt)
                if (!downloaded && !download) UsageNoteResult.Downloadable else {
                    if (download) progress(UsageNoteProgress.Downloading)
                    downloaded = true
                    UsageNoteResult.Ready(note)
                }
            }
            waitFor(flight.definition)
            tap(flight.definition)
            compose.onNodeWithText("Ejemplo en inglés (opcional)").performScrollTo()
                .performTextInput("The plane took off yesterday.")
            tap("Entender el uso")
            waitFor("Descargar modelo")
            tap("Descargar modelo")
            waitFor(note.explanation)
            tap("Seguir sin usarla")
            compose.onNodeWithText(note.explanation).assertDoesNotExist()
            tap("Entender el uso")
            waitFor(note.explanation)
            tap("Añadir solo el ejemplo 2")
            compose.onNodeWithText(note.examples[1]).assertExists()
            compose.onNodeWithText(note.examples[0]).assertDoesNotExist()
            tap("Entender el uso")
            waitFor(note.explanation)
            compose.onNodeWithText(note.explanation).performScrollTo().assertIsDisplayed()
            captureEmulator("piloto-nota-de-uso-claro.png")
            tap("Guardar nota")
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            db.close()
            db = open()
            val card = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("despegar", card.spanish)
            assertEquals("The plane took off yesterday.", card.example)
            assertEquals(note.examples[1], card.generatedExamples)
            assertEquals(note.forStorage(), card.usageNote)
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun lateResponsesAreDiscardedOnSenseAndExpressionChanges() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val flightStarted = CompletableDeferred<Unit>()
        val removalStarted = CompletableDeferred<Unit>()
        val releaseFlight = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        val work = Suggestion("To dismiss from employment.", "phrase", "Dictionary", listOf("ser despedido"))
        try {
            val model = VocabularyModel(app, db)
            showEditor(model, lookup = { if (it == "take off") listOf(flight, removing) else listOf(work) }) { prompt, _, _ ->
                when {
                    flight.definition in prompt -> withContext(NonCancellable) {
                        flightStarted.complete(Unit)
                        releaseFlight.await()
                        UsageNoteResult.Ready(note)
                    }
                    removing.definition in prompt -> withContext(NonCancellable) {
                        removalStarted.complete(Unit)
                        releaseRemoval.await()
                        UsageNoteResult.Ready(note.copy(explanation = "Resultado tardío de quitarse."))
                    }
                    else -> UsageNoteResult.Unavailable
                }
            }
            waitFor(flight.definition)
            tap(flight.definition)
            tap("Entender el uso")
            compose.waitUntil(5000) { flightStarted.isCompleted }
            tap(removing.definition)
            tap("Entender el uso")
            compose.waitUntil(5000) { removalStarted.isCompleted }
            releaseFlight.complete(Unit)
            compose.onNodeWithText(note.explanation).assertDoesNotExist()
            compose.onNodeWithText("Palabra o expresión en inglés").performScrollTo().performTextReplacement("get fired")
            releaseRemoval.complete(Unit)
            waitFor(work.definition)
            tap(work.definition)
            tap("Entender el uso")
            waitFor("Gemini Nano no está disponible", substring = true)
            compose.onNodeWithText("Resultado tardío de quitarse.").assertDoesNotExist()
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            val card = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("get fired", card.english)
            assertEquals("", card.usageNote)
            assertEquals("", card.generatedExamples)
        } finally {
            releaseFlight.complete(Unit)
            releaseRemoval.complete(Unit)
            db.close()
        }
    }

    private fun showEditor(
        model: VocabularyModel,
        lookup: suspend (String) -> List<Suggestion>,
        generator: UsageNoteGenerator
    ) {
        compose.setContent {
            DiegoVocabCardsTheme(darkTheme = false) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CardEditor(null, "take off", emptyList(), emptyList(), model,
                        lookup = lookup, usageNotes = generator) {}
                }
            }
        }
    }

    private fun tap(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()

    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
