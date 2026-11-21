package com.example.diegovocabcards

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private fun backupCounts(senses: Int, events: Int): String {
    val cards = if (senses == 1) "1 meaning" else "$senses meanings"
    val grades = if (events == 1) "1 rating" else "$events ratings"
    return "$cards and $grades"
}

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
        scope.launch(Dispatchers.Main.immediate) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                message = when (error.message) {
                    "This backup version is incompatible." -> error.message.orEmpty()
                    else -> "Could not complete the operation. Check the file and try again."
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
            message = "Backup exported."
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
    fun exportCurrent() = export.launch("WordLoop-${LocalDate.now()}.dvc")
    Text(stringResource(R.string.ui_backup), style = MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick = ::exportCurrent, enabled = !busy) { Text(stringResource(R.string.ui_export_backup)) }
    OutlinedButton(onClick = { restore.launch(arrayOf("*/*")) }, enabled = !busy) { Text(stringResource(R.string.ui_restore_backup)) }
    if (busy) Text(stringResource(R.string.ui_processing_backup))
    if (message.isNotBlank() && preview == null) Text(message)
    preview?.let { incoming ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text(stringResource(R.string.ui_restore_library)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your library contains ${backupCounts(currentCounts.first, currentCounts.second)}. Restoring replaces your library and settings.")
                    Text("This backup contains ${backupCounts(incoming.senses.size, incoming.history.size)}.")
                    if (currentCounts.first > 0) TextButton(onClick = ::exportCurrent, enabled = !busy) {
                        Text(stringResource(R.string.ui_export_current_library_first))
                    }
                    if (message.isNotBlank()) Text(message)
                }
            },
            confirmButton = { Button(enabled = !busy, onClick = {
                work {
                    dao.restore(incoming)
                    preview = null
                    message = "Library restored."
                }
            }) { Text(stringResource(R.string.ui_confirm_restore)) } },
            dismissButton = { TextButton(onClick = { preview = null; message = "" }, enabled = !busy) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
}
