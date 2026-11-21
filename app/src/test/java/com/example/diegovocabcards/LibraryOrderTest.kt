package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule
import com.example.diegovocabcards.review.Direction.*
import io.github.openspacedrepetition.State.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    private val cards = listOf(
        Card("job", "fire", "despedir", "", listOf("verbs")),
        Card("flame", "fire", "fuego", "", listOf("nouns")),
        Card("flower", "bloom", "florecer", "", listOf("verbs")))
    private val schedules = listOf(Schedule("job", EN_ES, 1, 10, LEARNING), Schedule("job", ES_EN, 2, 90, REVIEW),
        Schedule("flame", EN_ES, 1, 80, REVIEW), Schedule("flame", ES_EN, 1, 20, LEARNING))
    @Test fun stateAndDirectionMustMatchTheSameHistory() {
        val rows = organiseLibrary(cards, schedules, "fire", "nouns", ES_EN, "Learning", LibraryOrder.Due)
        assertEquals(listOf("flame"), rows.map { it.card.id })
        assertEquals(listOf(ES_EN), rows.single().sides.map { it.direction })
        assertEquals(emptyList<LibraryRow>(), organiseLibrary(cards, schedules, "despedir", null, ES_EN, "Learning", LibraryOrder.Due))
    }
    @Test fun sortingUsesMatchingSidesAndStableInsertionOrder() {
        fun ids(direction: com.example.diegovocabcards.review.Direction?, order: LibraryOrder) =
            organiseLibrary(cards, schedules, "", null, direction, null, order).map { it.card.id }
        assertEquals(listOf("job", "flame", "flower"), ids(null, LibraryOrder.Due))
        assertEquals(listOf("flame", "job", "flower"), ids(ES_EN, LibraryOrder.Due))
        assertEquals(cards.map { it.id }, ids(null, LibraryOrder.Newest))
        assertEquals(cards.reversed().map { it.id }, ids(null, LibraryOrder.Oldest))
        assertEquals("flower", ids(null, LibraryOrder.Alphabetical).first())
        assertEquals(listOf("New","New"), organiseLibrary(cards, schedules, "florecer", null, null, "New",
            LibraryOrder.Due).single().sides.map { it.status })
    }
}
