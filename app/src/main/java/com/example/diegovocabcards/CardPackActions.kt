package com.example.diegovocabcards

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.diegovocabcards.data.Card
import com.example.diegovocabcards.data.CardPack
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CardPackActions(cards: List<Card>, importCards: suspend (List<Card>) -> Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var preview by remember { mutableStateOf<List<Card>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun work(failure: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = failure }
            finally { busy = false }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) work("Could not open this WordLoop card pack.") {
            preview = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use(CardPack::read)
            }
        }
    }
    TextButton(onClick = { menu = true }, enabled = !busy) { Text("Card packs") }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (menu) AlertDialog(
        onDismissRequest = { menu = false },
        title = { Text("Card packs") },
        text = { Text("Share vocabulary or add cards from someone else. Packs keep your study history and settings private.") },
        confirmButton = {
            TextButton(enabled = cards.isNotEmpty(), onClick = {
                menu = false
                selection = arrayListOf()
                selecting = true
            }) { Text("Share cards") }
        },
        dismissButton = {
            TextButton(onClick = { menu = false; open.launch(arrayOf("*/*")) }) { Text("Import pack") }
        }
    )
    if (selecting) AlertDialog(
        onDismissRequest = { if (!busy) selecting = false },
        title = { Text("Select cards to share") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Only cards matching the current library filters are shown.")
                TextButton(onClick = {
                    selection = if (selection.isEmpty()) ArrayList(cards.take(CardPack.MAX_CARDS).map(Card::id)) else arrayListOf()
                }, enabled = !busy) { Text(if (selection.isEmpty()) "Select all shown" else "Clear selection") }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(cards, key = Card::id) { card ->
                        val checked = card.id in selection
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = checked, enabled = !busy && (checked || selection.size < CardPack.MAX_CARDS),
                            role = Role.Checkbox,
                            onValueChange = { include ->
                                selection = ArrayList(if (include) selection + card.id else selection - card.id)
                            }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text("${card.english} → ${card.spanish}")
                        }
                    }
                }
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = {
            TextButton(enabled = selection.isNotEmpty() && !busy, onClick = {
                work("Could not prepare the selected cards.") {
                    val uri = withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "card-packs").apply { mkdirs() }
                        val pack = File(directory, "WordLoop-${java.util.UUID.randomUUID()}.wlp")
                        pack.writeBytes(CardPack.encode(cards.filter { it.id in selection }))
                        directory.listFiles()?.filter { it != pack && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
                            ?.forEach { it.delete() }
                        FileProvider.getUriForFile(context, "${context.packageName}.cardpacks", pack)
                    }
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, "WordLoop card pack", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Share WordLoop cards"))
                    selecting = false
                }
            }) { Text(if (selection.size == 1) "Share 1 card" else "Share ${selection.size} cards") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { selecting = false }) { Text("Cancel") } }
    )
    preview?.let { incoming ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text(if (incoming.size == 1) "Import 1 card?" else "Import ${incoming.size} cards?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Adds cards to your library and skips matching meanings.")
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(incoming) { card ->
                            Text("${card.english} → ${card.spanish} · ${languageName(card.language)}",
                                modifier = Modifier.padding(vertical = 5.dp))
                        }
                    }
                    if (message.isNotBlank()) Text(message)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    work("Could not import this pack. Your library was left unchanged.") {
                        val added = importCards(incoming)
                        preview = null
                        message = "Added $added · Skipped ${incoming.size - added}"
                    }
                }) { Text("Add to library") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel") } }
        )
    }
}
