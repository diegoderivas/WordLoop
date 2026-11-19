package com.example.diegovocabcards

private val actionVerb = Regex("^to ([a-z]+)\\b")
private val grammarLabels = Regex("\\b(transitive|intransitive|ambitransitive|countable|uncountable)\\b")
private val restrictedUsage = Regex(
    "\\b(archaic|obsolete|dated|slang|figurative|regional|rare|historical|by extension|" +
        "alchemy|gemology|rocketry|mining|physiology|ceramics|farriery|sports?|software|engineering)\\b"
)
private val everydayUsage = Regex("\\b(employment|education|food|travel|everyday)\\b")
private val indirectDefinition = Regex("^(used other than|nonstandard spelling|alternative (form|spelling)|past participle|simple past|plural of)\\b")

internal data class MeaningGroup(val representative: Suggestion, val variants: List<Suggestion>)

internal fun rankedMeaningGroups(suggestions: List<Suggestion>, term: String): List<MeaningGroup> {
    val query = senseText(term)
    val positions = mutableMapOf<String, Int>()
    val order = suggestions.associateWith { sense ->
        val position = positions.getOrDefault(sense.category, 0)
        positions[sense.category] = position + 1
        position
    }
    val wholeQuery = Regex("\\b" + Regex.escape(query) + "\\b")

    fun score(sense: Suggestion): Int {
        val usage = senseText(sense.usage)
        val definition = senseText(sense.definition)
        var score = when (sense.category) {
            "verb" -> if (' ' in query) 12 else 2
            "noun", "phrase", "idiom" -> 0
            else -> -12
        }
        score -= order.getValue(sense).coerceAtMost(18)
        if (restrictedUsage.containsMatchIn(usage)) score -= 24
        if (everydayUsage.containsMatchIn(usage)) score += 12
        if (indirectDefinition.containsMatchIn(definition)) score -= 32
        if (sense.category == "verb" && query.isNotEmpty() && wholeQuery.containsMatchIn(definition)) score -= 15
        if (sense.examples.isNotEmpty()) score++
        if (sense.spanish.isNotEmpty()) score += 3
        return score
    }

    val groups = mutableListOf<MeaningGroup>()
    for (sense in suggestions.sortedByDescending(::score)) {
        val index = groups.indexOfFirst { group -> closeVariants(group.representative, sense) }
        if (index < 0) groups += MeaningGroup(sense, emptyList())
        else groups[index] = groups[index].copy(variants = groups[index].variants + sense)
    }
    fun groupScore(group: MeaningGroup): Int =
        score(group.representative) + if (group.variants.isNotEmpty()) 8 else 0

    val remaining = groups.sortedByDescending(::groupScore).toMutableList()
    if (remaining.isEmpty()) return emptyList()
    val preview = mutableListOf(remaining.removeAt(0))
    val majorCategories = setOf("noun", "verb")
    val first = preview.first().representative
    if (' ' !in query && first.category in majorCategories) {
        val alternate = remaining.indexOfFirst {
            val candidate = it.representative
            candidate.category != first.category && candidate.category in majorCategories &&
                score(candidate) >= score(first) - 22
        }
        if (alternate >= 0) preview += remaining.removeAt(alternate)
    }
    while (preview.size < 3 && remaining.isNotEmpty()) {
        val usedFamilies = preview.map { it.representative.family }.filter { it.isNotEmpty() }.toSet()
        val distinctFamily = remaining.indexOfFirst {
            it.representative.family !in usedFamilies
        }
        preview += remaining.removeAt(if (distinctFamily >= 0) distinctFamily else 0)
    }
    return preview + remaining
}

private fun closeVariants(first: Suggestion, second: Suggestion): Boolean {
    if (first.category != second.category) return false
    fun context(sense: Suggestion) = grammarLabels.replace(senseText(sense.usage), "")
        .filter(Char::isLetterOrDigit)
    if (context(first) != context(second)) return false

    val firstSpanish = first.spanish.map(::senseText)
    val secondSpanish = second.spanish.map(::senseText)
    if (firstSpanish.isNotEmpty() && secondSpanish.isNotEmpty() &&
        firstSpanish.first() != secondSpanish.first()) return false

    val a = senseText(first.definition)
    val b = senseText(second.definition)
    if (a == b) return true
    val predicate = actionVerb.find(a)?.groupValues?.get(1) ?: return false
    if (actionVerb.find(b)?.groupValues?.get(1) != predicate) return false
    val sameSpanish = firstSpanish.firstOrNull()?.let { it == secondSpanish.firstOrNull() } == true
    val commonSynonym = first.synonymOptions.map(::senseText).any { it in second.synonymOptions.map(::senseText) }
    val sharedContext = lexicalWords(a).intersect(lexicalWords(b)).any { it != predicate }
    return sharedContext && (sameSpanish || commonSynonym)
}

private val lexicalWord = Regex("[a-z]+")
private val grammaticalWords = setOf("the", "for", "with", "and", "from", "into", "this", "that",
    "which", "other", "some", "such", "especially", "something", "someone", "either", "often")

internal fun lexicalWords(text: String): Set<String> = lexicalWord.findAll(senseText(text))
    .map { it.value }
    .filter { it.length >= 3 && it !in grammaticalWords }
    .toSet()

internal fun participleFromGet(term: String): String? =
    Regex("^get ([a-z]+ed)$").matchEntire(senseText(term))?.groupValues?.get(1)

internal fun getConstruction(senses: List<Suggestion>): List<Suggestion> = senses
    .filter { it.category == "adjective" }
    .map {
        // A participle's translation or usage example may require different wording in the full expression.
        it.copy(
            category = "phrase", usage = listOf("A partir del participio", it.usage)
                .filter { label -> label.isNotBlank() }.joinToString(" · "),
            spanish = emptyList(), examples = emptyList(), synonymOptions = emptyList(), pronunciation = null
        )
    }
