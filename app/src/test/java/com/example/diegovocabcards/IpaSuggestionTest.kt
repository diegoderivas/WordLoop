package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class IpaSuggestionTest {
    @Test fun acceptsOneBarePhonemicTranscriptionAndRejectsExplanations() {
        assertEquals("/get ˈfaɪəd/", parseSuggestedIpa("  /get ˈfaɪəd/\n"))
        assertEquals("/led/", parseSuggestedIpa("/led/"))
        listOf("NONE", "IPA: /led/", "/led/ means the metal", "[led]", "/led/ or /liːd/",
            "/led/\n/liːd/", "`/led/`", "/  /", "/ˈ/", "/lead (noun)/").forEach {
            assertNull(it, parseSuggestedIpa(it))
        }
    }

    @Test fun unresolvedSingleWordAndFailedLookupWithoutContextDoNotAskNano() {
        assertNull(ipaContext("lead", null, emptyList(), null, lookupSucceeded = true))
        assertNull(ipaContext("get fired", null, emptyList(), null, lookupSucceeded = false))
        assertEquals(IpaContext("get fired"),
            ipaContext("get fired", null, emptyList(), null, lookupSucceeded = true))
    }

    @Test fun ambiguousEntryWaitsForChoiceAndPromptUsesTheFullExpression() {
        val metal = Suggestion("A heavy metal.", "noun", "fixture", spanish = listOf("plomo"))
        val verb = Suggestion("To guide someone.", "verb", "fixture")
        assertNull(ipaContext("lead", null, listOf(metal, verb), null, lookupSucceeded = true))
        val context = ipaContext("lead", metal, listOf(metal, verb), null, lookupSucceeded = true)!!
        val prompt = ipaPrompt(context)
        assertTrue(prompt.contains("British English"))
        assertTrue(prompt.contains("Expression: lead"))
        assertTrue(prompt.contains("Definition: A heavy metal."))
        assertFalse(prompt.contains(verb.definition))
        assertTrue(ipaPrompt(IpaContext("get fired")).contains("Expression: get fired"))
    }
}
