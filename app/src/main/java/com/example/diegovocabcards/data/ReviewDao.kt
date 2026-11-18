package com.example.diegovocabcards.data

import androidx.room.*
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.Fsrs
import com.example.diegovocabcards.review.Memory
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

internal data class Schedule(val senseId: String, val direction: Direction, val turn: Int, val dueAt: Long)

@Dao
internal abstract class ReviewDao {
    @Query("""SELECT e.senseId, e.direction, e.turn, e.dueAt FROM ReviewEvent e
        WHERE e.turn = (SELECT MAX(h.turn) FROM ReviewEvent h
            WHERE h.senseId = e.senseId AND h.direction = e.direction)""")
    abstract fun schedules(): Flow<List<Schedule>>

    @Query("""SELECT * FROM ReviewEvent WHERE senseId = :id AND direction = :direction
        ORDER BY turn DESC LIMIT 1""")
    abstract suspend fun latest(id: String, direction: Direction): ReviewEvent?

    @Insert
    protected abstract suspend fun append(event: ReviewEvent)

    /** The displayed revision identifies a single presentation, including across retries. */
    @Transaction
    open suspend fun rate(id: String, direction: Direction, expectedTurn: Int, rating: Rating, now: Long) {
        val previous = latest(id, direction)
        if ((previous?.turn ?: 0) != expectedTurn) return
        if (previous != null && now < previous.memory.dueAt) return
        append(ReviewEvent(id, direction, expectedTurn + 1, rating,
            Fsrs.rate(previous?.memory, rating, now)))
    }
}
