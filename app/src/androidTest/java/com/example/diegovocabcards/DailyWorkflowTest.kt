package com.example.diegovocabcards

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diegovocabcards.data.LibrarySettings
import com.example.diegovocabcards.data.VocabularyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DailyWorkflowTest {
    @get:Rule val compose = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun createFindEditPracticeReviewExportAndRestoreThroughAndroidPicker() {
        val app = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(app, VocabularyDatabase::class.java).build()
        val showing = mutableStateOf(true)
        try {
            runBlocking { db.vocabulary().save("job", "fire", "despedir", "", "trabajo", senseCategories = "verb") }
            val model = VocabularyModel(app, db)
            compose.setContent { if (showing.value) VocabCardsApp(Appearance.Light, model) {} }
            tap("Añadir tarjeta")
            enter("Palabra o expresión en inglés", "fire")
            compose.onNodeWithText("Ver tarjetas (1)", substring = true).assertExists()
            enter("Traducción al español", "fuego")
            enter("Nueva etiqueta", "calor")
            tap("Guardar")
            waitFor("Buscar en inglés o español")
            compose.onNodeWithText("Buscar en inglés o español").performTextInput("fue")
            tap("fuego")
            compose.onNodeWithText("Traducción al español").performScrollTo().performTextReplacement("fuego o incendio")
            tap("Guardar")
            waitFor("Buscar en inglés o español")
            tap("Filtrar por etiqueta")
            compose.onAllNodesWithText("calor").filter(hasAnyAncestor(isPopup())).onFirst().performClick()
            tap("Practicar esta etiqueta")
            tap("Inglés → Español")
            tap("Iniciar práctica")
            compose.onNodeWithText("fuego o incendio").assertDoesNotExist()
            tap("Mostrar respuesta")
            compose.onNodeWithText("fuego o incendio").assertExists()
            tap("Siguiente")
            waitFor("Práctica terminada")
            compose.onNodeWithContentDescription("Volver").performClick()
            waitFor("Quitar filtro")
            compose.onNodeWithText("despedir").assertDoesNotExist()
            assertTrue(runBlocking { db.backup().events().isEmpty() })
            tap("Inicio")
            tap("Opciones avanzadas")
            tap("Inglés → Español")
            tap("Mostrar respuesta")
            tap("Bien")
            compose.waitUntil(10000) { runBlocking { db.backup().countEvents() == 1 } }
            compose.onNodeWithContentDescription("Volver").performClick()
            tap("Biblioteca")
            tap("Ver progreso")
            waitFor("Historial reciente")
            captureEmulator("respaldo-progreso-claro.png")
            tap("Cerrar")
            compose.onNodeWithContentDescription("Configuración").performClick()
            tap("Exportar copia")
            val name = "VocabCards-test-${UUID.randomUUID()}.dvc"
            val filename = systemNode { it.isEditable }
            assertTrue(filename.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
            systemClick(systemNode { it.text?.toString()?.lowercase() in setOf("save", "guardar") })
            waitFor("Copia exportada.")
            val before = runBlocking { db.backup().snapshot(LibrarySettings()) }
            runBlocking { db.vocabulary().deleteSense("job") }
            tap("Restaurar copia")
            val document = systemNode { it.text?.toString() == name }
            systemClick(document)
            waitFor("Confirmar restauración")
            compose.onNodeWithText("Exportar una copia previa").assertExists()
            captureEmulator("respaldo-confirmacion-claro.png")
            tap("Confirmar restauración")
            waitFor("Biblioteca restaurada.")
            assertEquals(before, runBlocking { db.backup().snapshot(LibrarySettings()) })
        } finally {
            compose.runOnIdle { showing.value = false }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun enter(label: String, value: String) =
        compose.onNodeWithText(label).performScrollTo().performTextInput(value)

    private fun waitFor(text: String) = compose.waitUntil(15000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun tap(text: String) {
        val node = compose.onNodeWithText(text)
        if (hasAnyAncestor(hasScrollAction()).matches(node.fetchSemanticsNode())) node.performScrollTo()
        node.performClick()
    }

    private fun systemNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { find(it) }?.let { return it }
            return null
        }
        val end = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                if (root.packageName?.contains("documentsui") == true) find(root)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        error("No se encontró el control en el selector de archivos de Android")
    }

    private fun systemClick(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = target.parent
        }
        error("El selector no permitió tocar el documento")
    }
}
