package com.example.diegovocabcards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.example.diegovocabcards.ui.theme.DiegoVocabCardsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vocabulary = androidx.lifecycle.ViewModelProvider(this)[VocabularyModel::class.java]
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        val reviewPreferences = getSharedPreferences("review", MODE_PRIVATE)
        setContent {
            var mode by remember {
                mutableStateOf(Appearance.fromKey(preferences.getString("theme", null)))
            }
            var reviewSettings by remember { mutableStateOf(reviewPreferences.readReviewSettings()) }
            val dark = mode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            DiegoVocabCardsTheme(darkTheme = dark) {
                ProvideAndroidPronunciation(lifecycle) {
                    VocabCardsApp(mode, vocabulary, reviewSettings, onReviewSettingsChange = {
                        reviewPreferences.writeReviewSettings(it)
                        reviewSettings = it
                    }) { selected ->
                        preferences.edit().putString("theme", selected.key).apply()
                        mode = selected
                    }
                }
            }
        }
    }
}
