package com.example.diegovocabcards.review

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Duration
import java.time.Instant

internal enum class Direction(val label: String) {
    ES_EN("Spanish → English"), EN_ES("English → Spanish")
}

/** Snapshot after a rating. An absent snapshot means this sense/direction is new. */
internal data class Memory(
    val phase: State,
    val step: Int?,
    val stability: Double,
    val difficulty: Double,
    val dueAt: Long,
    val reviewedAt: Long
)

internal data class ReviewSettings(
    val retention: Int = 90,
    val firstLearningMinutes: Int = 1,
    val nextLearningMinutes: Int = 10,
    val relearningMinutes: Int = 10
) {
    val isValid: Boolean
        get() = retention in 80..97 &&
            firstLearningMinutes in 1..1439 &&
            nextLearningMinutes in 1..1439 &&
            firstLearningMinutes < nextLearningMinutes &&
            relearningMinutes in 1..1439
}

internal object Fsrs {
    fun rate(previous: Memory?, rating: Rating, at: Long, settings: ReviewSettings = ReviewSettings()): Memory {
        val scheduler = Scheduler.builder()
            .desiredRetention(settings.retention / 100.0)
            .learningSteps(arrayOf(
                Duration.ofMinutes(settings.firstLearningMinutes.toLong()),
                Duration.ofMinutes(settings.nextLearningMinutes.toLong())
            ))
            .relearningSteps(arrayOf(Duration.ofMinutes(settings.relearningMinutes.toLong())))
            .enableFuzzing(false)
            .build()
        val instant = Instant.ofEpochMilli(at)
        // The library's integer ID is only echoed in its log. Room owns the actual identity.
        val builder = Card.builder().cardId(0).due(instant)
        if (previous != null) {
            builder.state(previous.phase).step(previous.step)
                .stability(previous.stability).difficulty(previous.difficulty)
                .lastReview(Instant.ofEpochMilli(previous.reviewedAt))
                .due(Instant.ofEpochMilli(previous.dueAt))
        }
        val next = scheduler.reviewCard(builder.build(), rating, instant).card()
        return Memory(next.state, next.step, next.stability, next.difficulty,
            next.due.toEpochMilli(), next.lastReview.toEpochMilli())
    }
}

internal fun formatReviewInterval(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val days = seconds / 86_400
    if (days > 0) return if (days == 1L) "1 day" else "$days days"
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (remainder == 0L) "$minutes min" else "$minutes min $remainder s"
}
