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
    Review("Repasar"), Settings("Configuración")
}

@Composable
internal fun VocabCardsApp(mode: Appearance, onModeChange: (Appearance) -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val home = screen == Screen.Home
    BackHandler(enabled = !home) { screen = Screen.Home }

    Scaffold { insets ->
        key(screen) {
            Column(
                Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (home) {
                    Text("DiegoVocabCards", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = { screen = Screen.Home }) { Text("Volver a Inicio") }
                }
                Text(screen.title, modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge)
                when (screen) {
                    Screen.Home -> {
                        for (destination in listOf(Screen.Library, Screen.Add, Screen.Review, Screen.Settings)) {
                            OutlinedCard(onClick = { screen = destination }, modifier = Modifier.fillMaxWidth()) {
                                Text(destination.title, Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Screen.Library -> Text("Todavía no hay tarjetas en tu biblioteca.")
                    Screen.Add -> Text("El formulario para guardar palabras y expresiones estará disponible en la próxima etapa.")
                    Screen.Review -> Text("Todavía no hay tarjetas para repasar.")
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
