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
    val usage: String = ""
)

internal fun categoryLabel(category: String): String = when (category) {
    "noun" -> "Sustantivo"
    "proper noun" -> "Nombre propio"
    "verb" -> "Verbo"
    "adjective" -> "Adjetivo"
    "adverb" -> "Adverbio"
    "pronoun" -> "Pronombre"
    "preposition" -> "Preposición"
    "conjunction" -> "Conjunción"
    "interjection" -> "Interjección"
    "determiner" -> "Determinante"
    "particle" -> "Partícula"
    "phrase", "idiom" -> "Expresión"
    "proverb" -> "Proverbio"
    "numeral" -> "Numeral"
    "article" -> "Artículo"
    "prefix" -> "Prefijo"
    "suffix" -> "Sufijo"
    "abbreviation" -> "Abreviatura"
    "symbol" -> "Símbolo"
    else -> category.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

internal object Dictionary {
    private val cache = object : LinkedHashMap<String, List<Suggestion>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Suggestion>>): Boolean =
            size > 32
    }

    suspend fun lookup(term: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val query = term.trim().replace(Regex("\\s+"), " ")
        synchronized(cache) { cache[query] }?.let { return@withContext it }
        val fromWiki = try {
            queryWiki(query)
        } catch (_: IOException) {
            emptyList()
        } catch (_: JSONException) {
            emptyList()
        }
        ensureActive()
        val result = if (fromWiki.isNotEmpty()) fromWiki else queryFallback(query)
        ensureActive()
        if (result.isNotEmpty()) synchronized(cache) { cache[query] = result }
        result
    }

    private fun queryWiki(term: String): List<Suggestion> {
        val body = request(
            "https://en.wiktionary.org/w/api.php?action=parse&format=json&formatversion=2" +
                "&prop=text%7Crevid&redirects=1&disableeditsection=1&disablelimitreport=1&page=${encoded(term)}"
        ) ?: return emptyList()
        val response = JSONObject(body)
        val page = response.optJSONObject("parse") ?: return emptyList()
        val title = page.getString("title")
        val revision = page.getLong("revid")
        val source = "Colaboradores de Wiktionary · $title · CC BY-SA 4.0\n" +
            "https://en.wiktionary.org/w/index.php?oldid=$revision\n" +
            "https://creativecommons.org/licenses/by-sa/4.0/"
        return Wiktionary.parse(page.getString("text"), source, term, title)
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
                            synonymOptions = (0 until synonyms.length()).map { synonyms.getString(it) }
                        ))
                    }
                }
            }
        })
    }
}
