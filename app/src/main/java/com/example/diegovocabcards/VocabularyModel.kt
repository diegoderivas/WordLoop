package com.example.diegovocabcards

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.launch

internal enum class AfterSave { Library, AnotherCard, AnotherSense }
internal data class SavedCard(val card: Card, val next: AfterSave)

internal class VocabularyModel(app: Application, database: VocabularyDatabase) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, VocabularyDatabase.get(app))

    private val dao = database.vocabulary()
    val reviews = database.reviews()

    val cards = dao.cards()
    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf<SavedCard?>(null)
        private set
    var failedId by mutableStateOf<String?>(null)
        private set

    fun acknowledgeSave() { saved = null }

    fun save(card: Card, next: AfterSave, editedEntryCategories: String?) {
        if (saving || saved != null) return
        saving = true
        failedId = null
        viewModelScope.launch {
            try {
                dao.save(card.id, card.english, card.spanish, card.example, card.tags.joinToString(","), card.definition, card.synonyms, card.senseCategories,
                    editedEntryCategories, card.source, card.usageNote, card.generatedExamples, card.ipa, card.ipaSource)
                saved = SavedCard(card, next)
            } catch (failure: SQLiteException) {
                failedId = card.id
            } finally {
                saving = false
            }
        }
    }
}
