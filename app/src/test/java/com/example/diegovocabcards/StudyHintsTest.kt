package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card
import org.junit.Assert.*
import org.junit.Test

class StudyHintsTest {
    private val fire = Card("a", "fire", "fuego", "", emptyList(), senseCategories = "noun")
    private val dismiss = fire.copy(id = "b", spanish = "despedir", senseCategories = "verb")

    @Test fun ambiguousEnglishUsesTheSenseCategory() {
        assertEquals("Sustantivo", studyHint(fire, listOf(fire, dismiss), false))
        assertEquals("Verbo", studyHint(dismiss, listOf(fire, dismiss), false))
        assertNull(studyHint(fire, listOf(fire, dismiss), true))
    }

    @Test fun sameCategoryUsesOnlyDistinctContextWithoutTheHiddenAnswers() {
        val first = dismiss.copy(spanish = "despedir", definition = "End an employment contract.")
        val second = first.copy(id = "c", english = "dismiss", definition = "Fire someone from a job.")
        assertEquals(first.definition, studyHint(first, listOf(first, second), true))
        assertNull(studyHint(second, listOf(first, second), true))
        assertNull(studyHint(first, listOf(first, second.copy(definition = first.definition)), true))
    }
}
