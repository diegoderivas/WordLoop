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

internal data class TranslationPage(val html: String, val source: String)

internal object Wiktionary {
    private val headings = setOf("h2", "h3", "h4", "h5", "h6")
    private val categories = setOf(
        "noun", "proper noun", "verb", "adjective", "adverb", "pronoun", "determiner",
        "preposition", "conjunction", "interjection", "particle", "phrase", "proverb",
        "numeral", "article", "prefix", "suffix", "idiom", "abbreviation", "symbol"
    )
    private val prefixBoundary = setOf(',', ';', ':', '(')
    private val pronunciationHeading = Regex("pronunciation(?: [0-9]+)?")
    private val etymologyHeading = Regex("etymology(?: [0-9]+)?")

    private data class IpaVariant(val text: String, val labels: List<String>)

    fun hasTranslationSubpage(html: String, title: String): Boolean =
        html.contains("title=\"$title/translations\"")

    fun parse(
        html: String, source: String, requested: String, title: String,
        supplement: TranslationPage? = null
    ): List<Suggestion> {
        val document = Jsoup.parseBodyFragment(html)
        val root = document.selectFirst(".mw-parser-output") ?: document.body()
        var english = false
        var category = ""
        var categoryDepth = 0
        var inDefinitions = false
        var inPronunciation = false
        val languageVariants = mutableListOf<IpaVariant>()
        val scopedVariants = mutableListOf<IpaVariant>()
        var etymologyDepth = 0
        val exactEntry = senseText(requested) == senseText(title)
        var senses = mutableListOf<Suggestion>()
        var linkedSection = false
        var definitionList = 0
        val supplemental = supplement?.let { subpageTables(it.html) }.orEmpty()
        val translations = mutableListOf<Pair<String, List<String>>>()
        val results = mutableListOf<Suggestion>()

        fun finishCategory() {
            val spanish = mutableMapOf<Int, List<String>>()
            val fromSupplement = mutableSetOf<Int>()
            for ((label, equivalents) in translations) {
                findTranslationSense(senses, label)?.let { index ->
                    spanish[index] = (spanish[index].orEmpty() + equivalents).distinctBy(::senseText)
                }
            }
            if (linkedSection && supplement != null) {
                for ((label, equivalents) in supplemental[category].orEmpty()) {
                    findTranslationSense(senses, label, allowShortCaption = true)?.let { index ->
                        if (index !in spanish) {
                            spanish[index] = equivalents.take(1)
                            fromSupplement += index
                        }
                    }
                }
            }
            fun matchesCategory(variant: IpaVariant): Boolean {
                val partsOfSpeech = variant.labels.map(::senseText).filter { it in categories }
                return partsOfSpeech.isEmpty() || category in partsOfSpeech
            }
            val ipa = scopedVariants.filter(::matchesCategory)
                .ifEmpty { languageVariants.filter(::matchesCategory) }
                .map { variant ->
                    val labels = variant.labels.filterNot { senseText(it) in categories }.distinct()
                    (labels + variant.text).joinToString(" ")
                }.distinct().joinToString(" · ")
            val sound = preferredIpa(ipa)?.let { DictionaryPronunciation(it, source) }
            results += senses.mapIndexed { index, sense ->
                val attribution = if (index in fromSupplement) "$source\n\n${supplement!!.source}" else source
                sense.copy(spanish = spanish[index].orEmpty(), source = attribution, pronunciation = sound)
            }
            linkedSection = false
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
                if (depth == 2) languageVariants.clear()
                if (depth == 2 || depth <= etymologyDepth || etymologyHeading.matches(name)) {
                    etymologyDepth = if (etymologyHeading.matches(name)) depth else 0
                    scopedVariants.clear()
                }
                inPronunciation = english && exactEntry && pronunciationHeading.matches(name)
                if (english && name in categories) {
                    category = name
                    categoryDepth = depth
                    inDefinitions = true
                }
                continue
            }
            if (inPronunciation) {
                for (notation in node.select(".IPA")) {
                    val text = ipaTranscription(notation.text()) ?: continue
                    val labels = notation.closest("li")?.select(".qualifier-content, .ib-content")
                        ?.eachText().orEmpty().flatMap { it.split(',') }.map(String::trim)
                        .filter { it.isNotBlank() && it.length <= 45 }
                    val variants = if (etymologyDepth == 0) languageVariants else scopedVariants
                    variants += IpaVariant(text, labels)
                }
            }
            if (!english || category.isEmpty()) continue
            if (inDefinitions && node.normalName() == "ol") {
                definitionList++
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
                        val rootSense = item.parents().firstOrNull { it.parent() === node } ?: item
                        val family = if (rootSense.children().any { it.normalName() == "ol" }) {
                            "$definitionList:${rootSense.elementSiblingIndex()}"
                        } else ""
                        senses += Suggestion(definition, category, source, usage = usage,
                            examples = example, synonymOptions = synonyms, family = family)
                    }
                }
            }
            if (supplement != null && hasTranslationSubpage(node.outerHtml(), title)) linkedSection = true
            translations += spanishTables(node)
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
        return distinctMeanings(relevant)
    }

    private fun subpageTables(html: String): Map<String, List<Pair<String, List<String>>>> {
        val document = Jsoup.parseBodyFragment(html)
        val root = document.selectFirst(".mw-parser-output") ?: document.body()
        val result = mutableMapOf<String, MutableList<Pair<String, List<String>>>>()
        var english = false
        var category = ""
        for (node in root.children()) {
            val heading = node.takeIf { it.normalName() in headings }
                ?: node.takeIf { it.hasClass("mw-heading") }?.children()?.firstOrNull()
            if (heading?.normalName() == "h2") {
                english = senseText(heading.text()) == "english"
                category = ""
            }
            if (!english) continue
            val name = heading?.let { senseText(it.text()) }
            if (name in categories) category = name.orEmpty()
            if (category.isNotEmpty()) result.getOrPut(category) { mutableListOf() } += spanishTables(node)
        }
        return result
    }

    private fun spanishTables(node: Element): List<Pair<String, List<String>>> =
        node.select("table.translations[data-gloss]").mapNotNull { table ->
            val label = senseText(table.attr("data-gloss"))
            val terms = table.select("li").filter { row: Element -> row.ownText().startsWith("Spanish:") }
                .flatMap { row ->
                    row.select("[lang=es]").filter { term: Element -> term.closest(".ttbc") == null }
                        .map { it.text().trim() }
                }.filter { it.isNotEmpty() }
            if (label.isEmpty() || terms.isEmpty()) null else label to terms
        }

    private fun findTranslationSense(
        senses: List<Suggestion>, caption: String, allowShortCaption: Boolean = false
    ): Int? {
        val grammar = caption.substringBefore(':').takeIf { it == "transitive" || it == "intransitive" }
        val label = if (grammar != null) caption.substringAfter(':').trim() else caption
        val candidates = senses.indices.filter {
            grammar == null || Regex("\\b$grammar\\b").containsMatchIn(senseText(senses[it].usage))
        }
        val exact = candidates.filter { senseText(senses[it].definition) == label }
        if (exact.isNotEmpty()) return exact.singleOrNull()

        val clauses = candidates.filter { isUniqueClauseCandidate(senseText(senses[it].definition), label) }
        if (clauses.isNotEmpty()) {
            if (grammar != null && clauses.size > 1) {
                // A table labelled only by grammatical role describes the general use if unambiguous.
                return clauses.singleOrNull { senseText(senses[it].usage) == grammar }
            }
            return clauses.singleOrNull()
        }
        if (grammar != null) {
            return candidates.singleOrNull {
                senseText(senses[it].usage) == grammar &&
                    senseText(senses[it].definition).startsWith("$label ")
            }
        }
        if (!allowShortCaption) return null
        val words = lexicalWords(label).map(::stem).toSet()
        if (words.size < 2) return null
        return candidates.singleOrNull { index ->
            val gloss = lexicalWords(senses[index].definition).map(::stem).toSet()
            gloss.containsAll(words)
        }
    }

    private fun stem(word: String): String {
        if (word.length < 5) return word
        return word.removeSuffix("ing").removeSuffix("ed").removeSuffix("s").removeSuffix("e")
    }

    private fun isUniqueClauseCandidate(gloss: String, label: String): Boolean {
        if (label.length < 5) return false
        fun startsAtClause(long: String, short: String): Boolean =
            long.startsWith(short) && long.drop(short.length).trimStart().firstOrNull() in prefixBoundary
        return startsAtClause(gloss, label) || startsAtClause(label, gloss)
    }
}
