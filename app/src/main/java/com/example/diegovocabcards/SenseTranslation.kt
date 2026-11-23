package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val GOOGLE_TRANSLATION = "Traducción automática con Google Translate\n" +
    "https://developers.google.com/ml-kit/language/translation"

internal object LocalSenseTranslation {
    private fun options(language: String) = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(language).build()
    private val wifi = DownloadConditions.Builder().requireWifi().build()

    suspend fun suggest(term: String, meaning: Suggestion): String? {
        val query = senseTranslationInput(term, meaning) ?: return null
        val translator = Translation.getClient(options(meaning.language))
        return try {
            withTimeoutOrNull(60_000) {
                translator.downloadModelIfNeeded(wifi).awaitResult()
                val result = translator.translate(query).awaitResult()
                senseTranslationEquivalent(result, term, meaning.category, meaning.language)
            }
        } finally {
            translator.close()
        }
    }

}

internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("La operación se interrumpió"))
    }
}

internal fun senseTranslationInput(term: String, meaning: Suggestion): String? {
    val expression = term.trim().replace(Regex("\\s+"), " ")
    val context = meaning.definition.ifBlank { meaning.examples.firstOrNull().orEmpty() }
        .trim().replace(Regex("\\s+"), " ")
    if (expression.isBlank() || '"' in expression || context.isBlank()) return null
    if (meaning.language == "ja") {
        return "$expression (${context.trimEnd('.').replaceFirstChar { it.lowercaseChar() }})".takeIf { it.length <= 500 }
    }
    val infinitive = meaning.category in setOf("verb", "phrase") && !expression.startsWith("to ", ignoreCase = true)
    val head = if (infinitive) "to $expression" else expression
    return "\"$head\" ($context)".takeIf { it.length <= 500 }
}

internal fun validJapaneseEquivalent(text: String) = text.matches(Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}々ー]{1,28}"""))

private val quotedEquivalent = Regex("""^(?:"([^"]+)"|“([^”]+)”|«([^»]+)»)\s*\(.+\)[.!]?\s*$""")
private val candidateWords = Regex("""[\p{L}][\p{L}\p{M} '-]{0,89}""")
private val alternative = Regex("""\b(?:o|u)\b""")
private val infinitive = Regex("""^[\p{L}]+(?:ar|er|ir)(?:se)?(?:\s|$)""")
private val passive = Regex("""^(?:ser|quedar|que|te|me|nos|se)\s+""")

internal fun senseTranslationEquivalent(response: String, term: String, category: String, language: String = "es"): String? {
    if (language == "ja") {
        val text = response.trim()
        val wrapped = Regex("""^(?:「([^」]+)」|“([^”]+)”|\"([^\"]+)\"|『([^』]+)』|([^（(「」“”"]+))\s*[（(].+[）)][。.]?$""").matchEntire(text)
        val candidate = wrapped?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: text
        return candidate.takeIf(::validJapaneseEquivalent)
    }
    val match = quotedEquivalent.matchEntire(response.trim()) ?: return null
    val candidate = match.groupValues.drop(1).first { it.isNotEmpty() }.trim()
        .replaceFirstChar { if (category == "proper noun") it else it.lowercaseChar() }
        .let { if (category in setOf("verb", "phrase")) it.removePrefix("para ") else it }
    if (!candidateWords.matches(candidate) || alternative.containsMatchIn(candidate)) return null
    if (senseText(candidate) == senseText(term)) return null
    if (category == "verb" && !infinitive.containsMatchIn(candidate)) return null
    if (category == "phrase" && participleFromGet(term) != null && !passive.containsMatchIn(candidate)) return null
    return candidate
}

@Composable
internal fun TranslatedText(
    value: String, attributed: Boolean, style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current
) {
    if (attributed) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, Modifier.align(Alignment.CenterVertically), style = style)
            TranslationAttribution(Modifier.align(Alignment.CenterVertically))
        }
    } else Text(value, style = style)
}

@Composable
internal fun TranslationAttribution(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val asset = if (dark) "google-attribution-dark" else "google-attribution"
    val badge = remember(context.assets, asset) {
        val encoded = context.assets.open("translation/$asset.png.b64")
            .bufferedReader().use { it.readText() }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
    }
    Image(badge, contentDescription = stringResource(R.string.ui_translated_by_google),
        modifier = modifier.size(120.dp, 16.dp))
}
