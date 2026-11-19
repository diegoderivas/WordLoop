package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class SenseTranslationTest {
    @Test fun completeExpressionIsMarkedAndTheSelectedContextIsUsed() {
        val flight = Suggestion("To leave the ground.", "verb", "")
        assertEquals("\"to take off\" (To leave the ground.)", senseTranslationInput(" take  off ", flight))
        assertNull(senseTranslationInput("take off", flight.copy(spanish = listOf("despegar"))))
        assertEquals("\"look up\" (Use the dictionary.)",
            senseTranslationInput("look up", Suggestion("", "noun", "", examples = listOf("Use the dictionary."))))
    }

    @Test fun acceptsCompactContextualResultsAndRejectsAmbiguity() {
        assertEquals("ser despedido",
            senseTranslationEquivalent("\"Para ser despedido\" (contexto).", "get fired", "phrase"))
        assertEquals("despegar", senseTranslationEquivalent("«Despegar» (en vuelo).", "take off", "verb"))
        assertEquals("buscar", senseTranslationEquivalent("“Para buscar” (en un libro).", "look up", "verb"))
        assertNull(senseTranslationEquivalent("\"Encender\" (terminado del empleo).", "get fired", "phrase"))
        assertNull(senseTranslationEquivalent("\"Buscar o mirar\" (contexto)", "look up", "verb"))
        assertNull(senseTranslationEquivalent("\"Busque\" (en un libro)", "look up", "verb"))
        assertNull(senseTranslationEquivalent("Puedes buscar en el diccionario.", "look up", "verb"))
    }
}
