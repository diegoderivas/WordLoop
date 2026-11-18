package com.example.diegovocabcards

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.launch

internal class VocabularyModel(app: Application) : AndroidViewModel(app) {
    private val dao = VocabularyDatabase.get(app).vocabulary()
    val cards = dao.cards()
    var saving by mutableStateOf(false)
        private set
    var savedId by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf(false)
        private set

    fun acknowledgeSave() { savedId = null }

    fun save(id: String, english: String, spanish: String, example: String, tags: String) {
        if (saving || savedId != null) return
        saving = true
        saveError = false
        viewModelScope.launch {
            try {
                dao.save(id, english, spanish, example, tags)
                savedId = id
            } catch (failure: SQLiteException) {
                saveError = true
            } finally {
                saving = false
            }
        }
    }
}
