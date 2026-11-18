package com.example.diegovocabcards

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.VocabularyDatabase
import com.example.diegovocabcards.review.Direction
import com.example.diegovocabcards.review.ReviewSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScheduledReviewTest {
    @get:Rule val compose = createComposeRule()

    private fun click(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()

    @Test fun failedWriteKeepsRevealedCardAndManualPracticeLeavesHistoryIntact() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val start = Instant.parse("2026-11-18T12:00:00Z")
        val clock = object : Clock() {
            var time = start
            override fun instant() = time
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = Clock.fixed(time, zone)
        }
        try {
            val cards = runBlocking {
                db.vocabulary().save("test", "run", "correr", "I run.\nThey run.", "")
                db.vocabulary().cards().first()
            }
            compose.setContent {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ScheduledReview(cards, db.reviews(), clock, ReviewSettings(retention = 92, nextLearningMinutes = 8)) {}
                }
            }
            click("Iniciar repaso")
            compose.onNodeWithText("correr").assertIsDisplayed()
            compose.onNodeWithText("run").assertDoesNotExist()
            compose.onNodeWithText("I run.", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Bien").assertDoesNotExist()
            click("Mostrar respuesta")
            compose.onNodeWithText("I run.\nThey run.").assertExists()
            compose.onNodeWithText("8 min").assertExists()
            compose.onNodeWithText("12 días").assertExists()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TEMP TRIGGER reject_grade BEFORE INSERT ON ReviewEvent BEGIN SELECT RAISE(ABORT, 'test'); END")
            click("Bien")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("No se pudo guardar", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("run").assertExists()
            assertNull(runBlocking { db.reviews().latest("test", Direction.ES_EN) })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_grade")
            compose.runOnIdle { clock.time = start.plusSeconds(20) }
            compose.onNodeWithText("8 min").assertExists()
            click("Bien")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Repaso terminado").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Calificaciones guardadas: 1").assertExists()
            click("Volver a elegir sentido")
            compose.onNodeWithText("Iniciar repaso").assertIsNotEnabled()
            compose.onNodeWithText("Próximo repaso", substring = true).assertExists()
            click("Inglés → Español")
            compose.onNodeWithText("Iniciar repaso").assertIsEnabled()
            click("Práctica manual sin calificar")
            click("Iniciar práctica")
            click("Mostrar respuesta")
            click("Siguiente")
            compose.onNodeWithText("Práctica terminada").assertExists()
            val schedules = runBlocking { db.reviews().schedules().first() }
            assertEquals(1, schedules.single().turn)
            assertEquals(Direction.ES_EN, schedules.single().direction)
            assertEquals(clock.millis() + 480_000, schedules.single().dueAt)
        } finally {
            db.close()
        }
    }
}
