package com.example.diegovocabcards

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.*
import com.example.diegovocabcards.review.*
import io.github.openspacedrepetition.Rating
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class LibraryBackupTest {
    @Test fun restoresVersionOneAndDiscardsOnlyTheSharedCategory() = runBlocking {
        // Frozen v1 record layout, independent of the current exporter.
        val payload = ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { out ->
                fun field(value: String) {
                    val encoded = value.toByteArray(Charsets.UTF_8)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
                field("dark")
                out.writeBoolean(false)
                field("uk")
                listOf(92, 2, 9, 8, 1).forEach(out::writeInt)
                out.writeLong(42)
                field("fire")
                field("obsolete-shared")
                out.writeInt(1)
                field("flame")
                out.writeLong(42)
                listOf("fuego", "A fire is burning.", "Flames.", "flame", "noun", "Dictionary",
                    "A guide", "Second example", "/faɪə/", "IPA source").forEach(::field)
                out.writeInt(1)
                field("flame")
                field("diario")
                out.writeInt(1)
                field("flame")
                field("EN_ES")
                out.writeInt(1)
                field("GOOD")
                field("LEARNING")
                out.writeInt(1)
                out.writeDouble(3.0)
                out.writeDouble(6.0)
                out.writeLong(1_800_000_600_000)
                out.writeLong(1_800_000_000_000)
            }
        }.toByteArray()
        val file = ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { out ->
                out.writeUTF("DiegoVocabCards")
                out.writeInt(1)
                out.writeInt(payload.size)
                out.write(java.security.MessageDigest.getInstance("SHA-256").digest(payload))
                out.write(payload)
            }
        }.toByteArray()
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            VocabularyDatabase::class.java).build()
        try {
            val decoded = BackupFormat.read(file.inputStream())
            db.backup().restore(decoded)
            assertEquals(decoded, db.backup().snapshot(LibrarySettings()))
            val card = db.vocabulary().cards().first().single()
            assertEquals("noun", card.senseCategories)
            assertEquals(listOf("diario"), card.tags)
            assertEquals(1_800_000_600_000, db.reviews().latest("flame", Direction.EN_ES)!!.memory.dueAt)
            db.openHelper.readableDatabase.query("PRAGMA table_info(Entry)").use { columns ->
                while (columns.moveToNext()) assertNotEquals("entryCategories", columns.getString(1))
            }
        } finally { db.close() }
    }

    @Test fun roundTripIntoFreshDatabasePreservesBothSchedulesAndRejectsDamage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, VocabularyDatabase::class.java).build()
        val name = "restore-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, VocabularyDatabase::class.java, name).build()
        var restored = open()
        try {
            val settings = LibrarySettings(theme = "dark", automaticSpeech = false, voice = "voice-uk",
                review = ReviewSettings(92, 2, 9, 8))
            val dao = source.vocabulary()
            dao.save("a", "fire", "fuego", "There was a fire.", "daily,heat", "Flames.", "flame", "noun",
                "dictionary", "Meaning: heat and light.", "The fire spread.", "/faɪə/", "dictionary IPA")
            dao.save("b", "fire", "despedir", "", "daily", senseCategories = "verb")
            for (direction in Direction.entries) {
                val now = 1_800_000_000_000L + direction.ordinal * 12345L
                source.reviews().rate("a", direction, 0, Rating.GOOD, now, settings.review)
                val due = source.reviews().latest("a", direction)!!.memory.dueAt
                source.reviews().rate("a", direction, 1, Rating.HARD, due, settings.review)
            }
            source.reviews().rate("b", Direction.ES_EN, 0, Rating.EASY, 1_800_000_000_789L)
            source.backup().configure(settings)
            val expected = source.backup().snapshot(LibrarySettings())
            val bytes = ByteArrayOutputStream().also { BackupFormat.write(expected, it) }.toByteArray()
            restored.backup().restore(BackupFormat.read(bytes.inputStream()))
            restored.close()
            restored = open()
            suspend fun assertIntact() {
                assertEquals(expected, restored.backup().snapshot(LibrarySettings()))
                assertEquals(source.vocabulary().cards().first(), restored.vocabulary().cards().first())
                assertEquals(source.reviews().schedules().first().toSet(), restored.reviews().schedules().first().toSet())
            }
            assertIntact()
            val damaged = bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            val future = bytes.clone().also { it[2 + "DiegoVocabCards".length + 3] = 99 }
            for (invalid in listOf(damaged, future, bytes.copyOf(bytes.size / 2))) {
                assertTrue(runCatching { restored.backup().restore(BackupFormat.read(invalid.inputStream())) }.isFailure)
                assertIntact()
            }
            restored.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER fail_restore BEFORE INSERT ON ReviewEvent BEGIN SELECT RAISE(ABORT, 'test'); END")
            assertTrue(runCatching { restored.backup().restore(expected.copy(settings = LibrarySettings())) }.isFailure)
            assertIntact()
        } finally {
            source.close()
            restored.close()
            context.deleteDatabase(name)
        }
    }
}
