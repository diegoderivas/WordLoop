package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule
import com.example.diegovocabcards.review.Direction.*
import com.example.diegovocabcards.review.*
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
        assertEquals("c", nextStudyQuestion(queue.ready, listOf("a"))!!.card.id)
        assertEquals(listOf("ES_EN:1:a", "ES_EN:3:c", "EN_ES:4:b"), queue.quickBatch())
        assertEquals("EN_ES:1:c", studyQueue(cards, rows, null, 101).ready.first { it.key == "EN_ES:1:c" }.key)
        assertFalse("EN_ES:1:c" in queue.quickBatch())
        val alone = studyQueue(cards.filter { it.id == "a" }, rows, null, 100)
        assertNull(nextStudyQuestion(alone.ready, listOf("a")))
        assertEquals(queue, studyQueue(cards.reversed(), rows.reversed(), null, 100))
    }

    @Test fun againNeedsBothTheDueTimeAndFiveOtherWordsAndRestartsTheDistance() {
        val mist = Card("1", "mist", "niebla", "", emptyList())
        val otherSense = Card("2", "Mist", "霧", "", emptyList(), language = "ja")
        val cards = listOf(mist, otherSense)
        val now = 100_000L
        val again = Fsrs.rate(null, io.github.openspacedrepetition.Rating.AGAIN, now)
        val rows = listOf(Schedule(mist.id, ES_EN, 1, again.dueAt, again.phase))
        val ratedWords = listOf("mist", "oak", "hill", "hill", "lake", "rain")
            .fold(emptyList<String>()) { recent, word -> recordStudyRating(word, recent) }
        val due = studyQueue(cards, rows, null, again.dueAt)
        assertNull(nextStudyQuestion(due.ready, ratedWords))
        val separated = recordStudyRating("wind", ratedWords)
        val earlyDirection = studyQueue(cards.take(1), rows, ES_EN, again.dueAt - 1)
        assertNull(nextStudyQuestion(earlyDirection.ready, separated))
        val retry = nextStudyQuestion(due.ready, separated)!!
        assertEquals(ES_EN, retry.direction)
        assertEquals(1, retry.turn)
        assertNull(nextStudyQuestion(due.ready, recordStudyRating(retry.word, separated)))
    }

    @Test fun quickLooksPastTenQuestionsWhenEarlyMeaningsAreBlocked() {
        val cards = (0..10).map { Card("a$it", "mist", "gloss $it", "", emptyList()) } +
            (0..8).map { Card("b$it", "different $it", "gloss $it", "", emptyList()) }
        val queue = studyQueue(cards, emptyList(), null, 0L)
        val batch = queue.quickBatch()
        assertEquals(10, batch.size)
        assertTrue(batch.any { ":b" in it })
        assertTrue(planStudyQuestions(queue.ready, emptyList(), limit = 30).size > 10)
    }

    @Test fun schedulesAllEligibleSensesWithOverdueFirst() {
        val cards = (1..250).map { Card(it.toString(), "term $it", "acepción $it", "", emptyList()) }
        val queue = studyQueue(cards, listOf(Schedule("200", EN_ES, 3, 80)), EN_ES, 100)
        assertEquals(250, queue.ready.size)
        assertEquals(249, queue.newCount)
        assertEquals(10, queue.quickBatch().size)
        assertEquals("EN_ES:3:200", queue.quickBatch().first())
        assertEquals("200", queue.ready.first().card.id)
    }
}
