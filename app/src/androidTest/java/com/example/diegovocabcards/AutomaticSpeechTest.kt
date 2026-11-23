package com.example.diegovocabcards

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.diegovocabcards.data.Card
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AutomaticSpeechTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewButtonsLeavePreferenceAloneUntilVoiceIsChosen() {
        val samples = mutableListOf<String>()
        val selections = mutableListOf<String>()
        val player = object : PronunciationPlayer {
            override val voices = listOf("en-GB", "en-US", "en-AU").map { name ->
                android.speech.tts.Voice(name, java.util.Locale.forLanguageTag(name), 300, 200, false, emptySet())
            }
            override val state = SpeechState.Idle
            override val requestedText: String? = null
            override fun speak(text: String) = error("Expected a sample")
            override fun sample(voiceName: String) { samples.add(voiceName) }
            override fun stop() { }
        }
        compose.setContent {
            CompositionLocalProvider(LocalPronunciationPlayer provides player) {
                VoiceOptions("en-GB") { selections.add(it) }
            }
        }
        compose.onNodeWithText("English voice").performClick()
        for (label in listOf("British English", "American English", "Australian English")) {
            compose.onNodeWithContentDescription("Preview $label").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf("en-GB", "en-US", "en-AU"), samples)
            assertEquals(emptyList<String>(), selections)
        }
        compose.onNodeWithText("Australian English").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("en-AU"), selections) }
    }

    @Test fun presentationStopsPriorAudioAndSpeaksOnceAtTheCorrectMoment() {
        val events = mutableListOf<String>()
        val player = object : PronunciationPlayer {
            override val state = SpeechState.Idle
            override val requestedText: String? = null
            override fun sample(voiceName: String) = error("Unexpected preview during study")
            override fun speak(text: String) { events.add(text) }
            override fun stop() { events.add("stop") }
        }
        val card = Card("a", "fire", "fuego", "", emptyList())
        var turn by mutableIntStateOf(0)
        var revealed by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalPronunciationPlayer provides player, LocalAutomaticSpeech provides enabled) {
                key(turn) {
                    StudyPresentation(if (turn == 1) card.copy(id = "b", english = "run") else card,
                        spanishFirst = turn == 0, revealed, "Turno $turn", onReveal = {}) {}
                }
            }
        }
        compose.runOnIdle { assertEquals(emptyList<String>(), events); revealed = true }
        compose.runOnIdle { assertEquals(listOf("fire"), events); turn++; revealed = false }
        compose.runOnIdle { assertEquals(listOf("fire", "stop", "run"), events); revealed = true }
        compose.runOnIdle { assertEquals(3, events.size); turn++ }
        compose.runOnIdle { assertEquals(listOf("fire", "stop", "run", "stop", "fire"), events); enabled = false; turn++ }
        compose.runOnIdle { assertEquals("stop", events.last()); assertEquals(6, events.size) }
    }
}
