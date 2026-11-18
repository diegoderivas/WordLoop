package com.example.diegovocabcards

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.espresso.Espresso.pressBack
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
        compose.onNodeWithText("Biblioteca").performClick().assertIsSelected()
        pressBack()
        compose.onNodeWithText("Inicio").assertIsSelected()
        compose.onNodeWithText("Añadir tarjeta").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("Repasar").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Configuración").performClick()
        compose.onNodeWithText("Oscuro").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Oscuro").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Claro").performClick().assertIsSelected()
        compose.onNodeWithText("Automático (seguir al teléfono)").performClick().assertIsSelected()
    }
}
