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

    @Test fun mixedQueueOrdersLearningThenOverdueThenNewAndSpacesTheReverse() {
        val cards = listOf("c", "a", "b").map { Card(it, it, it, "", emptyList()) }
        val rows = listOf(
            Schedule("a", EN_ES, 2, 75, io.github.openspacedrepetition.State.RELEARNING),
            Schedule("a", ES_EN, 1, 60, io.github.openspacedrepetition.State.LEARNING),
            Schedule("b", EN_ES, 4, 15), Schedule("c", ES_EN, 3, 10), Schedule("c", EN_ES, 1, 101)
        )
        val queue = studyQueue(cards, rows, null, 100)
        assertEquals(listOf("a" to ES_EN, "a" to EN_ES, "c" to ES_EN, "b" to EN_ES, "b" to ES_EN),
            queue.ready.map { it.card.id to it.direction })
        assertEquals(4, queue.dueCount)
        assertEquals(1, queue.newCount)
        assertEquals(101L, queue.nextDue)
        assertEquals("c", queue.nextAfter("a")!!.card.id)
        assertEquals(listOf("ES_EN:1:a", "ES_EN:3:c", "EN_ES:2:a", "EN_ES:4:b", "ES_EN:0:b"),
            queue.quickBatch())
        val later = studyQueue(cards, rows, null, 101)
        assertTrue("EN_ES:1:c" in later.quickBatch())
        assertFalse("EN_ES:1:c" in queue.quickBatch())
        val alone = studyQueue(cards.filter { it.id == "a" }, rows, null, 100)
        assertNotNull(alone.nextAfter("a"))
        assertEquals(queue, studyQueue(cards.reversed(), rows.reversed(), null, 100))
    }

    @Test fun schedulesAllEligibleSensesWithOverdueFirst() {
        val cards = (1..250).map { Card(it.toString(), "run", "acepción $it", "", emptyList()) }
        val queue = studyQueue(cards, listOf(Schedule("200", EN_ES, 3, 80)), EN_ES, 100)
        assertEquals(250, queue.ready.size)
        assertEquals(249, queue.newCount)
        assertEquals(10, queue.quickBatch().size)
        assertEquals("EN_ES:3:200", queue.quickBatch().first())
        assertEquals("200", queue.ready.first().card.id)
    }
}
