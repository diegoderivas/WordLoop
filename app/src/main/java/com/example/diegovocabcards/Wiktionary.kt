package com.example.diegovocabcards

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

internal fun senseText(text: String): String =
    text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trimEnd('.', ';', ':', ',')

internal fun distinctMeanings(suggestions: List<Suggestion>): List<Suggestion> =
    suggestions.groupBy {
        listOf(senseText(it.definition), senseText(it.category), senseText(it.usage),
            it.spanish.map(::senseText).sorted().joinToString("|"), it.source)
    }.values.map { group ->
        group.first().copy(
            examples = group.flatMap { it.examples }.distinctBy(::senseText),
            synonymOptions = group.flatMap { it.synonymOptions }.distinctBy(::senseText)
        )
    }

internal object Wiktionary {
    private val headings = setOf("h2", "h3", "h4", "h5", "h6")
    private val categories = setOf(
        "noun", "proper noun", "verb", "adjective", "adverb", "pronoun", "determiner",
        "preposition", "conjunction", "interjection", "particle", "phrase", "proverb",
        "numeral", "article", "prefix", "suffix", "idiom", "abbreviation", "symbol"
    )
    private val prefixBoundary = setOf(',', ';', ':', '(')

    fun parse(html: String, source: String, requested: String, title: String): List<Suggestion> {
        val document = Jsoup.parseBodyFragment(html)
        val root = document.selectFirst(".mw-parser-output") ?: document.body()
        var english = false
        var category = ""
        var categoryDepth = 0
        var inDefinitions = false
        var senses = mutableListOf<Suggestion>()
        val translations = mutableListOf<Pair<String, List<String>>>()
        val results = mutableListOf<Suggestion>()

        fun finishCategory() {
            val spanish = mutableMapOf<Int, List<String>>()
            for ((label, equivalents) in translations) {
                val exact = senses.indices.filter { senseText(senses[it].definition) == label }
                val matching = exact.ifEmpty {
                    senses.indices.filter { isUniqueClauseCandidate(senseText(senses[it].definition), label) }
                }
                matching.singleOrNull()?.let { index ->
                    spanish[index] = (spanish[index].orEmpty() + equivalents).distinctBy(::senseText)
                }
            }
            results += senses.mapIndexed { index, sense -> sense.copy(spanish = spanish[index].orEmpty()) }
            senses = mutableListOf()
            translations.clear()
        }

        for (node in root.children()) {
            val heading = node.takeIf { it.normalName() in headings }
                ?: node.takeIf { it.hasClass("mw-heading") }?.children()?.firstOrNull()
            if (heading != null && heading.normalName() in headings) {
                val depth = heading.normalName().last().digitToInt()
                val name = senseText(heading.text())
                inDefinitions = false
                if (depth == 2 || (categoryDepth > 0 && depth <= categoryDepth)) {
                    finishCategory()
                    category = ""
                    categoryDepth = 0
                }
                if (depth == 2) english = name == "english"
                if (english && name in categories) {
                    category = name
                    categoryDepth = depth
                    inDefinitions = true
                }
                continue
            }
            if (!english || category.isEmpty()) continue
            if (inDefinitions && node.normalName() == "ol") {
                for (item in node.select("li")) {
                    if (item.parent()?.normalName() != "ol") continue
                    val local = item.clone()
                    local.select("ol, ul, .citation-whole").remove()
                    val example = local.select(".e-example[lang=en]").eachText().filter { it.isNotBlank() }
                    val synonyms = local.select(".nyms.synonym a[lang=en], .nyms.synonym [lang=en] a")
                        .eachText().filter { it.isNotBlank() }
                    val usage = local.select(".usage-label-sense .label-content").eachText().joinToString(" · ")
                    local.select("dl, .usage-label-sense, .mw-editsection, .reference").remove()
                    val definition = local.text().trim()
                    if (definition.isNotBlank()) {
                        senses += Suggestion(definition, category, source, usage = usage,
                            examples = example, synonymOptions = synonyms)
                    }
                }
            }
            for (table in node.select("table.translations[data-gloss]")) {
                val label = senseText(table.attr("data-gloss"))
                val terms = table.select("li").filter { row: Element -> row.ownText().startsWith("Spanish:") }
                    .flatMap { row ->
                        row.select("[lang=es]").filter { term: Element -> term.closest(".ttbc") == null }
                            .map { it.text().trim() }
                    }.filter { it.isNotEmpty() }
                if (label.isNotEmpty() && terms.isNotEmpty()) translations += label to terms
            }
        }
        finishCategory()

        // A redirect from a longer construction can point to a verb with unrelated literal senses.
        val query = senseText(requested)
        val canonical = senseText(title)
        val relevant = if (query.startsWith("$canonical ")) {
            val words = Regex("\\b" + Regex.escape(query) + "\\b")
            results.filter { sense ->
                sense.examples.any { words.containsMatchIn(senseText(it.replace("(", "").replace(")", ""))) }
            }
        } else results
        return distinctMeanings(relevant).sortedWith(
            compareByDescending<Suggestion> { it.spanish.isNotEmpty() }
                .thenByDescending { it.examples.isNotEmpty() }
                .thenByDescending { it.category == "verb" }
        )
    }

    private fun isUniqueClauseCandidate(gloss: String, label: String): Boolean {
        if (label.length < 5) return false
        fun startsAtClause(long: String, short: String): Boolean =
            long.startsWith(short) && long.drop(short.length).trimStart().firstOrNull() in prefixBoundary
        return startsAtClause(gloss, label) || startsAtClause(label, gloss)
    }
}
