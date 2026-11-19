package com.example.diegovocabcards.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@Entity(indices = [Index(value = ["english"], unique = true)])
internal data class Entry(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val english: String,
    @ColumnInfo(defaultValue = "''") val entryCategories: String = ""
)

@Entity(
    foreignKeys = [ForeignKey(entity = Entry::class, parentColumns = ["entryId"],
        childColumns = ["entryId"])],
    indices = [Index("entryId")]
)
internal data class Sense(
    @PrimaryKey val id: String,
    val entryId: Long,
    val spanish: String,
    val example: String,
    @ColumnInfo(defaultValue = "''") val definition: String = "",
    @ColumnInfo(defaultValue = "''") val synonyms: String = "",
    @ColumnInfo(defaultValue = "''") val senseCategories: String = "",
    @ColumnInfo(defaultValue = "''") val source: String = "",
    @ColumnInfo(defaultValue = "''") val usageNote: String = "",
    @ColumnInfo(defaultValue = "''") val generatedExamples: String = ""
)

@Entity(primaryKeys = ["cardId", "label"], foreignKeys = [
    ForeignKey(entity = Sense::class, parentColumns = ["id"], childColumns = ["cardId"],
        onDelete = ForeignKey.CASCADE)
])
internal data class CardTag(val cardId: String, val label: String)

internal data class Card(
    val id: String,
    val english: String,
    val spanish: String,
    val example: String,
    @Relation(parentColumn = "id", entityColumn = "cardId", entity = CardTag::class,
        projection = ["label"])
    val tags: List<String>,
    val definition: String = "",
    val synonyms: String = "",
    val senseCategories: String = "",
    val entryCategories: String = "",
    val source: String = "",
    val usageNote: String = "",
    val generatedExamples: String = ""
)

@Dao
internal abstract class VocabularyDao {
    @Transaction
    @Query("SELECT id, english, spanish, example, definition, synonyms, senseCategories, entryCategories, source, usageNote, generatedExamples FROM Sense JOIN Entry USING(entryId) ORDER BY Sense.rowid DESC")
    abstract fun cards(): Flow<List<Card>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEntry(entry: Entry): Long

    @Query("SELECT entryId FROM Entry WHERE english = :english")
    protected abstract suspend fun findEntry(english: String): Long

    @Upsert
    protected abstract suspend fun putSense(sense: Sense)

    @Query("DELETE FROM CardTag WHERE cardId = :id")
    protected abstract suspend fun clearTags(id: String)

    @Insert
    protected abstract suspend fun insertTags(tags: List<CardTag>)

    @Query("DELETE FROM Entry WHERE NOT EXISTS (SELECT 1 FROM Sense WHERE Sense.entryId = Entry.entryId)")
    protected abstract suspend fun removeUnusedEntries()

    @Query("UPDATE Entry SET entryCategories = :categories WHERE entryId = :id")
    protected abstract suspend fun categories(id: Long, categories: String)

    @Transaction
    open suspend fun save(
        id: String, english: String, spanish: String, example: String, tags: String,
        definition: String = "", synonyms: String = "", senseCategories: String = "",
        entryCategories: String? = null, source: String = "", usageNote: String = "",
        generatedExamples: String = ""
    ) {
        val term = english.trim()
        require(term.isNotEmpty() && spanish.isNotBlank())
        val inserted = insertEntry(Entry(english = term))
        val entryId = if (inserted == -1L) findEntry(term) else inserted
        putSense(Sense(id, entryId, spanish.trim(), example.trim(), definition, synonyms, senseCategories,
            source, usageNote, generatedExamples))
        entryCategories?.let { categories(entryId, it) }
        clearTags(id)
        val labels = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
        insertTags(labels.map { CardTag(id, it) })
        removeUnusedEntries()
    }
}

@Database(entities = [Entry::class, Sense::class, CardTag::class, ReviewEvent::class],
    version = 4, autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)])
internal abstract class VocabularyDatabase : RoomDatabase() {
    abstract fun vocabulary(): VocabularyDao
    abstract fun reviews(): ReviewDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Entry ADD COLUMN entryCategories TEXT NOT NULL DEFAULT ''")
                for (column in listOf("definition", "synonyms", "senseCategories", "source")) {
                    db.execSQL("ALTER TABLE Sense ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
                }
            }
        }
        @Volatile private var instance: VocabularyDatabase? = null

        fun get(context: Context): VocabularyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                VocabularyDatabase::class.java, "vocabulary.db").addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
