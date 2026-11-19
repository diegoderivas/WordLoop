package com.example.diegovocabcards

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PronunciationTest {
    @Test fun prefersInstalledBritishEnglishThenOtherLocalEnglish() {
        fun voice(name: String, locale: Locale, network: Boolean = false, features: Set<String> = emptySet()) =
            Voice(name, locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network, features)
        val uk = voice("uk", Locale.UK)
        val us = voice("us", Locale.US)
        val online = voice("online", Locale.UK, network = true)
        val missing = voice("missing", Locale.UK, features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val spanish = voice("spanish", Locale.forLanguageTag("es-AR"))
        assertEquals(listOf(uk, us), localEnglishVoices(setOf(us, online, missing, spanish, uk)))
        val au = voice("au", Locale.forLanguageTag("en-AU"))
        assertEquals(au, localEnglishVoices(setOf(uk, us, au), "au").first())
        assertEquals(us, localEnglishVoices(setOf(uk, us, au), "us").first())
        assertEquals(uk, localEnglishVoices(setOf(uk, us), "au").first())
        assertEquals(listOf(us), localEnglishVoices(setOf(online, missing, us)))
        assertTrue(localEnglishVoices(setOf(online, missing, spanish)).isEmpty())
    }

    @Test fun platformEngineFinishesOfflineOrReportsMissingLocalVoice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outcome = withContext(Dispatchers.Main) {
            val player = AndroidPronunciation(instrumentation.targetContext.applicationContext)
            try {
                player.speak("Take off")
                withTimeout(15_000L) {
                    while (player.state == SpeechState.Loading || player.state == SpeechState.Speaking) delay(50L)
                    player.state
                }
            } finally {
                player.release()
            }
        }
        assertTrue(outcome == SpeechState.Idle || outcome == SpeechState.Unavailable)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nLocal Android speech: $outcome\n") })
    }
}
