package com.example.diegovocabcards

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyDatabaseTest {
    @Test fun retryEditAndIndependentSenses() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, VocabularyDatabase::class.java).build()
        try {
            val dao = db.vocabulary()
            assertEquals(emptyList<Any>(), dao.cards().first())
            repeat(2) { dao.save("one", "brush up on", "refrescar conocimientos de", "", "A, a, B") }
            assertEquals(listOf("A", "B"), dao.cards().first().single().tags.sorted())
            dao.save("two", "brush up on", "repasar", "Example", "C")
            dao.save("one", "brush up on", "repasar conocimientos de", "", "")
            val cards = dao.cards().first()
            assertEquals(listOf("two", "one"), cards.map { it.id })
            assertEquals(listOf("repasar", "repasar conocimientos de"), cards.map { it.spanish })
            assertEquals(emptyList<String>(), cards.last().tags)
            dao.save("one", "go over", "repasar", "", "")
            assertEquals(listOf("brush up on", "go over"), dao.cards().first().map { it.english })
        } finally {
            db.close()
        }
    }
}
