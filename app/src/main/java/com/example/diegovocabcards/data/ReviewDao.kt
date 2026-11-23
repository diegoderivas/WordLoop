package com.example.diegovocabcards.data

import androidx.room.*
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.Memory
import com.example.diegovocabcards.review.ReviewSettings
import io.github.openspacedrepetition.Rating
import kotlinx.coroutines.flow.Flow

@Entity(primaryKeys = ["senseId", "direction", "turn"], foreignKeys = [
    ForeignKey(entity = Sense::class, parentColumns = ["id"], childColumns = ["senseId"],
        onDelete = ForeignKey.CASCADE)
])
internal data class ReviewEvent(
    val senseId: String,
    val direction: Direction,
    val turn: Int,
    val rating: Rating,
    @Embedded val memory: Memory
)

internal data class Schedule(val senseId: String, val direction: Direction, val turn: Int, val dueAt: Long,
    val phase: io.github.openspacedrepetition.State = io.github.openspacedrepetition.State.REVIEW)

@Dao
internal abstract class ReviewDao {
    @Query("""SELECT e.senseId, e.direction, e.turn, e.dueAt, e.phase FROM ReviewEvent e
        WHERE e.turn = (SELECT MAX(h.turn) FROM ReviewEvent h
            WHERE h.senseId = e.senseId AND h.direction = e.direction)""")
    abstract fun schedules(): Flow<List<Schedule>>

    @Query("SELECT reviewedAt FROM ReviewEvent WHERE reviewedAt >= :start")
    abstract fun recentRatingsSince(start: Long): Flow<List<Long>>

    @Query("""SELECT * FROM ReviewEvent WHERE senseId = :id AND direction = :direction
        ORDER BY turn DESC LIMIT 1""")
    abstract suspend fun latest(id: String, direction: Direction): ReviewEvent?

    @Query("SELECT * FROM ReviewEvent WHERE senseId = :id AND direction = :direction ORDER BY turn DESC LIMIT 5")
    abstract suspend fun recent(id: String, direction: Direction): List<ReviewEvent>

    @Insert
    protected abstract suspend fun append(event: ReviewEvent)

    /** The displayed revision identifies a single presentation, including across retries. */
    @Transaction
    open suspend fun rate(
        id: String,
        direction: Direction,
        expectedTurn: Int,
        rating: Rating,
        now: Long,
        settings: ReviewSettings = ReviewSettings(),
        revealedAt: Long = now
    ) {
        val previous = latest(id, direction)
        if ((previous?.turn ?: 0) != expectedTurn) return
        if (previous != null && now < previous.memory.dueAt) return
        // Use the memory estimate shown at reveal, but start the chosen interval at the tap.
        val preview = Fsrs.rate(previous?.memory, rating, revealedAt, settings)
        val next = preview.copy(reviewedAt = now, dueAt = now + (preview.dueAt - revealedAt))
        append(ReviewEvent(id, direction, expectedTurn + 1, rating, next))
    }
}
