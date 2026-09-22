package com.pairplay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF7B5EA7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDF7),
    onPrimaryContainer = Color(0xFF2B1B44),
    secondary = Color(0xFFD98C6A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFBE0D0),
    onSecondaryContainer = Color(0xFF3F2114),
    background = Color(0xFFFDFAFF),
    surface = Color(0xFFFDFAFF),
    surfaceVariant = Color(0xFFF1E8F5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC7ADE8),
    onPrimary = Color(0xFF2B1B44),
    primaryContainer = Color(0xFF4A3B63),
    onPrimaryContainer = Color(0xFFEADDF7),
    secondary = Color(0xFFF0B18E),
    onSecondary = Color(0xFF3F2114),
    background = Color(0xFF15111C),
    surface = Color(0xFF15111C),
    surfaceVariant = Color(0xFF2A2338)
)

@Composable
fun PairPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
