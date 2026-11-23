package com.example.diegovocabcards

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.map
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
    private val sharedText = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedText.value = readSharedText(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_shared_text", sharedText.value)
        super.onSaveInstanceState(outState)
    }

    private fun readSharedText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(240)?.toString()
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotEmpty)
    }

    override fun attachBaseContext(base: android.content.Context) {
        val configuration = android.content.res.Configuration(base.resources.configuration)
        configuration.setLocale(java.util.Locale.UK)
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText.value = if (savedInstanceState != null) savedInstanceState.getString("pending_shared_text") else readSharedText(intent)
        enableEdgeToEdge()
        val vocabulary = androidx.lifecycle.ViewModelProvider(this)[VocabularyModel::class.java]
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        val reviewPreferences = getSharedPreferences("review", MODE_PRIVATE)
        val legacy = LibrarySettings(theme = Appearance.fromKey(preferences.getString("theme", null)).key,
            automaticSpeech = reviewPreferences.getBoolean("automatic_voice", true), review = reviewPreferences.readReviewSettings())
        val settingsFlow = vocabulary.backup.observeSettings().map { it ?: legacy }
        setContent {
            val stored by settingsFlow.collectAsState(initial = null)
            val settings = stored ?: return@setContent
            val mode = Appearance.fromKey(settings.theme)
            val dark = mode.isDark(isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            DiegoVocabCardsTheme(darkTheme = dark) {
                ProvideAndroidPronunciation(lifecycle, settings.voice) {
                    VocabCardsApp(mode, vocabulary, settings.review, automaticSpeech = settings.automaticSpeech,
                        backupDefaults = settings,
                        sharedText = sharedText.value, onSharedTextHandled = { sharedText.value = null },
                        onVoiceChange = { name -> vocabulary.configure(legacy) { it.copy(voice = name) } },
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
