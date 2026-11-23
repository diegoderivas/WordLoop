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

    @Test fun editingLegacyCardSuggestsOnlyItsMatchingSensesPronunciation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        try {
            val original = runBlocking {
                db.vocabulary().save("old", "record", "registro", "", "", definition = "An account.", senseCategories = "noun")
                db.vocabulary().cards().first().single()
            }
            val noun = Suggestion("An account.", "noun", "fixture",
                pronunciation = DictionaryPronunciation("US /ˈɹɛkɚd/ · UK /ˈɹɛkɔːd/", "fixture"))
            val verb = Suggestion("To preserve an account.", "verb", "fixture",
                pronunciation = DictionaryPronunciation("/ɹɪˈkɔːd/", "fixture"))
            val model = VocabularyModel(app, db)
            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CardEditor(original, "", emptyList(), emptyList(), model, lookup = { listOf(verb, noun) }) {}
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("/ˈɹɛkɔːd/").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save changes").performClick()
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("fixture", saved.ipaSource)
            assertEquals("/ˈɹɛkɔːd/", saved.ipa)
        } finally { db.close() }
    }

    @Test fun oldCardWithoutIpaKeepsManualCorrectionWhenLookupArrives() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val lookup = CompletableDeferred<List<Suggestion>>()
        try {
            val original = runBlocking {
                db.vocabulary().save("old", "fire", "fuego", "", "")
                db.vocabulary().cards().first().single()
            }
            val model = VocabularyModel(app, db)
            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CardEditor(original, "", emptyList(), emptyList(), model, lookup = { lookup.await() },
                        translate = { _, _ -> null }, nanoOptions = { _, _, _ -> emptyList() },
                        usageNotes = { _, _, _ -> UsageNoteResult.Unavailable }) {}
                }
            }
            compose.tapControl("Add IPA")
            val ipa = compose.onNodeWithText("Pronunciation (IPA)")
            ipa.performScrollTo().performTextInput("faɪə")
            val meaning = Suggestion("Flames and heat.", "noun", "fixture",
                pronunciation = DictionaryPronunciation("/faɪɹ/", "fixture"))
            lookup.complete(listOf(meaning))
            compose.waitUntil(5000) {
                compose.onAllNodesWithText(meaning.definition).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(meaning.definition).performScrollTo().performClick()
            ipa.assertTextContains("faɪə")
            compose.onNodeWithText("Save changes").performClick()
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals(original.id, saved.id)
            assertEquals("fuego", saved.spanish)
            assertEquals("/faɪə/", saved.ipa)
            assertEquals(MANUAL_IPA, saved.ipaSource)
        } finally {
            lookup.cancel()
            disposeTestContent()
            db.close()
        }
    }

    @Test fun bothOrdersPreferGeminiAndRespectAnExplicitlyClearedTranslation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val senses = listOf(
            Suggestion("Flames and heat.", "noun", "Dictionary", listOf("fuego")),
            Suggestion("End someone's employment.", "verb", "Dictionary", listOf("despedir")),
            Suggestion("Discharge a weapon.", "verb", "Dictionary")
        )
        val google = senses.associateWith { CompletableDeferred<String>() }
        val nano = senses.associateWith { CompletableDeferred<String>() }
        val model = VocabularyModel(app, db)
        try {
            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CardEditor(null, "fire", emptyList(), emptyList(), model, lookup = { senses },
                        translate = { _, s -> google.getValue(s).await() },
                        nanoOptions = { _, s, task ->
                            if (task == SenseRequest.Translation) listOf(nano.getValue(s).await()) else emptyList()
                        }, usageNotes = { _, _, _ -> UsageNoteResult.Unavailable }) {}
                }
            }
            val spanish = compose.onNodeWithText("Spanish translation")
            fun waitText(value: String) = compose.waitUntil(5000) {
                compose.onAllNodes(hasSetTextAction() and hasText(value)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText(senses.first().definition).fetchSemanticsNodes().isNotEmpty() }
            fun choose(i: Int) {
                if (i != 0) compose.onNodeWithText("Change meaning").performScrollTo().performClick()
                compose.onNodeWithText(senses[i].definition).performScrollTo().performClick()
            }
            choose(0)
            compose.onNodeWithText("Compare translations").performScrollTo().performClick()
            google.getValue(senses[0]).complete("incendio")
            waitText("incendio")
            nano.getValue(senses[0]).complete("llamas")
            waitText("llamas")
            choose(1)
            nano.getValue(senses[1]).complete("destituir")
            waitText("destituir")
            google.getValue(senses[1]).complete("echar")
            compose.waitUntil { compose.onAllNodesWithText("Use Google translation").fetchSemanticsNodes().isNotEmpty() }
            spanish.assertTextContains("destituir")
            choose(2)
            google.getValue(senses[2]).complete("disparar")
            waitText("disparar")
            spanish.performScrollTo().performTextClearance()
            nano.getValue(senses[2]).complete("hacer fuego")
            compose.waitUntil { compose.onAllNodesWithText("Use Gemini translation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save card").assertIsNotEnabled()
        } finally {
            (google.values + nano.values).forEach { it.cancel() }
            disposeTestContent()
            db.close()
        }
    }

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
            fun tap(label: String) = compose.tapControl(label)
            fun waitFor(label: String, substring: Boolean = false) {
                compose.waitUntil(5000) {
                    compose.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty()
                }
            }

            val english = compose.onNodeWithText("English word or phrase")
            english.performTextInput("hire")
            waitFor(work.definition)
            compose.onAllNodesWithText(work.definition).assertCountEquals(1)
            compose.onNodeWithText("Payment").assertDoesNotExist()
            compose.onNodeWithText("More meanings").assertExists()
            tap(work.definition)
            tap("Card details")
            tap("Choose another")
            compose.onNodeWithText("Choose an example").assertExists()
            compose.onNodeWithText("We hired more staff.").performClick()
            compose.onNodeWithText("We hired more staff.").assertExists()
            compose.onNode(hasText("employ") and isSelectable()).performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
            compose.onNode(hasText("employ") and isSelectable()).performClick().assertIsNotSelected()
            compose.onNodeWithText("Word class").assertExists()
            tap("Choose another")
            compose.onNodeWithText("Continue without an example").performClick()
            compose.onNodeWithText("We hired more staff.").assertDoesNotExist()
            tap("Done")
            tap("Change meaning")
            tap(rent.definition)
            compose.onNodeWithText("Keep my edits").performClick()
            tap("Card details")
            compose.onNodeWithText("Example").assertTextContains("")
            compose.onNode(hasText("employ") and isSelectable()).assertDoesNotExist()
            compose.onNodeWithText("rent").assertExists()
            tap("Done")
            tap("Change meaning")
            tap(incomplete.definition)
            compose.onNodeWithText("They hired a van.").assertDoesNotExist()
            compose.onNodeWithText("rent").assertDoesNotExist()
            compose.onNodeWithText("Save card").assertIsNotEnabled()
            english.performScrollTo().performTextReplacement("offline")
            waitFor("Lookup unavailable", substring = true)
            compose.onNodeWithText("Spanish translation").performScrollTo().performTextInput("sin conexión")
            tap("Save card")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("sin conexión", saved.spanish)
            assertEquals("", saved.definition)
            assertEquals("", saved.source)
        } finally {
            disposeTestContent()
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
            fun tap(label: String) = compose.tapControl(label)
            compose.waitUntil(5000) { compose.onAllNodesWithText(removal.definition).fetchSemanticsNodes().isNotEmpty() }
            tap(removal.definition)
            tap("Compare translations")
            compose.onNodeWithText("Translate with Google").assertExists()
            tap("Change meaning")
            tap(flight.definition)
            compose.waitUntil(5000) { firstStarted.isCompleted }
            tap("Change meaning")
            tap(breakTime.definition)
            val spanish = compose.onNodeWithText("Spanish translation")
            spanish.performScrollTo().performTextReplacement("mi corrección")
            secondResult.complete("tomarse un descanso")
            firstResult.complete("despegar")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("tomarse un descanso").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("despegar").assertDoesNotExist()
            spanish.assertTextContains("mi corrección")
            tap("Change meaning")
            tap(flight.definition)
            tap("Change meaning")
            tap(breakTime.definition)
            spanish.assertTextContains("mi corrección")
            assertEquals(3, googleCalls)
            tap("Use Google translation")
            tap("Save card")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("tomarse un descanso", saved.spanish)
            assertEquals(breakTime.definition, saved.definition)
            assertTrue(GOOGLE_TRANSLATION in saved.source)
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            disposeTestContent()
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
            fun tap(label: String) = compose.tapControl(label)
            fun waitFor(label: String) = compose.waitUntil(5000) {
                compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            val spanish = compose.onNodeWithText("Spanish translation")
            waitFor(flame.definition)
            tap(flame.definition)
            tap("Compare translations")
            compose.onNodeWithText("Suggest synonyms with Gemini").assertDoesNotExist()
            compose.waitUntil(5000) { started.isCompleted }
            tap("Change meaning")
            tap(dismiss.definition)
            delayed.complete("llama")
            spanish.performScrollTo().performTextReplacement("mi traducción")
            tap("Translate with Google")
            tap("Translate with Gemini")
            waitFor("destituir")
            compose.onNodeWithText("llama").assertDoesNotExist()
            compose.onNodeWithText("echar").assertExists()
            compose.onNodeWithText("Use dictionary translation · despedir").assertExists()
            spanish.assertTextContains("mi traducción")
            spanish.performScrollTo().performTouchInput { click() }
            captureEmulator("autocompletado-editor-teclado-claro.png")
            compose.onNodeWithText("Use Gemini translation").performScrollTo()
            captureEmulator("editor-propuestas-claro.png")
            tap("Use Google translation")
            tap("Use Gemini translation")
            spanish.assertTextContains("destituir")
            tap("Card details")
            waitFor("sack")
            tap("sack")
            compose.onNodeWithText("Synonyms (comma-separated)").assertTextContains("sack")
            tap("Done")
            tap("Save card")
            compose.waitUntil(5000) { model.saved != null }
            val saved = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("destituir", saved.spanish)
            assertTrue(GEMINI_TRANSLATION in saved.source && GOOGLE_TRANSLATION !in saved.source)
            assertTrue(GEMINI_SYNONYMS in saved.source)
            compose.runOnIdle { model.acknowledgeSave() }
            compose.onNodeWithText("English word or phrase").performScrollTo().performTextReplacement("get fired")
            waitFor(dismissed.definition)
            tap(dismissed.definition)
            compose.onNodeWithText("Keep my edits").performClick()
            tap("Translate with Gemini")
            tap("Use Gemini translation")
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
            compose.onNodeWithText("English word or phrase").performTextInput("run")
            compose.waitUntil(5000) { started.get() }
            compose.onNodeWithText("English word or phrase").performTextReplacement("look up")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Definition for look up").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Definition for run").assertDoesNotExist()
            compose.onNodeWithText("Card details").performScrollTo().performClick()
            compose.onNodeWithText("English definition").performScrollTo().performTextInput("My own definition")
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Definition for look up").performScrollTo().performClick()
            compose.onNodeWithText("Keep my edits").performClick()
            compose.onNodeWithText("Card details").performScrollTo().performClick()
            compose.onNodeWithText("My own definition").assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Definition for look up").performScrollTo().performClick()
            compose.onNodeWithText("Use suggestion").performClick()
            compose.onNodeWithText("Spanish translation").performScrollTo().performTextInput("buscar")
            compose.onNodeWithText("Save card").performClick()
            compose.waitUntil(5000) { model.saved != null }
            val card = runBlocking { db.vocabulary().cards().first().single() }
            assertEquals("Definition for look up", card.definition)
            assertEquals("Example for look up", card.example)
            assertTrue(card.source.contains("TEST DATA"))
        } finally {
            disposeTestContent()
            db.close()
        }
    }
}
