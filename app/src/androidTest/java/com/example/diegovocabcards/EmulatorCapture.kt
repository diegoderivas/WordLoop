package com.example.diegovocabcards

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun captureEmulator(name: String) {
    if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
    // Wait for SurfaceFlinger to present the Compose frame already checked by the test.
    instrumentation.waitForIdleSync()
    android.os.SystemClock.sleep(1200)
    val automation = if (android.os.Build.VERSION.SDK_INT >= 31) {
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
    } else {
        instrumentation.uiAutomation
    }
    val screenshot = checkNotNull(automation.takeScreenshot())
    try {
        File(directory, name).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
        screenshot.recycle()
    }
}
