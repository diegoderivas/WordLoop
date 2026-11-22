package com.example.diegovocabcards

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
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
        compose.onNode(hasText("Library") and hasClickAction()).performClick().assertIsSelected()
        pressBack()
        compose.onNode(hasText("Home") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Add card").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Review") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Dark").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Light").performClick().assertIsSelected()
        compose.onNodeWithText("System").performClick().assertIsSelected()
    }
}
