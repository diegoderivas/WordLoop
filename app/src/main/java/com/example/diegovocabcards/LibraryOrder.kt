package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.Schedule
import com.example.diegovocabcards.review.Direction

internal enum class LibraryOrder(val label: String) {
    Alphabetical("A–Z"), Newest("Newest first"), Oldest("Oldest first"), Due("Next review")
}

internal data class LibrarySide(val direction: Direction, val schedule: Schedule?) {
    val status = schedule?.phase?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "New"
}
internal data class LibraryRow(val card: Card, val sides: List<LibrarySide>)

internal fun organiseLibrary(
    newestFirst: List<Card>, schedules: List<Schedule>, query: String, tag: String?, direction: Direction?,
    status: String?, order: LibraryOrder, language: String? = null
): List<LibraryRow> {
    val term = query.trim()
    val bySide = schedules.associateBy { it.senseId to it.direction }
    val matches = newestFirst.mapNotNull { card ->
        if (language != null && card.language != language) return@mapNotNull null
        if (!card.english.contains(term, true) && !card.spanish.contains(term, true)) return@mapNotNull null
        if (tag != null && card.tags.none { it.equals(tag, true) }) return@mapNotNull null
        val sides = listOf(Direction.EN_ES, Direction.ES_EN).filter { direction == null || it == direction }
            .map { LibrarySide(it, bySide[card.id to it]) }.filter { status == null || it.status == status }
        if (sides.isEmpty()) null else LibraryRow(card, sides)
    }
    return when (order) {
        LibraryOrder.Newest -> matches
        LibraryOrder.Oldest -> matches.asReversed()
        LibraryOrder.Alphabetical -> matches.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.card.english })
        LibraryOrder.Due -> matches.sortedBy { row -> row.sides.mapNotNull { it.schedule?.dueAt }.minOrNull() ?: Long.MAX_VALUE }
    }
}
