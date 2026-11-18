package com.example.diegovocabcards

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun openSectionsAndRestoreSettings() {
        for (label in listOf("Biblioteca", "Añadir tarjeta", "Repasar")) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithText("Volver a Inicio").performClick()
            compose.onNodeWithText("Inicio").assertIsDisplayed()
        }
        compose.onNodeWithText("Configuración").performScrollTo().performClick()
        compose.onNodeWithText("Oscuro").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Oscuro").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Claro").performClick().assertIsSelected()
        compose.onNodeWithText("Automático (seguir al teléfono)").performClick().assertIsSelected()
    }
}
