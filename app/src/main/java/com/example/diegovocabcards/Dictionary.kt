package com.example.diegovocabcards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

internal data class Suggestion(
    val definition: String,
    val category: String,
    val source: String,
    val spanish: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val synonymOptions: List<String> = emptyList(),
    val usage: String = "",
    val family: String = "",
    val pronunciation: DictionaryPronunciation? = null,
    val language: String = "es"
)

internal fun categoryLabel(category: String): String = category.replaceFirstChar { it.titlecase(Locale.ROOT) }

internal object Dictionary {
    private val cache = object : LinkedHashMap<String, List<Suggestion>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Suggestion>>): Boolean =
            size > 32
    }

    suspend fun lookup(term: String, language: String = "es"): List<Suggestion> = withContext(Dispatchers.IO) {
        val query = term.trim().replace(Regex("\\s+"), " ")
        synchronized(cache) { cache["$language/$query"] }?.let { return@withContext it }
        val exact = wikiMeanings(query, language)
        ensureActive()
        val phrase = if (exact.isEmpty()) {
            participleFromGet(query)?.let { getConstruction(wikiMeanings(it, language)) }.orEmpty()
        } else emptyList()
        ensureActive()
        val result = exact.ifEmpty { phrase }.ifEmpty { queryFallback(query) }.map { it.copy(language = language) }
        ensureActive()
        if (result.isNotEmpty()) synchronized(cache) { cache["$language/$query"] = result }
        result
    }

    private data class WikiPage(val html: String, val title: String, val source: String)

    private fun wikiMeanings(term: String, language: String): List<Suggestion> {
        val page = fetchWikiIfAvailable(term) ?: return emptyList()
        val translations = if (Wiktionary.hasTranslationSubpage(page.html, page.title)) {
            fetchWikiIfAvailable("${page.title}/translations")
                ?.takeIf { it.title == "${page.title}/translations" }
                ?.let { TranslationPage(it.html, it.source) }
        } else null
        return Wiktionary.parse(page.html, page.source, term, page.title, translations, language)
    }

    private fun fetchWikiIfAvailable(term: String): WikiPage? = try {
        fetchWiki(term)
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }

    private fun fetchWiki(term: String): WikiPage? {
        val body = request(
            "https://en.wiktionary.org/w/api.php?action=parse&format=json&formatversion=2" +
                "&prop=text%7Crevid&redirects=1&disableeditsection=1&disablelimitreport=1&page=${encoded(term)}"
        ) ?: return null
        val response = JSONObject(body)
        val page = response.optJSONObject("parse") ?: return null
        val title = page.getString("title")
        val revision = page.getLong("revid")
        val source = "Colaboradores de Wiktionary · $title · CC BY-SA 4.0\n" +
            "https://en.wiktionary.org/w/index.php?oldid=$revision\n" +
            "https://creativecommons.org/licenses/by-sa/4.0/"
        return WikiPage(page.getString("text"), title, source)
    }

    private fun encoded(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun request(url: String): String? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("User-Agent", "DiegoVocabCards/1.0 (Android dictionary for personal study)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            when (val status = connection.responseCode) {
                404 -> return null
                200 -> Unit
                else -> throw IOException("Dictionary HTTP $status")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                buildString {
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (length + count > 2_000_000) throw IOException("Dictionary response too large")
                        append(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun queryFallback(term: String): List<Suggestion> {
        val body = request("https://api.dictionaryapi.dev/api/v2/entries/en/${encoded(term)}")
            ?: return emptyList()
        val entries = JSONArray(body)
        return distinctMeanings(buildList {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val license = entry.optJSONObject("license") ?: continue
                if (license.optString("name") != "CC BY-SA 3.0") continue
                val source = "Wiktionary vía Free Dictionary API · CC BY-SA 3.0\n" +
                    entry.getJSONArray("sourceUrls").let { urls ->
                        (0 until urls.length()).joinToString("\n") { urls.getString(it) }
                    } + "\n" + license.optString("url")
                val ipa = if (senseText(entry.optString("word")) == senseText(term)) {
                    val phonetics = entry.optJSONArray("phonetics") ?: JSONArray()
                    val variants = (0 until phonetics.length()).mapNotNull { index ->
                        val record = phonetics.optJSONObject(index) ?: return@mapNotNull null
                        val text = ipaTranscription(record.optString("text")) ?: return@mapNotNull null
                        val filename = record.optString("audio").substringBefore('?').substringAfterLast('/')
                        val region = Regex("""-(uk|us)\.[a-z0-9]+$""", RegexOption.IGNORE_CASE)
                            .find(filename)?.groupValues?.get(1).orEmpty()
                        "$region $text".trim()
                    }
                    preferredIpa((variants + entry.optString("phonetic")).joinToString(" · ")).orEmpty()
                } else ""
                val pronunciation = ipa.takeIf(String::isNotBlank)?.let { DictionaryPronunciation(it, source) }
                val meanings = entry.getJSONArray("meanings")
                for (j in 0 until meanings.length()) {
                    val meaning = meanings.getJSONObject(j)
                    val definitions = meaning.getJSONArray("definitions")
                    for (n in 0 until definitions.length()) {
                        val definition = definitions.getJSONObject(n)
                        val synonyms = definition.optJSONArray("synonyms") ?: JSONArray()
                        add(Suggestion(
                            definition.getString("definition"), meaning.optString("partOfSpeech"), source,
                            examples = listOf(definition.optString("example")).filter { it.isNotBlank() },
                            synonymOptions = (0 until synonyms.length()).map { synonyms.getString(it) },
                            pronunciation = pronunciation
                        ))
                    }
                }
            }
        })
    }
}
