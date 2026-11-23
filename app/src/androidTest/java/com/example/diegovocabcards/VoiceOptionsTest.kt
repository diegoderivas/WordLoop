package com.example.diegovocabcards

import android.speech.tts.Voice
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class VoiceOptionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exposesSelectedVoiceAndKeepsPreviewSeparate() {
        val selected = mutableStateOf("uk-voice-id")
        var previewed = ""
        val player = object : PronunciationPlayer {
            override val state = SpeechState.Idle
            override val requestedText: String? = null
            override val voices = listOf(
                Voice("uk-voice-id", Locale.UK, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()),
                Voice("us-voice-id", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
            )
            override fun speak(text: String) = Unit
            override fun sample(voiceName: String) { previewed = voiceName }
            override fun stop() = Unit
        }
        compose.setContent {
            DiegoVocabCardsTheme(darkTheme = false) {
                CompositionLocalProvider(LocalPronunciationPlayer provides player) {
                    VoiceOptions(selected.value) { selected.value = it }
                }
            }
        }
        compose.onNodeWithText("English voice").performClick()
        compose.onNode(hasText("British English") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("American English") and isSelectable()).assertIsNotSelected()
        captureEmulator("ui-audit-voice-light.png")
        compose.onNodeWithContentDescription("Preview American English").performClick()
        compose.runOnIdle {
            assertEquals("us-voice-id", previewed)
            assertEquals("uk-voice-id", selected.value)
        }
        compose.onNode(hasText("American English") and isSelectable()).performClick()
        compose.onNodeWithText("American English").assertIsDisplayed()
        compose.runOnIdle { assertEquals("us-voice-id", selected.value) }
    }
}
