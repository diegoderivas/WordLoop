package com.example.diegovocabcards.review

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule
import io.github.openspacedrepetition.State
import java.util.Locale

internal data class Question(val card: Card, val turn: Int, val direction: Direction, val learning: Boolean = false) {
    val word: String = card.english.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    val key: String get() = "${direction.name}:$turn:${card.id}"
}
internal data class StudyQueue(
    val ready: List<Question>,
    val newCount: Int,
    val dueCount: Int,
    val nextDue: Long?
) {
    fun quickBatch(): List<String> = planStudyQuestions(ready, emptyList(), limit = 10).map(Question::key)
}

private const val STUDY_WORD_GAP = 5

// The five most recently rated distinct English entries are still blocked.
internal fun recordStudyRating(word: String, recent: List<String>): List<String> =
    (listOf(word) + recent.filterNot { it == word }).take(STUDY_WORD_GAP)

internal fun nextStudyQuestion(ready: List<Question>, recent: List<String>): Question? =
    ready.firstOrNull { it.word !in recent }

internal fun planStudyQuestions(ready: List<Question>, recent: List<String>, limit: Int): List<Question> {
    val pending = ready.toMutableList()
    var rated = recent
    return buildList {
        repeat(limit) {
            val next = nextStudyQuestion(pending, rated) ?: return@buildList
            add(next)
            pending.remove(next)
            rated = recordStudyRating(next.word, rated)
        }
    }
}

/** Null direction selects both independent FSRS histories. */
internal fun studyQueue(cards: List<Card>, schedules: List<Schedule>, direction: Direction?, now: Long): StudyQueue {
    val byId = cards.associateBy { it.id }
    val included = schedules.filter { it.senseId in byId && (direction == null || it.direction == direction) }
    val seen = included.mapTo(mutableSetOf()) { it.senseId to it.direction }
    val due = included.filter { it.dueAt <= now }.sortedWith(compareBy<Schedule>(
        { it.phase == State.REVIEW }, { it.dueAt }, { it.senseId }, { it.direction.ordinal }
    )).map { Question(byId.getValue(it.senseId), it.turn, it.direction, it.phase != State.REVIEW) }
    val new = buildList {
        for (card in cards.sortedBy { it.id }) for (side in Direction.entries) {
            if ((direction == null || side == direction) && (card.id to side) !in seen) {
                add(Question(card, 0, side))
            }
        }
    }
    return StudyQueue(due + new, new.size, due.size, included.filter { it.dueAt > now }.minOfOrNull { it.dueAt })
}
