package com.example.diegovocabcards.data

import androidx.room.*
import com.example.diegovocabcards.review.ReviewSettings
import kotlinx.coroutines.flow.Flow

/** One row, so restoring preferences and study history can commit together. */
@Entity
internal data class LibrarySettings(
    @PrimaryKey val id: Int = 1,
    val theme: String = "system",
    val automaticSpeech: Boolean = true,
    val voice: String = "",
    @Embedded val review: ReviewSettings = ReviewSettings(),
    @ColumnInfo(defaultValue = "'es'") val language: String = "es"
)

internal data class LibraryBackup(
    val entries: List<Entry>,
    val senses: List<Sense>,
    val tags: List<CardTag>,
    val history: List<ReviewEvent>,
    val settings: LibrarySettings
) {
    fun validate() {
        require(settings.language in setOf("es", "ja") && senses.all { it.language in setOf("es", "ja") })
        require(settings.id == 1 && settings.review.isValid && settings.theme in setOf("system", "light", "dark"))
        val entryIds = entries.map { it.entryId }.toSet()
        require(entryIds.size == entries.size && entries.all { it.entryId > 0 && it.english.isNotBlank() })
        require(entries.distinctBy { it.english }.size == entries.size)
        val senseIds = senses.map { it.id }.toSet()
        require(senseIds.size == senses.size && senses.all {
            it.entryId in entryIds && it.id.isNotBlank() && it.spanish.isNotBlank()
        })
        require(tags.toSet().size == tags.size && tags.all { it.cardId in senseIds && it.label.isNotBlank() })
        for ((_, turns) in history.groupBy { it.senseId to it.direction }) {
            turns.sortedBy { it.turn }.forEachIndexed { index, event ->
                val m = event.memory
                require(event.senseId in senseIds && event.turn == index + 1 && m.reviewedAt <= m.dueAt)
                require(m.stability.isFinite() && m.stability > 0 && m.difficulty in 1.0..10.0)
                require(m.step == null || m.step >= 0)
            }
        }
    }
}

@Dao
internal abstract class BackupDao {
    @Query("SELECT COUNT(*) FROM Sense") abstract suspend fun countSenses(): Int
    @Query("SELECT COUNT(*) FROM ReviewEvent") abstract suspend fun countEvents(): Int
    @Query("SELECT * FROM Entry ORDER BY entryId") abstract suspend fun entries(): List<Entry>
    @Query("SELECT * FROM Sense ORDER BY rowid") abstract suspend fun senses(): List<Sense>
    @Query("SELECT * FROM CardTag ORDER BY rowid") abstract suspend fun tags(): List<CardTag>
    @Query("SELECT * FROM ReviewEvent ORDER BY rowid") abstract suspend fun events(): List<ReviewEvent>
    @Query("SELECT * FROM LibrarySettings WHERE id=1") abstract fun observeSettings(): Flow<LibrarySettings?>
    @Query("SELECT * FROM LibrarySettings WHERE id=1") abstract suspend fun settings(): LibrarySettings?
    @Upsert abstract suspend fun configure(settings: LibrarySettings)
    @Insert protected abstract suspend fun addEntries(rows: List<Entry>)
    @Insert protected abstract suspend fun addSenses(rows: List<Sense>)
    @Insert protected abstract suspend fun addTags(rows: List<CardTag>)
    @Insert protected abstract suspend fun addEvents(rows: List<ReviewEvent>)
    @Query("DELETE FROM Sense") protected abstract suspend fun clearSenses()
    @Query("DELETE FROM Entry") protected abstract suspend fun clearEntries()

    @Transaction
    open suspend fun snapshot(fallback: LibrarySettings) =
        LibraryBackup(entries(), senses(), tags(), events(), settings() ?: fallback)

    @Transaction
    open suspend fun restore(backup: LibraryBackup) {
        backup.validate()
        clearSenses() // Cascades to tags and review events.
        clearEntries()
        addEntries(backup.entries)
        addSenses(backup.senses)
        addTags(backup.tags)
        addEvents(backup.history)
        configure(backup.settings)
    }
}
