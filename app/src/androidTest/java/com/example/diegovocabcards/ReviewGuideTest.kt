package com.example.diegovocabcards

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.review.Direction
import io.github.openspacedrepetition.Rating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ReviewGuideTest {
    @get:Rule val compose = createComposeRule()
    private val note = UsageNote("You lose your job. Your employer decides you must leave.",
        "Use get fired from a job or get fired for doing something.", listOf("He got fired last week."))

    private fun tap(label: String) {
        val node = compose.onNodeWithText(label)
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }

    @Test fun editRetryGenerateAndReopenKeepBothSchedulesAndAllOtherFields() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val name = "review-guide-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(app, VocabularyDatabase::class.java, name).build()
        var db = open()
        try {
            val oldTime = System.currentTimeMillis() - 90L * 86_400_000
            runBlocking {
                db.vocabulary().save("job", "get fired", "ser despedido", "She got fired.", "work",
                    definition = "Lose one's employment.", senseCategories = "phrase", source = "Dictionary",
                    generatedExamples = "Bill got fired yesterday.", ipa = "/test/", ipaSource = "Test")
                for (direction in Direction.entries) db.reviews().rate("job", direction, 0, Rating.GOOD, oldTime)
            }
            fun history() = runBlocking { Direction.entries.map { db.reviews().latest("job", it) } }
            val previous = history()
            val schedules = runBlocking { db.reviews().schedules().first() }
            var active by mutableStateOf<VocabularyModel?>(VocabularyModel(app, db))
            var calls = 0
            val generator = UsageNoteGenerator { prompt, _, _ ->
                assertTrue("Expression: get fired" in prompt && "Lose one's employment." in prompt)
                assertTrue("que te despidan" in prompt)
                if (++calls == 1) UsageNoteResult.Unavailable else UsageNoteResult.Ready(note)
            }
            compose.setContent { active?.let { key(it) { VocabCardsApp(Appearance.Light, it, guideGenerator = generator) {} } } }
            tap("Repasar en inglés")
            compose.onNodeWithText("/test/").assertDoesNotExist()
            tap("Mostrar respuesta")
            compose.onNodeWithText("/test/").assertExists()
            tap("Editar tarjeta")
            compose.onNodeWithText("Traducción al español").performScrollTo().performTextReplacement("que te despidan")
            tap("Guardar")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Fácil").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("que te despidan").assertExists()
            compose.onNodeWithText("Mostrar respuesta").assertDoesNotExist()
            val before = runBlocking { db.vocabulary().cards().first().single() }
            val book = compose.onNodeWithContentDescription("Guía de uso")
            book.performScrollTo().performClick()
            tap("Reintentar")
            compose.waitUntil(5000) { compose.onAllNodesWithText(note.forStorage()).fetchSemanticsNodes().isNotEmpty() }
            tap("Volver al repaso")
            assertEquals(before.copy(usageNote = note.forStorage()), runBlocking { db.vocabulary().cards().first().single() })
            assertEquals(previous, history())
            assertEquals(schedules, runBlocking { db.reviews().schedules().first() })

            compose.runOnIdle { active = null }
            compose.waitForIdle()
            db.close()
            db = open()
            compose.runOnIdle { active = VocabularyModel(app, db) }
            tap("Repasar en español")
            compose.onNodeWithText("/test/").assertExists()
            tap("Mostrar respuesta")
            book.performScrollTo().performClick()
            compose.onNodeWithText(note.forStorage()).assertExists()
            assertEquals(2, calls)
            assertEquals(previous, history())
        } finally {
            db.close()
            app.deleteDatabase(name)
        }
    }

    @Test fun lateGuideCannotOverwriteNewMeaningOrExistingGuide() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            val model = VocabularyModel(app, db)
            runBlocking {
                val dao = db.vocabulary()
                dao.save("sense", "fire", "fuego", "", "")
                val original = dao.cards().first().single()
                dao.save("sense", "fire", "despedir", "", "")
                assertFalse(model.saveMissingGuide(original, note))
                val updated = dao.cards().first().single()
                assertTrue(model.saveMissingGuide(updated, note))
                assertFalse(model.saveMissingGuide(updated, note.copy(usage = "Stale response")))
                assertEquals(note.forStorage(), dao.cards().first().single().usageNote)
                assertTrue(db.reviews().schedules().first().isEmpty())
            }
        } finally { db.close() }
    }
}
