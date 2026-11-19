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
    private val note = UsageNote(
        "A plane takes off when it leaves the ground. The phrase marks the beginning of a flight.",
        "Say take off from a runway or take off for a destination.",
        listOf("The flight took off on time.", "We took off just before dawn.", "The rescue helicopter took off for the island."),
        "Land marks the end of a flight."
    )

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
            tap("Guía de uso")
            waitFor("Descargar modelo")
            tap("Descargar modelo")
            waitFor(note.meaning)
            tap("Guía de uso")
            compose.onNodeWithText(note.meaning).assertDoesNotExist()
            tap("Guía de uso")
            waitFor(note.meaning)
            tap("Añadir solo el ejemplo 3")
            tap("Guía de uso")
            compose.onNodeWithText(note.examples[2]).assertExists()
            compose.onNodeWithText("Ejemplo en inglés (opcional)").assertTextContains("The plane took off yesterday.")
            tap("Guía de uso")
            waitFor(note.meaning)
            compose.onNodeWithText(note.meaning).performScrollTo().assertIsDisplayed()
            captureEmulator("guia-de-uso-claro.png")
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            db.close()
            db = open()
            val card = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("despegar", card.spanish)
            assertEquals("The plane took off yesterday.", card.example)
            assertEquals(note.examples[2], card.generatedExamples)
            assertEquals(note.forStorage(), card.usageNote)
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun missingFieldsUseNanoAndIntentionalRemovalSurvivesReopeningTheSense() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            val model = VocabularyModel(app, db)
            var calls = 0
            val meaning = flight.copy(spanish = emptyList())
            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CardEditor(null, "take off", emptyList(), emptyList(), model,
                        lookup = { listOf(meaning, removing) }, translate = { _, _ -> null },
                        nanoOptions = { _, _, task -> if (task == SenseRequest.Translation) listOf("despegar") else emptyList() },
                        usageNotes = { _, _, _ ->
                            calls++
                            UsageNoteResult.Ready(note)
                        }) {}
                }
            }
            waitFor(meaning.definition)
            tap(meaning.definition)
            waitFor("Usar traducción de Gemini")
            compose.onNodeWithText("Traducción al español").assertTextContains("despegar")
            val example = compose.onNodeWithText("Ejemplo en inglés (opcional)")
            example.assertTextContains(note.examples.first())
            tap("Quitar ejemplo")
            tap(removing.definition)
            tap(meaning.definition)
            example.assertTextContains("")
            compose.onNodeWithText("Traducción al español").assertTextContains("despegar")
            assertEquals(2, calls)
        } finally { db.close() }
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
                        UsageNoteResult.Ready(note.copy(meaning = "Late result for removing clothes."))
                    }
                    else -> {
                        assertTrue("Expression: get fired" in prompt && work.definition in prompt)
                        UsageNoteResult.Unavailable
                    }
                }
            }
            waitFor(flight.definition)
            tap(flight.definition)
            compose.waitUntil(5000) { flightStarted.isCompleted }
            tap(removing.definition)
            releaseFlight.complete(Unit)
            compose.waitUntil(5000) { removalStarted.isCompleted }
            compose.onNodeWithText(note.meaning).assertDoesNotExist()
            compose.onNodeWithText("Palabra o expresión en inglés").performScrollTo().performTextReplacement("get fired")
            releaseRemoval.complete(Unit)
            waitFor(work.definition)
            tap(work.definition)
            tap("Guía de uso")
            waitFor("Gemini Nano no está disponible", substring = true)
            compose.onNodeWithText("Late result for removing clothes.").assertDoesNotExist()
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

    @Test fun rejectsSpanishMeaningAndKeepsALegacyNoteOnRegeneration() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val ruefully = Suggestion("With regret or sorrow.", "adverb", "Dictionary", listOf("con pesar"))
        val legacy = "Dice que alguien siente pesar.\n\nHe smiled ruefully.\n\nMatiz · (omit)"
        val original = com.example.diegovocabcards.data.Card(
            "legacy", "ruefully", "con pesar", "He smiled ruefully.", emptyList(), usageNote = legacy)
        val response = """
            Meaning: A rueful smile shows that someone regrets what happened. It can carry a touch of irony when the person sees the funny side of a mistake.
            How to use it: Say smile ruefully or shake your head ruefully when accepting a disappointing outcome.
            Nuance: (omit)
            Example 1: She smiled ruefully as she read her old predictions.
            Example 2: He shook his head ruefully at the missed penalty.
            Example 3: They laughed ruefully when the repair failed again.
        """.trimIndent()
        val spanish = response.replace(
            "A rueful smile shows that someone regrets what happened. It can carry a touch of irony when the person sees the funny side of a mistake.",
            "Una sonrisa de pesar muestra arrepentimiento por lo sucedido. A veces puede tener un ligero toque de ironía.")
        try {
            val model = VocabularyModel(app, db)
            var calls = 0
            showEditor(model, lookup = { listOf(ruefully) }, original = original) { prompt, _, _ ->
                assertTrue("Expression: ruefully" in prompt && "Spanish gloss (context): con pesar" in prompt)
                validateUsageNote(if (++calls == 1) spanish else response)
                    ?.let(UsageNoteResult::Ready) ?: UsageNoteResult.Error
            }
            waitFor(ruefully.definition)
            tap(ruefully.definition)
            tap("Guía de uso")
            tap("Ver nota de uso")
            waitFor("Reintentar nota")
            compose.onNodeWithText(legacy).assertExists()
            tap("Reintentar nota")
            waitFor("How to use it")
            compose.onNodeWithText("Nuance").assertDoesNotExist()
            compose.onNodeWithText("They laughed ruefully when the repair failed again.").assertExists()
            tap("Regenerar nota")
            waitFor("Regenerar nota")
            tap("Guía de uso")
            tap("Guardar")
            compose.waitUntil(5000) { model.saved != null }
            val card = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals(legacy, card.usageNote)
            assertEquals(original.example, card.example)
            assertEquals(original.spanish, card.spanish)
        } finally {
            db.close()
        }
    }

    @Test fun guideFinishesAfterSavingAndStartingTheNextDraft() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        try {
            val model = VocabularyModel(app, db)
            compose.setContent {
                val saved = model.saved
                androidx.compose.runtime.key(saved?.card?.id) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        CardEditor(null, if (saved == null) "take off" else "", emptyList(), emptyList(), model,
                            lookup = { listOf(flight) }, nanoOptions = { _, _, _ -> emptyList() },
                            usageNotes = { _, _, _ ->
                                started.complete(Unit)
                                release.await()
                                UsageNoteResult.Ready(note)
                            }) {}
                    }
                }
            }
            waitFor(flight.definition)
            tap(flight.definition)
            compose.waitUntil { started.isCompleted }
            tap("Guardar y añadir otra")
            compose.waitUntil { model.saved?.next == AfterSave.AnotherCard }
            compose.onNodeWithText("Palabra o expresión en inglés").assertTextContains("")
            release.complete(Unit)
            compose.waitUntil(5000) {
                runBlocking { db.vocabulary().cards().first().singleOrNull()?.usageNote == note.forStorage() }
            }
            compose.onNodeWithText(note.meaning).assertDoesNotExist()
        } finally { release.complete(Unit); db.close() }
    }

    private fun showEditor(
        model: VocabularyModel,
        lookup: suspend (String) -> List<Suggestion>,
        original: com.example.diegovocabcards.data.Card? = null,
        generator: UsageNoteGenerator
    ) {
        compose.setContent {
            DiegoVocabCardsTheme(darkTheme = false) {
                Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CardEditor(original, "take off", emptyList(), emptyList(), model,
                        lookup = lookup, nanoOptions = { _, _, _ -> emptyList() }, usageNotes = generator) {}
                }
            }
        }
    }

    private fun tap(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()

    private fun waitFor(label: String, substring: Boolean = false) {
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
