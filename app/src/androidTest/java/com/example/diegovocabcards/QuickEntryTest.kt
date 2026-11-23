package com.example.diegovocabcards

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QuickEntryTest {
    @get:Rule val compose = createComposeRule()

    private fun click(text: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNode(hasText(text) and hasClickAction())
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }
    private fun enter(label: String, value: String) =
        compose.onNodeWithText(label).performScrollTo().performTextInput(value)

    @Test fun consecutiveSavesCreateSeparateDraftsAndCarryOnlyChosenTags() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val dao = db.vocabulary()
        try {
            runBlocking { dao.save("seed", "hello", "hola", "", "diario") }
            val model = VocabularyModel(app, db)
            compose.setContent { VocabCardsApp(Appearance.System, model) {} }
            click("Library")
            click("Add card")
            val terms = listOf("brush up on", "look up", "run")
            val meanings = listOf("refrescar", "buscar", "correr")
            for (i in terms.indices) {
                compose.onNodeWithText("Save card").assertIsNotEnabled()
                enter("English word or phrase", terms[i])
                enter("Spanish translation", meanings[i])
                if (i == 0) {
                    click("Card details")
                    enter("Example", "I brush up on my English.")
                    enter("New tag", "personal")
                    click("Done")
                    db.openHelper.writableDatabase.execSQL(
                        "CREATE TEMP TRIGGER reject_save BEFORE INSERT ON Sense BEGIN SELECT RAISE(ABORT, 'test'); END"
                    )
                    click("Save and add another")
                    compose.waitUntil(5000) { model.failedId != null }
                    compose.onNodeWithText("refrescar").assertExists()
                    click("Card details")
                    compose.onNodeWithText("personal").assertExists()
                    click("Done")
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_save")
                }
                if (i == 1) {
                    click("Card details")
                    compose.onNodeWithText("I brush up on my English.").assertDoesNotExist()
                    compose.onNodeWithText("personal").assertExists()
                    click("diario")
                    click("Done")
                }
                click(if (i == terms.lastIndex) "Save card" else "Save and add another")
                compose.waitUntil(5000) { !model.saving && model.saved == null }
            }
            compose.onNodeWithText("correr").assertExists()
            val created = runBlocking { dao.cards().first() }.filter { it.id != "seed" }.reversed()
            assertEquals(terms, created.map { it.english })
            assertEquals(meanings, created.map { it.spanish })
            assertEquals(listOf("", ""), created.drop(1).map { it.example })
            assertEquals(listOf("diario", "personal"), created.first().tags.sorted())
            assertEquals(listOf(listOf("personal"), listOf("personal")), created.drop(1).map { it.tags })
        } finally {
            db.close()
        }
    }
}
