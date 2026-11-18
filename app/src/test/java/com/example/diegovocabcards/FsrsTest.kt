package com.example.diegovocabcards

import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.Memory
import io.github.openspacedrepetition.Rating.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class FsrsTest {
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
}
