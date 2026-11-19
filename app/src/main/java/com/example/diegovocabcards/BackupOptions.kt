package com.example.diegovocabcards

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun BackupOptions(dao: BackupDao, defaults: LibrarySettings) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<LibraryBackup?>(null) }
    var currentCounts by remember { mutableStateOf(0 to 0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message = when (error.message) {
                    "La versión de esta copia es incompatible." -> error.message.orEmpty()
                    else -> "No se pudo completar la operación. Revisá el archivo y volvé a intentarlo."
                }
            } finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) work {
            withContext(Dispatchers.IO) {
                val snapshot = dao.snapshot(defaults)
                requireNotNull(resolver.openOutputStream(uri, "wt")).use { BackupFormat.write(snapshot, it) }
            }
            message = "Copia exportada."
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) work {
            val incoming = withContext(Dispatchers.IO) {
                requireNotNull(resolver.openInputStream(uri)).use(BackupFormat::read)
            }
            currentCounts = dao.countSenses() to dao.countEvents()
            preview = incoming
        }
    }
    fun exportCurrent() = export.launch("DiegoVocabCards-${LocalDate.now()}.dvc")
    Text("Copia de seguridad", style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = ::exportCurrent, enabled = !busy) { Text("Exportar copia") }
    OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Restaurar copia") }
    if (busy) Text("Procesando copia…")
    if (message.isNotBlank() && preview == null) Text(message)
    preview?.let { incoming ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("Restaurar biblioteca") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Se reemplazarán ${currentCounts.first} acepciones, ${currentCounts.second} calificaciones y los ajustes actuales.")
                    Text("La copia contiene ${incoming.senses.size} acepciones y ${incoming.history.size} calificaciones.")
                    if (currentCounts.first > 0) TextButton(onClick = ::exportCurrent, enabled = !busy) {
                        Text("Exportar una copia previa")
                    }
                    if (message.isNotBlank()) Text(message)
                }
            },
            confirmButton = { Button(enabled = !busy, onClick = {
                work {
                    dao.restore(incoming)
                    preview = null
                    message = "Biblioteca restaurada."
                }
            }) { Text("Confirmar restauración") } },
            dismissButton = { TextButton(onClick = { preview = null; message = "" }, enabled = !busy) { Text("Cancelar") } }
        )
    }
}
