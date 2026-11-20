package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class PronunciationDictionaryTest {
    @Test fun usesOnlyTheExactEnglishEntryAndThePronunciationOfItsEtymology() {
        val html = """
            <h2>English</h2>
            <h3>Etymology 1</h3>
            <h4>Pronunciation</h4>
            <ul><li><span class="qualifier-content">UK</span> <span class="IPA">/ˈɹɛkɔːd/</span></li></ul>
            <h4>Noun</h4><ol><li>A written account.</li></ol>
            <h3>Etymology 2</h3>
            <h4>Pronunciation</h4>
            <ul><li><span class="IPA">/ɹɪˈkɔːd/</span></li></ul>
            <h4>Verb</h4><ol><li>To keep an account of.</li></ol>
            <h2>French</h2>
            <h3>Pronunciation</h3>
            <ul><li><span class="IPA">/ʁə.kɔʁ/</span></li></ul>
            <h3>Noun</h3><ol><li>A different entry.</li></ol>
        """.trimIndent()
        val source = "Colaboradores de Wiktionary · record · CC BY-SA 4.0"
        val english = Wiktionary.parse(html, source, "record", "record")
        assertEquals(2, english.size)
        assertEquals(DictionaryPronunciation("/ˈɹɛkɔːd/", source), english[0].pronunciation)
        assertEquals(DictionaryPronunciation("/ɹɪˈkɔːd/", source), english[1].pronunciation)
        assertTrue(Wiktionary.parse(html, source, "records", "record").all { it.pronunciation == null })
    }

    @Test fun prefersBritishThenAmericanAndAcceptsAnOlderUnlabelledTranscription() {
        assertEquals("/fɑː/", preferredIpa("US /fɑɹ/ · UK /fɑː/"))
        assertEquals("/fɑɹ/", preferredIpa("Australia /fɐː/ · General American /fɑɹ/"))
        assertEquals("/ɹʌn/", preferredIpa("/ɹʌn/"))
        assertNull(preferredIpa("unknown"))
    }

    @Test fun languagePronunciationSurvivesEtymologiesAndScopedOverrideStaysLocal() {
        val html = """
            <h2>English</h2>
            <h3>Pronunciation</h3><ul>
              <li><span class="qualifier-content">US</span><span class="IPA">/fɑɹ/</span></li>
              <li><span class="qualifier-content">UK</span><span class="IPA">/fɑː/</span></li>
            </ul>
            <h3>Etymology 1</h3><h4>Noun</h4><ol><li>A first sense.</li></ol>
            <h3>Etymology 2</h3><h4>Pronunciation</h4><ul>
              <li><span class="IPA">/fiː/</span></li></ul>
            <h4>Verb</h4><ol><li>A second sense.</li></ol>
            <h3>Etymology 3</h3><h4>Adjective</h4><ol><li>A third sense.</li></ol>
        """
        val sounds = Wiktionary.parse(html, "test", "word", "word").map { it.pronunciation?.ipa }
        assertEquals(listOf("/fɑː/", "/fiː/", "/fɑː/"), sounds)
    }

    @Test fun audioOnlyOrUnrelatedIpaDoesNotCreateATranscription() {
        val html = """
            <h2>English</h2>
            <h3>Pronunciation</h3><ul><li>Audio (US)</li></ul>
            <h3>Adverb</h3><ol><li>With regret. <span class="IPA">/other/</span></li></ol>
        """.trimIndent()
        assertNull(Wiktionary.parse(html, "Wiktionary", "ruefully", "ruefully").single().pronunciation)
    }
}
