package com.example.diegovocabcards.review

import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Instant

internal enum class Direction(val label: String) {
    ES_EN("Español → Inglés"), EN_ES("Inglés → Español")
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

internal object Fsrs {
    private val scheduler = Scheduler.builder().enableFuzzing(false).build()

    fun rate(previous: Memory?, rating: Rating, at: Long): Memory {
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
