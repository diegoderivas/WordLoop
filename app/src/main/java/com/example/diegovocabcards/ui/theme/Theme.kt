package com.example.diegovocabcards.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB2CAFF),
    onPrimary = Color(0xFF172749),
    primaryContainer = Color(0xFF263458),
    onPrimaryContainer = Color(0xFFEDF0FA),
    background = Color(0xFF101622),
    surfaceContainer = Color(0xFF1A253A),
    surface = Color(0xFF1A253A),
    onSurface = Color(0xFFEDF0FA),
    onSurfaceVariant = Color(0xFFBAC4DC),
    secondaryContainer = Color(0xFF263458),
    onSecondaryContainer = Color(0xFFEDF0FA),
    outlineVariant = Color(0xFF343D55)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF355AA6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE9FF),
    onPrimaryContainer = Color(0xFF182138),
    background = Color(0xFFF6F8FF),
    surfaceContainer = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF182138),
    onSurfaceVariant = Color(0xFF515D76),
    secondaryContainer = Color(0xFFDFE9FF),
    onSecondaryContainer = Color(0xFF182138),
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
