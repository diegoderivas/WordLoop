package com.example.diegovocabcards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SupportingSenseExample(
    examples: List<String>,
    current: String,
    enabled: Boolean,
    onChoose: () -> Unit,
    onUse: (String) -> Unit,
    onRemove: () -> Unit
) {
    val recommended = examples.first()
    Column {
        Text("Ejemplo recomendado", style = MaterialTheme.typography.titleSmall)
        if (current != recommended) Text(recommended, style = MaterialTheme.typography.bodyMedium)
        if (examples.size > 1) {
            TextButton(onClick = onChoose, enabled = enabled) { Text("Elegir ejemplo") }
        } else if (current != recommended) {
            TextButton(onClick = { onUse(recommended) }, enabled = enabled) { Text("Usar ejemplo") }
        }
        if (current.isNotBlank()) {
            TextButton(onClick = onRemove, enabled = enabled) { Text("Quitar ejemplo") }
        }
    }
}

@Composable
internal fun ExamplePicker(
    examples: List<String>,
    current: String,
    onClose: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Elegí un ejemplo") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(examples) { index, example ->
                    OutlinedCard(onClick = { onSelect(example) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (index == 0) Text("Recomendado", style = MaterialTheme.typography.labelMedium)
                            if (example == current) Text("En la tarjeta", style = MaterialTheme.typography.labelMedium)
                            Text(example)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSelect("") }) { Text("Continuar sin ejemplo") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cerrar") } }
    )
}
