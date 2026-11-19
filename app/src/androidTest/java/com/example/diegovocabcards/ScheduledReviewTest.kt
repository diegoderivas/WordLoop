package com.example.diegovocabcards

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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

    private fun click(label: String) {
        val node = compose.onNodeWithText(label)
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }

    @Test fun mixedSessionGradesOnlyTheDisplayedDirection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val showing = androidx.compose.runtime.mutableStateOf(true)
        try {
            runBlocking { db.vocabulary().save("one", "fire", "fuego", "", "") }
            val model = VocabularyModel(app, db)
            compose.setContent { if (showing.value) VocabCardsApp(Appearance.Light, model) {} }
            click("Repasar en ambos sentidos")
            compose.onNodeWithText("fuego").assertIsDisplayed()
            compose.onNodeWithText("fire").assertDoesNotExist()
            click("Mostrar respuesta")
            click("Bien")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Inglés → Español · Nueva").fetchSemanticsNodes().isNotEmpty() }
            val first = runBlocking { db.reviews().latest("one", Direction.ES_EN) }
            assertNotNull(first)
            assertNull(runBlocking { db.reviews().latest("one", Direction.EN_ES) })
            compose.onNodeWithText("fuego").assertDoesNotExist()
            click("Mostrar respuesta")
            click("Fácil")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Repaso terminado").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Calificaciones guardadas: 2").assertExists()
            assertEquals(first, runBlocking { db.reviews().latest("one", Direction.ES_EN) })
            assertEquals(io.github.openspacedrepetition.Rating.EASY, runBlocking { db.reviews().latest("one", Direction.EN_ES) }!!.rating)
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

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
            val example = List(16) { "Example ${it + 1}: I run every morning before work." }.joinToString("\n")
            runBlocking { db.vocabulary().save("test", "run", "correr", example, "") }
            val model = VocabularyModel(app, db)
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
                    VocabCardsApp(Appearance.Light, model, ReviewSettings(retention = 92, nextLearningMinutes = 8),
                        clock = clock) {}
                }
            }
            click("Opciones avanzadas")
            click("Español → Inglés")
            compose.onNodeWithText("correr").assertIsDisplayed()
            compose.onNodeWithText("run").assertDoesNotExist()
            compose.onNodeWithText(example).assertDoesNotExist()
            compose.onNodeWithText("Bien").assertDoesNotExist()
            click("Mostrar respuesta")
            compose.onNodeWithText(example).assertExists()
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
            click("Volver a Inicio")
            click("Opciones avanzadas")
            compose.onNodeWithText("Español → Inglés").assertIsNotEnabled()
            compose.onNodeWithText("Próximo repaso", substring = true).assertExists()
            click("Repasar")
            click("Opciones avanzadas")
            click("Inglés → Español")
            compose.onNodeWithText("run").assertIsDisplayed()
            compose.onNodeWithText("correr").assertDoesNotExist()
            click("Mostrar respuesta")
            click("Fácil")
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Repaso terminado").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Volver").performClick()
            click("Práctica manual sin calificar")
            click("Español → Inglés")
            click("Iniciar práctica")
            click("Mostrar respuesta")
            click("Siguiente")
            compose.onNodeWithText("Práctica terminada").assertExists()
            val schedules = runBlocking { db.reviews().schedules().first() }
            assertEquals(2, schedules.size)
            val spanish = schedules.single { it.direction == Direction.ES_EN }
            val english = schedules.single { it.direction == Direction.EN_ES }
            assertEquals(1, spanish.turn)
            assertEquals(1, english.turn)
            assertEquals(clock.millis() + 480_000, spanish.dueAt)
            assertEquals(clock.millis() + java.time.Duration.ofDays(12).toMillis(), english.dueAt)
            val settings = app.getSharedPreferences("review-test", android.content.Context.MODE_PRIVATE)
            try {
                settings.writeReviewSettings(ReviewSettings(retention = 92))
                assertEquals(92, settings.readReviewSettings().retention)
                assertEquals(schedules, runBlocking { db.reviews().schedules().first() })
            } finally {
                settings.edit().clear().apply()
            }
        } finally {
            db.close()
        }
    }
}
