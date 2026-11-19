package com.example.diegovocabcards.review

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule
import io.github.openspacedrepetition.State

internal data class Question(val card: Card, val turn: Int, val direction: Direction)
internal data class StudyQueue(
    val ready: List<Question>,
    val newCount: Int,
    val dueCount: Int,
    val nextDue: Long?
) {
    fun nextAfter(senseId: String?): Question? =
        ready.firstOrNull { it.card.id != senseId } ?: ready.firstOrNull()
}

/** Null direction selects both independent FSRS histories. */
internal fun studyQueue(cards: List<Card>, schedules: List<Schedule>, direction: Direction?, now: Long): StudyQueue {
    val byId = cards.associateBy { it.id }
    val included = schedules.filter { it.senseId in byId && (direction == null || it.direction == direction) }
    val seen = included.mapTo(mutableSetOf()) { it.senseId to it.direction }
    val due = included.filter { it.dueAt <= now }.sortedWith(compareBy<Schedule>(
        { it.phase == State.REVIEW }, { it.dueAt }, { it.senseId }, { it.direction.ordinal }
    )).map { Question(byId.getValue(it.senseId), it.turn, it.direction) }
    val new = buildList {
        for (card in cards.sortedBy { it.id }) for (side in Direction.entries) {
            if ((direction == null || side == direction) && (card.id to side) !in seen) {
                add(Question(card, 0, side))
            }
        }
    }
    return StudyQueue(due + new, new.size, due.size, included.filter { it.dueAt > now }.minOfOrNull { it.dueAt })
}
