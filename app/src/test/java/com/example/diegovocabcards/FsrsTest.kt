package com.example.diegovocabcards

import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.Memory
import com.example.diegovocabcards.review.ReviewSettings
import io.github.openspacedrepetition.Rating.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FsrsTest {
    @Test fun remainingIntervalsRoundWithoutChangingTheirUnitsEarly() {
        val min = 60_000L
        fun interval(ms: Long) = com.example.diegovocabcards.review.formatReviewRemaining(ms)
        assertEquals("1 min", interval(10_000L))
        assertEquals("2 min", interval(90_000L))
        assertEquals("59 min", interval(60 * min - 1))
        assertEquals("1 hour", interval(60 * min))
        assertEquals("2 hours", interval(90 * min))
        assertEquals("20 hours", interval(1170 * min))
        assertEquals("1 day", interval(1440 * min))
    }
    private val start = Instant.parse("2022-11-29T12:30:00Z").toEpochMilli()

    @Test fun matchesPublishedJavaFsrsReferenceIncludingLapse() {
        // java-fsrs v1.0.0, FSRSTest.testCustomSchedulerArgs
        val ratings = listOf(GOOD, GOOD, GOOD, GOOD, GOOD, GOOD, AGAIN, AGAIN, GOOD, GOOD, GOOD, GOOD, GOOD)
        var previous: Memory? = null
        val days = ratings.map { rating ->
            val at = previous?.dueAt ?: start
            val next = Fsrs.rate(previous, rating, at)
            previous = next
            TimeUnit.MILLISECONDS.toDays(next.dueAt - at)
        }
        assertEquals(listOf(0L, 4L, 14L, 45L, 135L, 372L, 0L, 0L, 2L, 5L, 10L, 20L, 40L), days)
    }

    @Test fun firstRatingsHaveExactDueTimes() {
        for ((rating, seconds) in listOf(AGAIN to 60L, HARD to 330L, GOOD to 600L, EASY to 16 * 86400L)) {
            assertEquals(start + seconds * 1000, Fsrs.rate(null, rating, start).dueAt)
        }
    }

    @Test fun retentionShortensDayIntervalsAndShortStepsFollowSettings() {
        val higherRetention = ReviewSettings(retention = 92)
        val shorterSteps = higherRetention.copy(firstLearningMinutes = 2, nextLearningMinutes = 6,
            relearningMinutes = 5)
        assertEquals(start + 600_000, Fsrs.rate(null, GOOD, start, higherRetention).dueAt)
        assertEquals(start + TimeUnit.DAYS.toMillis(12), Fsrs.rate(null, EASY, start, higherRetention).dueAt)
        assertEquals(start + 120_000, Fsrs.rate(null, AGAIN, start, shorterSteps).dueAt)
        assertEquals(start + 240_000, Fsrs.rate(null, HARD, start, shorterSteps).dueAt)
        assertEquals(start + 360_000, Fsrs.rate(null, GOOD, start, shorterSteps).dueAt)
        val graduated = Fsrs.rate(null, EASY, start, shorterSteps)
        assertEquals(graduated.dueAt + 300_000, Fsrs.rate(graduated, AGAIN, graduated.dueAt, shorterSteps).dueAt)
        assertFalse(shorterSteps.copy(firstLearningMinutes = 6).isValid)
        assertFalse(shorterSteps.copy(relearningMinutes = 1440).isValid)
    }
}
