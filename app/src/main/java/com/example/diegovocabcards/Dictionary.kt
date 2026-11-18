package com.example.diegovocabcards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal data class Suggestion(val definition: String, val example: String, val synonyms: String,
    val category: String, val source: String)

internal object Dictionary {
    suspend fun lookup(term: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val connection = URI("https://api.dictionaryapi.dev/api/v2/entries/en/" +
            URLEncoder.encode(term, "UTF-8").replace("+", "%20")).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            if (connection.responseCode == 404) return@withContext emptyList()
            check(connection.responseCode == 200)
            val entries = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
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
                            add(Suggestion(definition.getString("definition"), definition.optString("example"),
                                (0 until synonyms.length()).joinToString(", ") { synonyms.getString(it) },
                                meaning.optString("partOfSpeech"), source))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
