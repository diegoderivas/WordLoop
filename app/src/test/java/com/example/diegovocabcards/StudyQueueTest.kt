package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule
import com.example.diegovocabcards.review.Direction.*
import com.example.diegovocabcards.review.studyQueue
import org.junit.Assert.*
import org.junit.Test

class StudyQueueTest {
    @Test fun returnsAtExactDueTimeAndKeepsTheInverseNew() {
        val cards = listOf(Card("1", "run", "correr", "", emptyList()))
        val rows = listOf(Schedule("1", ES_EN, 1, 60_000))
        val early = studyQueue(cards, rows, ES_EN, 59_999)
        assertEquals(60_000L, early.nextDue)
        assertTrue(early.ready.isEmpty())
        val due = studyQueue(cards, rows, ES_EN, 60_000)
        assertEquals(1, due.dueCount)
        assertEquals(1, due.ready.single().turn)
        assertNull(due.nextDue)
        assertEquals(1, studyQueue(cards, rows, EN_ES, 60_000).newCount)
    }

    @Test fun schedulesAllEligibleSensesWithOverdueFirst() {
        val cards = (1..250).map { Card(it.toString(), "run", "acepción $it", "", emptyList()) }
        val queue = studyQueue(cards, listOf(Schedule("200", EN_ES, 3, 80)), EN_ES, 100)
        assertEquals(250, queue.ready.size)
        assertEquals(249, queue.newCount)
        assertEquals("200", queue.ready.first().card.id)
    }
}
