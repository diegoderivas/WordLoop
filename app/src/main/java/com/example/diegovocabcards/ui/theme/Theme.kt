package com.example.diegovocabcards.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBDCBFF),
    onPrimary = Color(0xFF172749),
    primaryContainer = Color(0xFF263458),
    onPrimaryContainer = Color(0xFFEDF0FA),
    background = Color(0xFF11141D),
    surface = Color(0xFF1B2030),
    onSurface = Color(0xFFEDF0FA),
    onSurfaceVariant = Color(0xFFBAC4DC),
    secondaryContainer = Color(0xFF263458),
    onSecondaryContainer = Color(0xFFEDF0FA),
    outlineVariant = Color(0xFF343D55)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF344985),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EDFF),
    onPrimaryContainer = Color(0xFF1C2337),
    background = Color(0xFFF6F7FC),
    surface = Color.White,
    onSurface = Color(0xFF1C2337),
    onSurfaceVariant = Color(0xFF515D76),
    secondaryContainer = Color(0xFFE8EDFF),
    onSecondaryContainer = Color(0xFF1C2337),
    outlineVariant = Color(0xFFDCE1EE)
)

@Composable
fun DiegoVocabCardsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
