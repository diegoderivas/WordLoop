package com.example.diegovocabcards

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalTranslationProbe {
    @Test fun contextualEquivalentsOnDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTranslation") == "true")
        for ((expression, meaning, expected) in listOf(
            Triple("get fired", Suggestion("dismissed, terminated from employment.", "phrase", ""), "ser despedido"),
            Triple("take off", Suggestion("To leave the ground and ascend into the air or into flight.", "verb", ""), "despegar"),
            Triple("look up", Suggestion("To obtain, or seek to obtain, information about something.", "verb", ""), "buscar")
        )) {
            assertEquals(expression, expected, LocalSenseTranslation.suggest(expression, meaning))
        }
    }
}
