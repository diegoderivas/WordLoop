package com.example.diegovocabcards

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import java.util.UUID

internal data class DictionaryPronunciation(val ipa: String, val source: String)

internal fun ipaTranscription(raw: String): String? = raw.trim().takeIf {
    it.length in 3..120 && ((it.startsWith('/') && it.endsWith('/')) ||
        (it.startsWith('[') && it.endsWith(']'))) && it.drop(1).dropLast(1).any(Char::isLetter)
}

internal enum class SpeechState { Idle, Loading, Speaking, Unavailable }

internal interface PronunciationPlayer {
    val state: SpeechState
    val requestedText: String?
    fun speak(text: String)
    fun stop()
}

internal fun localEnglishVoices(voices: Set<Voice>): List<Voice> = voices
    .filter {
        it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }
    .sortedWith(compareByDescending<Voice> { it.locale.country == Locale.UK.country }
        .thenByDescending { it.quality }.thenBy { it.name })

internal class AndroidPronunciation(private val context: Context) : PronunciationPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var utterance: String? = null
    private var generation = 0
    private val timeout = Runnable {
        if (!ready) {
            if (pending != null) failInitialization() else release()
        }
    }

    override var state by mutableStateOf(SpeechState.Idle)
        private set
    override var requestedText by mutableStateOf<String?>(null)
        private set

    override fun speak(text: String) {
        val phrase = text.trim().take(TextToSpeech.getMaxSpeechInputLength())
        if (phrase.isEmpty()) return
        requestedText = text
        if (ready) {
            play(phrase)
            return
        }
        pending = phrase
        state = SpeechState.Loading
        if (engine != null) return
        val attempt = ++generation
        engine = TextToSpeech(context) { result ->
            main.post {
                if (attempt == generation) finishInitialization(result)
            }
        }
        main.postDelayed(timeout, 8_000L)
    }

    private fun finishInitialization(result: Int) {
        main.removeCallbacks(timeout)
        val tts = engine
        if (tts == null || result != TextToSpeech.SUCCESS ||
            !runCatching {
                localEnglishVoices(tts.voices.orEmpty()).any { tts.setVoice(it) == TextToSpeech.SUCCESS }
            }.getOrDefault(false)) {
            failInitialization()
            return
        }
        ready = true
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finishUtterance(utteranceId, SpeechState.Idle)
            @Deprecated("Required by UtteranceProgressListener")
            override fun onError(utteranceId: String) = finishUtterance(utteranceId, SpeechState.Unavailable)
        })
        pending?.let(::play)
        pending = null
    }

    private fun play(text: String) {
        val id = UUID.randomUUID().toString()
        utterance = id
        state = SpeechState.Speaking
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finishUtterance(id, SpeechState.Unavailable)
        }
    }

    private fun finishUtterance(id: String, outcome: SpeechState) {
        main.post {
            if (id == utterance) {
                utterance = null
                state = outcome
            }
        }
    }

    private fun failInitialization() {
        val failed = requestedText
        release()
        requestedText = failed
        state = SpeechState.Unavailable
    }

    override fun stop() {
        requestedText = null
        pending = null
        utterance = null
        engine?.stop()
        state = SpeechState.Idle
    }

    fun release() {
        stop()
        generation++
        main.removeCallbacks(timeout)
        engine?.shutdown()
        engine = null
        ready = false
    }
}

internal val LocalPronunciationPlayer = staticCompositionLocalOf<PronunciationPlayer?> { null }

@Composable
internal fun ProvideAndroidPronunciation(lifecycle: Lifecycle, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val player = remember(context) { AndroidPronunciation(context) }
    DisposableEffect(player, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { player.stop() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    CompositionLocalProvider(LocalPronunciationPlayer provides player, content = content)
}

@Composable
internal fun PronunciationButton(text: String, label: String = "Escuchar pronunciación") {
    if (text.isBlank()) return
    val player = LocalPronunciationPlayer.current
    var requested by remember(player, text) { mutableStateOf(false) }
    TextButton(onClick = { requested = true; player?.speak(text) }) {
        Text(label)
    }
    if (requested && (player == null ||
            (player.requestedText == text && player.state == SpeechState.Unavailable))) {
        Text("No hay una voz inglesa local disponible.", style = MaterialTheme.typography.bodySmall)
    } else if (player?.requestedText == text && player.state == SpeechState.Loading) {
        Text("Preparando la voz local…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun IpaContent(ipa: String, source: String) {
    if (ipa.isBlank() || source.isBlank()) return
    val uriHandler = LocalUriHandler.current
    val attribution = source.lineSequence().first()
    val link = source.lineSequence().firstOrNull { it.startsWith("https://") }
    Column {
        Text("IPA · $ipa", style = MaterialTheme.typography.titleSmall)
        if (link != null) {
            TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) { Text(attribution) }
        } else Text(attribution, style = MaterialTheme.typography.bodySmall)
    }
}
