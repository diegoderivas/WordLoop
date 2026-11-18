package com.example.diegovocabcards

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.review.Direction
import io.github.openspacedrepetition.Rating
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MigrationTest {
    @Test fun preservesVersionOneAndNewContextAfterReopening() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        createLegacy(name, 1) { old ->
            old.execSQL("INSERT INTO Entry VALUES (1, 'run')")
            old.execSQL("INSERT INTO Sense VALUES ('original-id', 1, 'correr', 'I run every day.')")
            old.execSQL("INSERT INTO CardTag VALUES ('original-id', 'diario')")
        }
        fun open() = Room.databaseBuilder(context, VocabularyDatabase::class.java, name)
            .addMigrations(VocabularyDatabase.MIGRATION_1_2).build()
        var db = open()
        try {
            val original = db.vocabulary().cards().first().single()
            assertEquals("original-id", original.id)
            assertEquals("correr", original.spanish)
            assertEquals("I run every day.", original.example)
            assertEquals(listOf("diario"), original.tags)
            db.vocabulary().save(original.id, "run", "correr", "First example\nSecond example",
                "diario", "Move quickly.", "sprint", "verb, noun", "verb, noun")
            db.close()
            db = open()
            val updated = db.vocabulary().cards().first().single()
            assertEquals(original.id, updated.id)
            assertEquals("First example\nSecond example", updated.example)
            assertEquals("Move quickly.", updated.definition)
            assertEquals("verb, noun", updated.entryCategories)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createLegacy(name: String, version: Int, seed: (SQLiteDatabase) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = instrumentation.targetContext.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.example.diegovocabcards.data.VocabularyDatabase/$version.json"
        ).bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql")
                    .replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
            }
            seed(old)
            old.version = version
        }
    }

    @Test fun versionTwoPreservesAttributedSensesAndIndependentHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "review-migration-${UUID.randomUUID()}.db"
        createLegacy(name, 2) { old ->
            old.execSQL("INSERT INTO Entry VALUES (42, 'run', 'verb, noun')")
            old.execSQL("INSERT INTO Sense VALUES ('motion',42,'correr','I run.','Move quickly','sprint','verb','Source · CC BY-SA 3.0')")
            old.execSQL("INSERT INTO Sense VALUES ('business',42,'dirigir','I run a shop.','','','verb','')")
            old.execSQL("INSERT INTO CardTag VALUES ('motion','diario')")
        }
        fun open() = Room.databaseBuilder(context, VocabularyDatabase::class.java, name).build()
        val direction = Direction.ES_EN
        val start = 1_800_000_000_000L
        var db = open()
        try {
            run {
                val cards = db.vocabulary().cards().first().associateBy { it.id }
                val motion = cards.getValue("motion")
                assertEquals("run", motion.english)
                assertEquals("correr", motion.spanish)
                assertEquals("I run.", motion.example)
                assertEquals("Move quickly", motion.definition)
                assertEquals("sprint", motion.synonyms)
                assertEquals("verb, noun", motion.entryCategories)
                assertEquals("verb", motion.senseCategories)
                assertEquals("Source · CC BY-SA 3.0", motion.source)
                assertEquals(listOf("diario"), motion.tags)
                assertEquals("dirigir", cards.getValue("business").spanish)
                assertTrue(db.reviews().schedules().first().isEmpty())
                db.reviews().rate("motion", direction, 0, Rating.AGAIN, start)
            }
            db.close()
            db = open()
            run {
                val dao = db.reviews()
                val due = start + 60_000
                assertEquals(due, dao.latest("motion", direction)!!.memory.dueAt)
                assertNull(dao.latest("motion", Direction.EN_ES))
                assertNull(dao.latest("business", direction))
                dao.rate("motion", direction, 1, Rating.EASY, due - 1)
                assertEquals(1, dao.latest("motion", direction)!!.turn)
                kotlinx.coroutines.coroutineScope {
                    repeat(2) { launch { dao.rate("motion", direction, 1, Rating.GOOD, due) } }
                }
                dao.rate("motion", direction, 0, Rating.EASY, due + 1000)
                val last = dao.latest("motion", direction)!!
                assertEquals(2, last.turn)
                assertEquals(Rating.GOOD, last.rating)
                db.openHelper.readableDatabase.query("SELECT count(*) FROM ReviewEvent").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(2, it.getInt(0))
                }
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
