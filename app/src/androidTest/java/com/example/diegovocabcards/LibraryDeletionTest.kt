package com.example.diegovocabcards

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
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

class LibraryDeletionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeDeletesOnlyOneSenseAndFailureAllowsRetry() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            runBlocking {
                db.vocabulary().save("flame", "fire", "fuego", "", "noun")
                db.vocabulary().save("job", "fire", "despedir", "", "verb")
                for (direction in Direction.entries) {
                    db.reviews().rate("job", direction, 0, Rating.GOOD, now)
                    db.reviews().rate("flame", direction, 0, Rating.HARD, now)
                }
            }
            val model = VocabularyModel(app, db)
            compose.setContent { VocabCardsApp(Appearance.Light, model) {} }
            compose.onNodeWithText("Library").performClick()
            compose.onNodeWithContentDescription("Delete meaning").assertDoesNotExist()
            compose.onNodeWithText("despedir").performTouchInput { swipeLeft() }
            val trash = compose.onNodeWithContentDescription("Delete meaning")
            trash.assertIsDisplayed()
            captureEmulator("biblioteca-borrar-claro.png")
            db.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER prevent_delete BEFORE DELETE ON Sense BEGIN SELECT RAISE(ABORT, 'test'); END"
            )
            trash.performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Could not delete the card.", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, runBlocking { db.vocabulary().cards().first().size })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER prevent_delete")
            val actions = compose.onNodeWithText("despedir").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            compose.runOnIdle { check(actions.single { it.label == "Delete meaning" }.action()) }
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("despedir").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("fuego").assertIsDisplayed()
            val kept = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("flame", kept.id)
            assertEquals(listOf("noun"), kept.tags)
            for (direction in Direction.entries) {
                assertNull(runBlocking { db.reviews().latest("job", direction) })
                assertNotNull(runBlocking { db.reviews().latest("flame", direction) })
            }
        } finally { db.close() }
    }
}
