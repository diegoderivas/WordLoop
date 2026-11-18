package com.example.diegovocabcards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private enum class Screen(val title: String) {
    Home("Inicio"), Library("Biblioteca"), Add("Añadir tarjeta"),
    Review("Repasar"), Settings("Configuración"), Edit("Editar tarjeta")
}

@Composable
internal fun VocabCardsApp(mode: Appearance, model: VocabularyModel, onModeChange: (Appearance) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val home = screen == Screen.Home
    val rows by model.cards.collectAsState(initial = null)
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf<String?>(null) }
    val back = {
        if (!model.saving) screen = if (screen == Screen.Edit) Screen.Library else Screen.Home
    }
    BackHandler(enabled = !home, onBack = back)
    LaunchedEffect(model.savedId) {
        model.savedId?.let {
            highlighted = it
            screen = Screen.Library
            model.acknowledgeSave()
        }
    }

    Scaffold { insets ->
        key(screen) {
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()
                    .then(if (screen == Screen.Library) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (home) {
                    Text("DiegoVocabCards", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = back, enabled = !model.saving) {
                        Text(if (screen == Screen.Edit) "Volver a Biblioteca" else "Volver a Inicio")
                    }
                }
                Text(screen.title, modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge)
                val cards = rows
                if (cards == null && screen != Screen.Home && screen != Screen.Settings) {
                    Text("Cargando biblioteca…")
                    return@Column
                }
                when (screen) {
                    Screen.Home -> {
                        for (destination in listOf(Screen.Library, Screen.Add, Screen.Review, Screen.Settings)) {
                            OutlinedCard(onClick = { screen = destination }, modifier = Modifier.fillMaxWidth()) {
                                Text(destination.title, Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Screen.Library -> Library(cards.orEmpty(), highlighted, onAdd = { screen = Screen.Add }) {
                        editId = it
                        screen = Screen.Edit
                    }
                    Screen.Add -> CardEditor(null, cards?.firstOrNull()?.tags.orEmpty(), model)
                    Screen.Edit -> cards?.find { it.id == editId }?.let { CardEditor(it, emptyList(), model) }
                    Screen.Review -> Practice(cards.orEmpty()) { screen = Screen.Add }
                    Screen.Settings -> AppearanceOptions(mode, onModeChange)
                }
            }
        }
    }
}

@Composable
private fun AppearanceOptions(mode: Appearance, onModeChange: (Appearance) -> Unit) {
    Text("Apariencia", style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        for (option in Appearance.entries) {
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = mode == option, role = Role.RadioButton,
                        onClick = { onModeChange(option) })
                    .heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Text(when (option) {
                    Appearance.System -> "Automático (seguir al teléfono)"
                    Appearance.Light -> "Claro"
                    Appearance.Dark -> "Oscuro"
                })
            }
        }
    }
}
