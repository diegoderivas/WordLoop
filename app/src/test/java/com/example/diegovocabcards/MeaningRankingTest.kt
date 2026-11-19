package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class MeaningRankingTest {
    @Test fun fireKeepsThreeDifferentUsesAndBothShootingVariants() {
        val flame = Suggestion("Combustion producing heat and flame", "noun", "entry")
        val pottery = Suggestion("To heat pottery with fire", "verb", "entry", listOf("cocer"),
            listOf("A pottery example"), usage = "transitive")
        val dismissal = Suggestion("To terminate the employment of a worker", "verb", "entry",
            usage = "transitive, employment")
        val shoot = Suggestion("To shoot a gun", "verb", "entry", listOf("disparar"),
            listOf("A transitive example"), listOf("shoot"), "transitive")
        val shootVariant = shoot.copy(definition = "To shoot a gun or cannon", usage = "intransitive",
            examples = listOf("An intransitive example"), synonymOptions = listOf("shoot", "open fire"))
        val sport = shoot.copy(definition = "To shoot a ball towards goal", usage = "sports",
            spanish = listOf("tirar"))
        val groups = rankedMeaningGroups(listOf(flame, pottery, dismissal, shoot, shootVariant, sport), "fire")
        assertEquals(setOf(flame, dismissal, shoot), groups.take(3).map { it.representative }.toSet())
        assertEquals(listOf(shootVariant), groups.single { it.representative == shoot }.variants)
        assertTrue(groups.any { it.representative == sport })
        assertTrue(groups.any { it.representative == pottery })

        val merged = mergeMeaningFields(fieldsFrom(shoot), fieldsFrom(shootVariant),
            MeaningField.entries.toSet(), emptySet(), false)
        assertEquals("An intransitive example", merged[MeaningField.Example])
        assertFalse(merged[MeaningField.Example]!!.contains("A transitive example"))
    }

    @Test fun followsLinkedTranslationsAndRespectsTransitivity() {
        val wordPage = """
            <h2>English</h2><h3>Verb</h3><ol>
            <li><span class=usage-label-sense><span class=label-content>transitive</span></span>
                To shoot (a firearm).</li>
            <li><span class=usage-label-sense><span class=label-content>intransitive</span></span>
                To shoot a firearm.</li>
            </ol><h4>Translations</h4><p><a title="fire/translations">Translations</a></p>
        """
        val subpage = TranslationPage("""
            <h2>English</h2><h3>Verb</h3><h4>Translations</h4>
            <table class=translations data-gloss="transitive: to shoot"><tr><td>
            <ul><li>Spanish: <span lang=es>disparar</span></li></ul></td></tr></table>
            <table class=translations data-gloss="intransitive: to shoot"><tr><td>
            <ul><li>Spanish: <span lang=es>disparar</span></li></ul></td></tr></table>
        """, "subpage revision")
        val senses = Wiktionary.parse(wordPage, "entry revision", "fire", "fire", subpage)
        assertEquals(2, senses.size)
        assertTrue(senses.all { it.spanish == listOf("disparar") })
        assertTrue(senses.all { it.source == "entry revision\n\nsubpage revision" })
        assertEquals(1, rankedMeaningGroups(senses, "fire").single().variants.size)
    }

    @Test fun differentTranslationsRemainDifferentGroups() {
        val a = Suggestion("To obtain services by giving a person a job", "verb", "source",
            listOf("contratar"), synonymOptions = listOf("engage"))
        val b = a.copy(definition = "To obtain services by paying for temporary use", spanish = listOf("alquilar"))
        assertEquals(2, rankedMeaningGroups(listOf(a, b), "hire").size)
    }

    @Test fun choosesCompleteExpressionsAndKeepsGetAsPartOfTheQuery() {
        val gloss = Suggestion("Dismissed from employment", "adjective", "entry")
        val form = Suggestion("Simple past of another verb", "verb", "entry")
        assertEquals("fired", participleFromGet("get fired"))
        assertNull(participleFromGet("take off"))
        val result = getConstruction(listOf(gloss, form)).single()
        assertEquals("phrase", result.category)
        assertEquals(gloss.definition, result.definition)
        val variant = Suggestion("Nonstandard spelling of takeoff", "noun", "entry")
        val flight = Suggestion("To rise into the air", "verb", "entry")
        assertEquals(flight, rankedMeaningGroups(listOf(variant, flight), "take off").first().representative)
    }
}
