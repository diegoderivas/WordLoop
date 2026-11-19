package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class UsageNoteTest {
    @Test fun requestContainsFullExpressionAndOnlyContextForThisSense() {
        val sense = Suggestion("To begin a flight.", "verb", "SOURCE_ONLY",
            examples = listOf("The plane took off.", "We took off at dawn."))
        val prompt = usageNotePrompt("take off", sense)
        assertTrue("Expression: take off" in prompt)
        assertTrue("Category: Verbo" in prompt)
        assertTrue(sense.definition in prompt)
        assertTrue(sense.examples.all { it in prompt })
        assertFalse(sense.source in prompt)
    }

    @Test fun validatesTwoDistinctExamplesAndOptionalNuance() {
        val short = "USO: Sirve para hablar del comienzo del vuelo.\nEJEMPLO 1: We took off early.\nEJEMPLO 2: The plane took off late."
        val result = parseUsageNote("$short\nMATIZ: Land indica el final del vuelo.")!!
        assertEquals("Sirve para hablar del comienzo del vuelo.", result.explanation)
        assertEquals(listOf("We took off early.", "The plane took off late."), result.examples)
        assertEquals("Land indica el final del vuelo.", result.nuance)
        assertNull(parseUsageNote(short)?.nuance)
        assertNull(parseUsageNote(short.substringBefore("\nEJEMPLO 2:")))
        assertNull(parseUsageNote(short.replace("The plane took off late.", "We took off early.")))
    }
}
