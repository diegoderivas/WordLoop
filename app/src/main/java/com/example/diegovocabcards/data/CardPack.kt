package com.example.diegovocabcards.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/** A portable selection of cards. Deliberately separate from the full library backup. */
internal object CardPack {
    const val MAX_CARDS = 1_000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val FORMAT = "wordloop-card-pack"

    fun encode(cards: List<Card>): ByteArray {
        require(cards.size in 1..MAX_CARDS)
        val entries = JSONArray()
        for (card in cards) {
            entries.put(JSONObject().apply {
                put("english", card.english)
                put("translation", card.spanish)
                put("language", card.language)
                put("example", card.example)
                put("definition", card.definition)
                put("synonyms", card.synonyms)
                put("category", card.senseCategories)
                put("source", card.source)
                put("guide", card.usageNote)
                put("guideExamples", card.generatedExamples)
                put("ipa", card.ipa)
                put("ipaSource", card.ipaSource)
                put("tags", JSONArray(card.tags))
            })
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("cards", entries)
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun read(input: InputStream): List<Card> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size == -1) break
            require(output.size() + size <= MAX_BYTES)
            output.write(buffer, 0, size)
        }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
        val pack = JSONObject(text)
        require(pack.getString("format") == FORMAT && pack.getInt("version") == 1)
        val entries = pack.getJSONArray("cards")
        require(entries.length() in 1..MAX_CARDS)
        return List(entries.length()) { index ->
            val row = entries.getJSONObject(index)
            val english = row.getString("english").trim()
            val translation = row.getString("translation").trim()
            val language = row.getString("language")
            require(english.isNotEmpty() && translation.isNotEmpty() && language in setOf("es", "ja"))
            val tags = row.getJSONArray("tags")
            Card(id = "", english = english, spanish = translation, language = language,
                tags = List(tags.length()) { tags.getString(it) },
                example = row.getString("example"), definition = row.getString("definition"),
                synonyms = row.getString("synonyms"), senseCategories = row.getString("category"),
                source = row.getString("source"), usageNote = row.getString("guide"),
                generatedExamples = row.getString("guideExamples"), ipa = row.getString("ipa"),
                ipaSource = row.getString("ipaSource"))
        }
    }
}
