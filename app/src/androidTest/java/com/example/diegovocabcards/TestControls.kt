package com.example.diegovocabcards

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal fun ComposeContentTestRule.tapControl(label: String) {
    val node = onNode(hasText(label) and hasClickAction())
    if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
    node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
}

internal fun disposeTestContent() {
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        fun dispose(view: android.view.View) {
            if (view is androidx.compose.ui.platform.AbstractComposeView) view.disposeComposition()
            else if (view is android.view.ViewGroup) for (i in 0 until view.childCount) dispose(view.getChildAt(i))
        }
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .forEach { dispose(it.window.decorView) }
    }
}
