package com.example.diegovocabcards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IpaFallbackTest {
    @get:Rule val compose = createComposeRule()
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val ipa get() = compose.onNodeWithText("Pronunciation (IPA)")

    @Test fun dictionaryPronunciationTakesPriorityOverNano() = withDb { db ->
        val word = Suggestion("To move on foot.", "verb", "fixture",
            pronunciation = DictionaryPronunciation("US /wɑk/ · UK /wɔːk/", "fixture"))
        val requests = mutableListOf<IpaContext>()
        compose.setContent {
            Editor(remember(db) { VocabularyModel(app, db) }, "walk", listOf(word), suggest = { requests.add(it); "/wɑk/" })
        }
        waitFor("/wɔːk/")
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun phraseWithoutDictionaryIpaSavesAndReopensForStudy() {
        val file = "ipa-reopen-${java.util.UUID.randomUUID()}"
        fun open() = Room.databaseBuilder(app, VocabularyDatabase::class.java, file).build()
        var db = open()
        var showing by mutableStateOf(true)
        var reviewModel by mutableStateOf<VocabularyModel?>(null)
        val editorModel = VocabularyModel(app, db)
        val sense = Suggestion("To lose one's job.", "verb", "fixture", spanish = listOf("ser despedido"))
        val requests = mutableListOf<IpaContext>()
        try {
            compose.setContent {
                if (showing) {
                    val review = reviewModel
                    if (review == null) Editor(editorModel, "get fired", listOf(sense),
                        suggest = { requests.add(it); "/get ˈfaɪəd/" })
                    else VocabCardsApp(Appearance.Light, review) {}
                }
            }
            waitFor("/get ˈfaɪəd/")
            tap("Card details")
            tap("Sources and licences")
            compose.onNodeWithText("Suggested by Gemini").assertExists()
            tap("Close")
            tap("Done")
            compose.onNodeWithText("Spanish translation").performScrollTo().performTextInput("ser despedido")
            ipa.performScrollTo()
            captureEmulator("ipa-gemini-editor-claro.png")
            tap("Save card")
            compose.waitUntil(5000) { editorModel.saved != null }
            compose.runOnIdle {
                assertEquals(listOf("get fired"), requests.map { it.expression })
                assertEquals(sense.definition, requests.single().definition)
                showing = false
            }
            compose.waitForIdle()
            db.close()
            db = open()
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("/get ˈfaɪəd/", saved.ipa)
            assertEquals(GEMINI_IPA, saved.ipaSource)
            val reopened = VocabularyModel(app, db)
            compose.runOnIdle { reviewModel = reopened; showing = true }
            waitFor("Start review")
            tap("Start review")
            waitFor("Show answer")
            compose.onNodeWithText("/get ˈfaɪəd/").assertDoesNotExist()
            tap("Show answer")
            compose.onNodeWithText("/get ˈfaɪəd/").assertExists()
        } finally {
            compose.runOnIdle { showing = false }
            compose.waitForIdle()
            db.close()
            app.deleteDatabase(file)
        }
    }

    @Test fun leadWaitsForChoiceAndSeparatesLateAnswersBySense() = withDb { db ->
        val metal = Suggestion("A heavy metal.", "noun", "fixture", spanish = listOf("plomo"))
        val guide = Suggestion("To guide someone.", "verb", "fixture", spanish = listOf("dirigir"))
        val requests = mutableListOf<String>()
        val metalAnswer = CompletableDeferred<String>()
        val guideAnswer = CompletableDeferred<String>()
        try {
            compose.setContent {
                Editor(remember(db) { VocabularyModel(app, db) }, "lead", listOf(metal, guide), suggest = {
                    requests.add(it.definition)
                    if (it.definition == metal.definition) metalAnswer.await() else guideAnswer.await()
                })
            }
            waitFor(metal.definition)
            compose.runOnIdle { assertTrue(requests.isEmpty()) }
            tap(metal.definition)
            compose.waitUntil(5000) { requests.contains(metal.definition) }
            tap("Change meaning")
            tap(guide.definition)
            metalAnswer.complete("/led/")
            compose.waitUntil(5000) { requests.contains(guide.definition) }
            compose.onNodeWithText("/led/").assertDoesNotExist()
            guideAnswer.complete("/liːd/")
            waitFor("/liːd/")
            tap("Change meaning")
            tap(metal.definition)
            waitFor("/led/")
            compose.runOnIdle { assertEquals(listOf(metal.definition, guide.definition), requests) }
        } finally {
            metalAnswer.cancel()
            guideAnswer.cancel()
        }
    }

    @Test fun manualCorrectionAndIntentionalClearSurviveLateResponseForAnOldCard() = withDb { db ->
        val original = runBlocking {
            db.vocabulary().save("older", "take off", "despegar", "", "", definition = "To leave the ground.")
            db.vocabulary().cards().first().single()
        }
        val started = CompletableDeferred<IpaContext>()
        val answer = CompletableDeferred<String>()
        val model = VocabularyModel(app, db)
        try {
            compose.setContent {
                Editor(model, "", emptyList(), original, suggest = { context ->
                    started.complete(context)
                    answer.await()
                })
            }
            compose.waitUntil(5000) { started.isCompleted }
            assertEquals(original.definition, runBlocking { started.await() }.definition)
            ipa.performScrollTo().performTextInput("/teɪk ɒf/")
            ipa.performTextClearance()
            answer.complete("/teɪk ˈɒf/")
            compose.waitForIdle()
            compose.onNodeWithText("/teɪk ˈɒf/").assertDoesNotExist()
            tap("Save changes")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("", saved.ipa)
            assertEquals(MANUAL_IPA, saved.ipaSource)
        } finally { answer.cancel() }
    }

    @Composable
    private fun Editor(
        model: VocabularyModel, term: String, suggestions: List<Suggestion>,
        original: Card? = null, suggest: suspend (IpaContext) -> String?
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CardEditor(original, term, emptyList(), emptyList(), model,
                lookup = { suggestions }, translate = { _, _ -> null },
                nanoOptions = { _, _, _ -> emptyList() }, usageNotes = { _, _, _ -> UsageNoteResult.Unavailable },
                ipaSuggestion = suggest) {}
        }
    }

    private fun tap(label: String) {
        val node = compose.onNodeWithText(label)
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }

    private fun waitFor(label: String) = compose.waitUntil(5000) {
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }

    private fun withDb(test: (VocabularyDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            test(db)
        } finally {
            db.close()
        }
    }
}
