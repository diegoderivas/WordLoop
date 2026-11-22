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
        compose.onNodeWithText("Start practice").performClick()
        for (spanishFirst in listOf(true, false)) for (card in cards) {
            compose.onNodeWithText(if (spanishFirst) card.spanish else card.english).assertIsDisplayed()
            compose.onNodeWithText(if (spanishFirst) card.english else card.spanish).assertDoesNotExist()
            compose.onNodeWithText(card.example).assertDoesNotExist()
            compose.onNodeWithText("Show answer").performClick()
            compose.onNodeWithText(card.english).assertIsDisplayed()
            compose.onNodeWithText(card.example).assertIsDisplayed()
            compose.onNodeWithText("Next").performClick()
        }
        compose.onNodeWithText("Practice complete").assertIsDisplayed()
    }
}
