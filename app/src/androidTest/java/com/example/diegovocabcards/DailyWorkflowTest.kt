package com.example.diegovocabcards

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.LibrarySettings
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DailyWorkflowTest {
    @get:Rule val compose = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun createFindEditPracticeReviewExportAndRestoreThroughAndroidPicker() {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val showing = mutableStateOf(true)
        try {
            runBlocking { db.vocabulary().save("job", "fire", "despedir", "", "trabajo", senseCategories = "verb") }
            val model = VocabularyModel(app, db)
            compose.setContent {
                if (showing.value) DiegoVocabCardsTheme(darkTheme = false) {
                    VocabCardsApp(Appearance.Light, model, dictionaryLookup = { error("Offline dictionary fixture") }) {}
                }
            }
            tap("Add card")
            enter("English word or phrase", "fire")
            compose.onNodeWithText("View cards (1)", substring = true).assertExists()
            waitFor("Lookup unavailable. You can enter this card yourself.")
            compose.onNodeWithText("Required to save the card.").performScrollTo().assertIsDisplayed()
            captureEmulator("ui-audit-editor-offline-light.png")
            enter("Spanish translation", "fuego")
            compose.onNodeWithText("Save card").assertIsDisplayed().assertIsEnabled()
            captureEmulator("ui-audit-editor-keyboard-light.png")
            tap("Card details")
            enter("New tag", "calor")
            tap("Done")
            tap("Save card")
            waitFor("Search English or Spanish")
            compose.onNodeWithText("Search English or Spanish").performTextInput("fue")
            tap("fuego")
            tap("Edit card")
            compose.onNodeWithText("Spanish translation").performScrollTo().performTextReplacement("fuego o incendio")
            tap("Save changes")
            waitFor("Search English or Spanish")
            compose.onNodeWithText("fuego o incendio").assertExists()
            tap("Filters")
            tap("All tags")
            compose.onAllNodesWithText("calor").filter(hasAnyAncestor(isPopup())).onFirst().performClick()
            tap("Done")
            tap("Practise this tag")
            tap("English → Spanish")
            tap("Start practice")
            compose.onNodeWithText("fuego o incendio").assertDoesNotExist()
            tap("Show answer")
            compose.onNodeWithText("fuego o incendio").assertExists()
            tap("Next")
            waitFor("Practice complete")
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("Practise this tag")
            compose.onNodeWithText("despedir").assertDoesNotExist()
            assertTrue(runBlocking { db.backup().events().isEmpty() })
            tap("Home")
            tap("Advanced options")
            tap("English → Spanish")
            tap("Show answer")
            tap("Good")
            compose.waitUntil(10000) { runBlocking { db.backup().countEvents() == 1 } }
            compose.onNodeWithContentDescription("Back").performClick()
            tap("Library")
            tap("fuego o incendio")
            tap("View history")
            waitFor("Recent history")
            captureEmulator("respaldo-progreso-claro.png")
            tap("Close")
            compose.onNodeWithContentDescription("Settings").performClick()
            tap("Export backup")
            val name = "VocabCards-test-${UUID.randomUUID()}.dvc"
            val filename = systemNode { it.isEditable }
            assertTrue(filename.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            systemClick(systemNode { it.text?.toString()?.lowercase() in setOf("save", "guardar") })
            waitFor("Backup exported.")
            val before = runBlocking { db.backup().snapshot(LibrarySettings()) }
            runBlocking { db.vocabulary().deleteSense("job") }
            tap("Restore backup")
            val document = systemNode { it.text?.toString() == name }
            systemClick(document)
            waitFor("Replace library")
            compose.onNodeWithText("Export current library first").assertExists()
            captureEmulator("ui-audit-restore-light.png")
            tap("Replace library")
            waitFor("Library restored.")
            assertEquals(before, runBlocking { db.backup().snapshot(LibrarySettings()) })
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun enter(label: String, value: String) =
        compose.onNodeWithText(label).performScrollTo().performTextInput(value)

    private fun waitFor(text: String) = compose.waitUntil(15000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun tap(text: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNode(hasText(text) and hasClickAction())
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.assertIsEnabled().performClick()
    }

    private fun systemNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
            return null
        }
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                if (root.packageName?.contains("documentsui") == true) find(root)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        error("No se encontró el control en el selector de archivos de Android")
    }

    private fun systemClick(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        error("El selector no permitió tocar el documento")
    }
}
