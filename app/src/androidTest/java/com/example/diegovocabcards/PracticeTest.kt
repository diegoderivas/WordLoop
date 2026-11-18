package com.example.diegovocabcards

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.diegovocabcards.data.Card
import org.junit.Rule
import org.junit.Test

class PracticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visitsTwoSensesOnceAndHidesEachExampleUntilReveal() {
        val cards = listOf(
            Card("1", "run", "correr", "I run.", emptyList()),
            Card("2", "run", "dirigir", "I run a business.", emptyList())
        )
        compose.setContent { Column { Practice(cards) {} } }
        compose.onNodeWithText("Iniciar práctica").performClick()
        for (card in cards) {
            compose.onNodeWithText(card.spanish).assertIsDisplayed()
            compose.onNodeWithText(card.english).assertDoesNotExist()
            compose.onNodeWithText(card.example).assertDoesNotExist()
            compose.onNodeWithText("Mostrar respuesta").performClick()
            compose.onNodeWithText(card.english).assertIsDisplayed()
            compose.onNodeWithText(card.example).assertIsDisplayed()
            compose.onNodeWithText("Siguiente").performClick()
        }
        compose.onNodeWithText("Práctica terminada").assertIsDisplayed()
    }
}
