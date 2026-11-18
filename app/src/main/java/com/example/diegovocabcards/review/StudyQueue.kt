package com.example.diegovocabcards.review

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule

internal data class Question(val card: Card, val turn: Int)
internal data class StudyQueue(
    val ready: List<Question>,
    val newCount: Int,
    val dueCount: Int,
    val nextDue: Long?
)

internal fun studyQueue(cards: List<Card>, schedules: List<Schedule>, direction: Direction, now: Long): StudyQueue {
    val byId = schedules.filter { it.direction == direction }.associateBy { it.senseId }
    val newCards = cards.filter { it.id !in byId }.map { Question(it, 0) }
    val existing = cards.mapNotNull { card -> byId[card.id]?.let { card to it } }
    val due = existing.filter { it.second.dueAt <= now }.sortedBy { it.second.dueAt }
    val ready = due.map { (card, schedule) -> Question(card, schedule.turn) } + newCards
    val nextDue = existing.map { it.second.dueAt }.filter { it > now }.minOrNull()
    return StudyQueue(ready, newCards.size, due.size, nextDue)
}
