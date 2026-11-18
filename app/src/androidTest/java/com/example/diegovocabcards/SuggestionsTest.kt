package com.example.diegovocabcards

import android.app.Application
import androidx.compose.foundation.layout.Column
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

    @Test fun staleResultsAndManualCorrectionsAreProtected() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val model = VocabularyModel(app, db.vocabulary())
        val started = java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CardEditor(null, "", emptyList(), emptyList(), model, lookup = { query ->
                    if (query == "run") {
                        started.set(true)
                        withContext(NonCancellable) { delay(1500) }
                    }
                    listOf(Suggestion("Definition for $query", "Example for $query", "", "verb", "TEST DATA"))
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
