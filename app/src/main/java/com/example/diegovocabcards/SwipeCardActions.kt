package com.example.diegovocabcards

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.diegovocabcards.data.Card
import kotlin.math.roundToInt

@Composable
internal fun SwipeCardActions(
    card: Card,
    onEdit: () -> Unit,
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onDelete: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val actionWidth = 72.dp
    val width = with(LocalDensity.current) { actionWidth.toPx() }
    val open by rememberUpdatedState(revealed)
    val reveal by rememberUpdatedState(onReveal)
    var draggingOffset by remember { mutableStateOf<Float?>(null) }
    val offset = draggingOffset ?: if (revealed) -width else 0f
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        if (offset < 0f) {
            Surface(
                modifier = Modifier.matchParentSize(),
                shape = CardDefaults.outlinedShape,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.width(actionWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        IconButton(enabled = revealed && draggingOffset == null, onClick = onDelete) {
                            Icon(painterResource(R.drawable.delete_sense), "Borrar acepción")
                        }
                    }
                }
            }
        }
        OutlinedCard(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth()
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(card.id, width) {
                    detectHorizontalDragGestures(
                        onDragStart = { draggingOffset = if (open) -width else 0f },
                        onDragEnd = {
                            reveal((draggingOffset ?: 0f) <= -width / 2)
                            draggingOffset = null
                        },
                        onDragCancel = { draggingOffset = null },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            draggingOffset = ((draggingOffset ?: 0f) + dx).coerceIn(-width, 0f)
                        }
                    )
                }
                .semantics {
                    customActions = listOf(CustomAccessibilityAction("Borrar acepción") {
                        onDelete()
                        true
                    })
                },
            content = {
                Column(Modifier.fillMaxWidth().padding(start = if (revealed) actionWidth else 0.dp), content = content)
            }
        )
    }
}
