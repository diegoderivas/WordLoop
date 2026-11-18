package com.example.diegovocabcards

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
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
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.example.diegovocabcards.data.VocabularyDatabase/1.json"
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
            old.execSQL("INSERT INTO Entry VALUES (1, 'run')")
            old.execSQL("INSERT INTO Sense VALUES ('original-id', 1, 'correr', 'I run every day.')")
            old.execSQL("INSERT INTO CardTag VALUES ('original-id', 'diario')")
            old.version = 1
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
}
