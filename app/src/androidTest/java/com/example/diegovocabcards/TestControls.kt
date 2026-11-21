package com.example.diegovocabcards

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun ComposeContentTestRule.tapControl(label: String) {
    val node = onNode(hasText(label) and hasClickAction())
    if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
    node.performClick()
}
