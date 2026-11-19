package com.example.diegovocabcards

import com.example.diegovocabcards.data.Card
import java.util.Locale

private val word = Regex("""[\p{L}\p{N}]+""")
private fun tokens(text: String) = word.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

internal fun studyHint(card: Card, cards: List<Card>, spanishFirst: Boolean): String? {
    fun question(c: Card) = tokens(if (spanishFirst) c.spanish else c.english)
    val prompt = question(card)
    val others = cards.filter { it.id != card.id && question(it) == prompt }
    if (others.isEmpty()) return null
    val answers = (others + card).flatMap { tokens(if (spanishFirst) it.english else it.spanish) }.toSet()
    fun safe(text: String) = text.isNotBlank() && tokens(text).none { it in answers }
    fun categories(c: Card) = c.senseCategories.split(',', '\n', ';').map { categoryLabel(it.trim().lowercase(Locale.ROOT)) }
        .filter(String::isNotEmpty)
    val othersCategories = others.flatMap(::categories).toSet()
    categories(card).firstOrNull { it !in othersCategories && safe(it) }?.let { return it }
    fun context(c: Card) = sequenceOf(c.definition, c.example, c.generatedExamples)
        .flatMap { it.lineSequence() }.map(String::trim).filter { it.length in 5..140 }
    val otherContexts = others.flatMap { context(it).map(::tokens).toList() }.toSet()
    return context(card).firstOrNull { safe(it) && tokens(it) !in otherContexts }
}
