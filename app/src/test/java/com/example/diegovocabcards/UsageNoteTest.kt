package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class UsageNoteTest {
    @Test fun requestUsesOnlyTheSelectedSenseAndExplicitlyRequiresEnglish() {
        val flight = Suggestion("To begin a flight.", "verb", "SOURCE_ONLY",
            examples = listOf("The plane took off."), spanish = listOf("despegar"))
        val removing = Suggestion("To remove clothing.", "verb", "SOURCE_ONLY",
            examples = listOf("Take off your coat."))
        val work = Suggestion("To lose your job.", "phrase", "SOURCE_ONLY")
        val cases = listOf("take off" to flight, "take off" to removing, "get fired" to work)
        for ((expression, sense) in cases) {
            val prompt = usageNotePrompt(expression, sense)
            assertTrue("Expression: $expression" in prompt)
            assertTrue("EVERY field in clear English" in prompt)
            assertTrue("Category: ${sense.category}" in prompt)
            assertTrue(sense.definition in prompt)
            assertTrue(sense.examples.all { it in prompt })
            assertTrue(cases.filter { it.second != sense }.none { it.second.definition in prompt })
            assertFalse(sense.source in prompt)
        }
    }

    @Test fun displaysStoredGuideSectionsAndLimitsSynonyms() {
        val note = parseUsageNote("""
            MEANING: A word can have several meanings. This note covers one of them.
            SYNONYMS: choose, select, Choose, decide, pick, opt, settle on, take
            HOW TO USE IT: Use the term when comparing options.
            EXAMPLE 1: I will choose later.
        """.trimIndent())!!
        assertEquals(listOf("choose", "select", "decide", "pick", "opt", "settle on"), note.synonyms)
        val sections = storedGuideSections(note.forStorage(), listOf("prefer"))
        assertEquals(listOf("Meaning", "Synonyms", "How to use it", "Examples"), sections.map { it.title })
        assertEquals(listOf("prefer", "choose", "select", "decide", "pick", "opt"), sections[1].body)
        assertEquals(listOf("I will choose later."), sections.last().body)
        assertEquals(listOf("A personal note."), storedGuideSections("A personal note.", emptyList()).single().body)
        assertEquals("Pick one.", guideListItem("2. Pick one."))
    }

    @Test fun parsesEnglishSectionsAndOmitsEmptyOrPlaceholderFields() {
        val output = """
            MEANING: A plane takes off when it leaves the ground. The phrase marks the start of a flight.
            HOW TO USE IT: Say take off from a runway or take off for a destination.
            NUANCE: (omit)
            SIMILAR WORDS: Land marks the end of the flight.
            EXAMPLE 1: We took off before dawn.
            EXAMPLE 2: The rescue pilot took off during a storm.
            EXAMPLE 3: The spacecraft took off as the crowd cheered.
        """.trimIndent()
        val result = parseUsageNote(output)!!
        assertEquals(listOf("Meaning", "How to use it", "Similar words"), result.sections().map { it.first })
        assertEquals(3, result.examples.size)
        assertNull(result.nuance)
        assertFalse("(omit)" in result.forStorage())
        assertEquals(2, parseUsageNote(output.replace("The spacecraft took off as the crowd cheered.", "We took off before dawn."))!!.examples.size)
        assertNull(parseUsageNote(output.replace(" The phrase marks the start of a flight.", "")))
        assertNull(parseUsageNote(output.lineSequence().filterNot { it.startsWith("HOW TO USE IT:") }.joinToString("\n")))
        assertNull(parseUsageNote("I cannot help with this."))
    }
}
