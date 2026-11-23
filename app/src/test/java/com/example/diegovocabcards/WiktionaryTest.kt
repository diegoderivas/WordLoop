package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class WiktionaryTest {
    @Test fun japaneseEquivalentsUseTheSelectedGlossAndOmitRubyReadings() {
        val html = """<div class="mw-parser-output"><h2>English</h2><h3>Verb</h3>
            <ol><li>To produce flowers.</li><li>To thrive.</li></ol>
            <table class="translations" data-gloss="to produce flowers"><tr><td><ul>
            <li>Spanish: <span lang="es">florecer</span></li>
            <li>Japanese: <span lang="ja"><ruby>咲<rt>さ</rt></ruby>く</span></li>
            </ul></td></tr></table></div>"""
        val ja = Wiktionary.parse(html, "source", "bloom", "bloom", language = "ja")
        assertEquals(listOf("咲く"), ja.single { "flowers" in it.definition }.spanish)
        assertTrue(ja.single { "thrive" in it.definition }.spanish.isEmpty())
        assertEquals(listOf("florecer"), Wiktionary.parse(html,"source","bloom","bloom").first().spanish)
    }

    @Test fun liveWiktionaryCoverage() {
        org.junit.Assume.assumeTrue(System.getenv("VOCAB_LIVE_DICTIONARY") == "1")
        val requests = listOf("fire", "get fired", "hire", "run", "take off", "look up", "brush up on")
        for (term in requests) {
            val exact = liveMeanings(term)
            val results = exact.ifEmpty {
                participleFromGet(term)?.let { getConstruction(liveMeanings(it)) }.orEmpty()
            }
            val groups = rankedMeaningGroups(results, term)
            val first = groups.take(3).map { it.representative }
            println("$term: ${results.size} senses · " +
                results.flatMap { it.spanish }.distinct().joinToString(", "))
            println("Initial $term: " + first.map { Triple(it.category, it.definition, it.spanish) })
            assertTrue("Missing definitions for $term", first.isNotEmpty())
            if (term == "fire") {
                assertTrue(first.any { it.category == "noun" && ("fuego" in it.spanish || "incendio" in it.spanish) })
                assertTrue(first.any { "despedir" in it.spanish })
                assertEquals(1, first.count { "disparar" in it.spanish })
                assertTrue(groups.take(3).any { "disparar" in it.representative.spanish && it.variants.isNotEmpty() })
            }
            if (term == "get fired") {
                assertEquals("phrase", first.first().category)
                assertTrue(first.first().definition.contains("employment"))
            }
            if (term == "hire") {
                val job = first.single { "contratar" in it.spanish }
                val rental = first.single { "alquilar" in it.spanish && it.category == "verb" }
                assertNotEquals(job.definition, rental.definition)
                assertTrue(job.examples.isNotEmpty())
                assertTrue(rental.examples.isNotEmpty())
                assertTrue("rent" in rental.synonymOptions)
            }
            if (term == "run") assertTrue(first.map { it.category }.containsAll(listOf("noun", "verb")))
            if (term == "take off") assertTrue(first.any { "flight" in it.definition })
            if (term == "look up") assertTrue(first.any { "information" in it.definition })
        }
    }

    private fun liveMeanings(term: String): List<Suggestion> {
        fun page(name: String): org.jsoup.nodes.Element? {
            val path = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val response = org.jsoup.Jsoup.connect(
                "https://en.wiktionary.org/w/api.php?action=parse&format=xml&prop=text" +
                    "&redirects=1&disableeditsection=1&disablelimitreport=1&page=$path"
            ).ignoreContentType(true)
                .userAgent("DiegoVocabCards/1.0 (dictionary integration verification)")
                .timeout(10_000).execute()
            return org.jsoup.Jsoup.parse(response.body(), "", org.jsoup.parser.Parser.xmlParser())
                .selectFirst("parse")
        }
        val entry = page(term) ?: return emptyList()
        val canonical = entry.attr("title")
        val html = entry.selectFirst("text")!!.wholeText()
        val supplement = if (Wiktionary.hasTranslationSubpage(html, canonical)) {
            page("$canonical/translations")?.selectFirst("text")?.wholeText()?.let {
                TranslationPage(it, "Wiktionary translations")
            }
        } else null
        return Wiktionary.parse(html, "Wiktionary", term, canonical, supplement)
    }

    @Test fun linksHireSensesWithinTheirCategoryAndRejectsUncheckedTranslations() {
        val parsed = Wiktionary.parse(
            """
            <div class="mw-parser-output">
              <div class="mw-heading"><h2>English</h2></div>
              <h3>Noun</h3><ol><li>Payment to use something.</li></ol>
              <div class="mw-heading"><h3>Verb</h3></div>
              <ol>
                <li>To employ; to give someone a job.
                  <dl><dd><i class="e-example" lang="en">We hired an engineer.</i></dd></dl>
                </li>
                <li>To pay to use a vehicle.
                  <dl><dd><span class="nyms synonym"><span lang="en"><a>rent</a></span></span></dd>
                  <dd><i class="e-example" lang="en">They hired a car.</i></dd></dl>
                </li>
                <li>To accept employment.</li>
              </ol>
              <h4>Translations</h4>
              <table class="translations" data-gloss="to employ">
                <tr><td><ul><li>Spanish: <span lang="es">contratar</span></li></ul></td></tr>
              </table>
              <table class="translations" data-gloss="to pay to use a vehicle">
                <tr><td><ul><li>Spanish: <span lang="es">alquilar</span></li></ul></td></tr>
              </table>
              <table class="translations" data-gloss="to accept employment">
                <tr><td><ul><li>Spanish: <span class="ttbc"><span lang="es">alquilar</span></span></li></ul></td></tr>
              </table>
              <h2>Other</h2><h3>Verb</h3><ol><li>Ignore this definition.</li></ol>
            </div>
            """.trimIndent(), "test attribution", "hire", "hire"
        )

        assertEquals(4, parsed.size)
        val job = parsed.single { "contratar" in it.spanish }
        val rental = parsed.single { "alquilar" in it.spanish }
        assertEquals(listOf("We hired an engineer."), job.examples)
        assertEquals(listOf("They hired a car."), rental.examples)
        assertEquals(listOf("rent"), rental.synonymOptions)
        assertTrue(parsed.last().spanish.isEmpty())
    }

    @Test fun retainsDifferentUseCategoryAndSpanishWhileMergingExactDuplicates() {
        val primary = Suggestion("To use a machine.", "verb", "wiki",
            spanish = listOf("usar"), examples = listOf("I used it."))
        val result = distinctMeanings(listOf(primary,
            primary.copy(definition = "to use  a machine", examples = listOf("We used it.")),
            primary.copy(category = "noun"),
            primary.copy(usage = "informal"),
            primary.copy(spanish = listOf("manejar"))
        ))
        assertEquals(4, result.size)
        assertEquals(listOf("I used it.", "We used it."), result.first().examples)
    }

    @Test fun ambiguousTranslationTableDoesNotAttachToEitherSense() {
        val html = """
            <h2>English</h2><h3>Verb</h3><ol>
            <li>To turn; to rotate.</li><li>To turn; to change direction.</li>
            </ol><h4>Translations</h4>
            <table class=translations data-gloss="to turn">
              <tr><td><ul><li>Spanish: <span lang=es>girar</span></li></ul></td></tr>
            </table>
        """.trimIndent()
        val result = Wiktionary.parse(html, "wiki", "turn", "turn")
        assertEquals(2, result.size)
        assertTrue(result.all { it.spanish.isEmpty() })
    }

    @Test fun swappingMeaningClearsMissingAutomaticFieldsAndPreservesManualCorrections() {
        val previous = fieldsFrom(Suggestion("Prior", "verb", "wiki", listOf("alquilar"),
            listOf("An automatic example"), listOf("rent")))
            .plus(MeaningField.Definition to "My description")
            .plus(MeaningField.Categories to "")
        val incoming = fieldsFrom(Suggestion("New", "verb", "wiki", listOf("contratar")))
        val automatic = setOf(MeaningField.Spanish, MeaningField.Example, MeaningField.Synonyms)
        val manual = setOf(MeaningField.Definition, MeaningField.Categories)
        val preserved = mergeMeaningFields(previous, incoming, automatic, manual, false)
        assertEquals("contratar", preserved[MeaningField.Spanish])
        assertEquals("My description", preserved[MeaningField.Definition])
        assertEquals("", preserved[MeaningField.Categories])
        assertEquals("", preserved[MeaningField.Example])
        assertEquals("", preserved[MeaningField.Synonyms])
        val replaced = mergeMeaningFields(previous, incoming, automatic, manual, true)
        assertEquals("New", replaced[MeaningField.Definition])
        assertEquals("verb", replaced[MeaningField.Categories])
    }
}
