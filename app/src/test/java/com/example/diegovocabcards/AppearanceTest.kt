package com.example.diegovocabcards

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun missingOrUnknownPreferenceFollowsSystem() {
        for (key in listOf(null, "unknown")) {
            val mode = Appearance.fromKey(key)
            assertFalse(mode.isDark(false))
            assertTrue(mode.isDark(true))
        }
    }

    @Test fun explicitModesOverrideBothSystemThemes() {
        for (systemDark in listOf(false, true)) {
            assertFalse(Appearance.Light.isDark(systemDark))
            assertTrue(Appearance.Dark.isDark(systemDark))
        }
    }

    @Test fun eachStoredKeyRestoresItsMode() {
        for (mode in Appearance.entries) assertEquals(mode, Appearance.fromKey(mode.key))
    }
}
