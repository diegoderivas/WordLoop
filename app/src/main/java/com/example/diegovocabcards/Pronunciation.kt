package com.example.diegovocabcards

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
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

private val ipaNotation = Regex("""/[^/]+/|\[[^\]]+\]""")
private val britishIpaLabel = Regex("""\b(uk|britain|british|rp|received pronunciation)\b""")
private val americanIpaLabel = Regex("""\b(us|usa|american|genam)\b""")

internal fun preferredIpa(raw: String): String? = raw.split(" · ").mapNotNull { variant ->
    val notation = ipaNotation.find(variant) ?: return@mapNotNull null
    val valid = ipaTranscription(notation.value) ?: return@mapNotNull null
    val region = variant.substring(0, notation.range.first).lowercase(Locale.ROOT)
    val priority = when {
        britishIpaLabel.containsMatchIn(region) -> 0
        americanIpaLabel.containsMatchIn(region) -> 1
        else -> 2
    }
    priority to "/${valid.drop(1).dropLast(1)}/"
}.minByOrNull { it.first }?.second

internal const val VOICE_SAMPLE = "I'll see you tomorrow morning."
internal const val MANUAL_IPA = "Transcripción manual"

internal fun formattedIpa(value: String): String {
    val body = value.trim().trim('/', '[', ']').trim()
    return if (body.isEmpty()) "" else "/$body/"
}

internal enum class SpeechState { Idle, Loading, Speaking, Unavailable }

internal interface PronunciationPlayer {
    val state: SpeechState
    val requestedText: String?
    val voices: List<Voice> get() = emptyList()
    fun refreshVoices() {}
    fun speak(text: String)
    fun sample(voiceName: String)
    fun stop()
}

internal fun localEnglishVoices(voices: Set<Voice>, preferred: String = ""): List<Voice> = voices
    .filter {
        it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }
    .sortedWith(compareByDescending<Voice> { it.name == preferred }
        .thenByDescending { it.locale.country == Locale.UK.country }
        .thenByDescending { it.quality }.thenBy { it.name })

internal class AndroidPronunciation(private val context: Context) : PronunciationPlayer {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    var preferredVoice = ""
    override var voices by mutableStateOf(emptyList<Voice>())
        private set
    private var ready = false
    private var pending: String? = null
    private var sampleVoice: String? = null
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

    override fun speak(text: String) = start(text, null)

    override fun sample(voiceName: String) {
        stop()
        start(VOICE_SAMPLE, voiceName)
    }

    private fun start(text: String, voiceName: String?) {
        sampleVoice = voiceName
        val phrase = text.trim().take(TextToSpeech.getMaxSpeechInputLength())
        if (phrase.isEmpty()) return
        requestedText = text
        if (ready) {
            play(phrase)
            return
        }
        pending = phrase
        state = SpeechState.Loading
        initialize()
    }

    override fun refreshVoices() {
        if (ready) voices = localEnglishVoices(engine?.voices.orEmpty())
        else initialize()
    }

    private fun initialize() {
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
        voices = if (result == TextToSpeech.SUCCESS) localEnglishVoices(tts?.voices.orEmpty()) else emptyList()
        if (tts == null || voices.isEmpty()) {
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
        val tts = engine ?: return
        val available = localEnglishVoices(tts.voices.orEmpty(), preferredVoice)
            .filter { sampleVoice == null || it.name == sampleVoice }
        if (available.none { tts.voice == it || tts.setVoice(it) == TextToSpeech.SUCCESS }) {
            failInitialization()
            return
        }
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
        sampleVoice = null
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
internal fun ProvideAndroidPronunciation(lifecycle: Lifecycle, preferredVoice: String = "", content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val player = remember(context) { AndroidPronunciation(context) }
    SideEffect { player.preferredVoice = preferredVoice }
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
internal fun PronunciationButton(text: String, label: String) {
    PronunciationControl(text) { play ->
        TextButton(onClick = play) { Text(label) }
    }
}

@Composable
internal fun EnglishPronunciation(
    text: String, ipa: String?, content: @Composable RowScope.() -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PronunciationControl(text) { play ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
                if (text.isNotBlank()) {
                    IconButton(onClick = play) {
                        Icon(painterResource(R.drawable.pronunciation_speaker), "Escuchar pronunciación")
                    }
                }
            }
        }
        if (ipa != null) {
            val transcription = preferredIpa(ipa)
            Text(transcription ?: "Sin IPA", style = if (transcription == null) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun PronunciationControl(text: String, button: @Composable (() -> Unit) -> Unit) {
    val player = LocalPronunciationPlayer.current
    var requested by remember(player, text) { mutableStateOf(false) }
    button {
        requested = true
        player?.speak(text)
    }
    if (requested && (player == null ||
            (player.requestedText == text && player.state == SpeechState.Unavailable))) {
        Text("No hay una voz inglesa local disponible.", style = MaterialTheme.typography.bodySmall)
    } else if (player?.requestedText == text && player.state == SpeechState.Loading) {
        Text("Preparando la voz local…", style = MaterialTheme.typography.bodySmall)
    }
}
