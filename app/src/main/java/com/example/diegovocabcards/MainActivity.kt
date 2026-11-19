package com.example.diegovocabcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.diegovocabcards.data.LibrarySettings
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vocabulary = androidx.lifecycle.ViewModelProvider(this)[VocabularyModel::class.java]
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        val reviewPreferences = getSharedPreferences("review", MODE_PRIVATE)
        val legacy = LibrarySettings(theme = Appearance.fromKey(preferences.getString("theme", null)).key,
            automaticSpeech = reviewPreferences.getBoolean("automatic_voice", true), review = reviewPreferences.readReviewSettings())
        val settingsFlow = vocabulary.backup.observeSettings()
        setContent {
            val stored by settingsFlow.collectAsState(initial = null)
            val settings = stored ?: legacy
            val mode = Appearance.fromKey(settings.theme)
            val dark = mode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            DiegoVocabCardsTheme(darkTheme = dark) {
                ProvideAndroidPronunciation(lifecycle) {
                    VocabCardsApp(mode, vocabulary, settings.review, automaticSpeech = settings.automaticSpeech,
                        backupDefaults = settings,
                        onAutomaticSpeech = { value -> vocabulary.configure(legacy) { it.copy(automaticSpeech = value) } },
                        onReviewSettingsChange = { value -> vocabulary.configure(legacy) { it.copy(review = value) } }
                    ) { selected ->
                        vocabulary.configure(legacy) { it.copy(theme = selected.key) }
                    }
                }
            }
        }
    }
}
