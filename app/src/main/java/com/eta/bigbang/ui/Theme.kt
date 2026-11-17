package com.eta.bigbang.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0B66C3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF),
    onPrimaryContainer = Color(0xFF00131F),
    secondary = Color(0xFF4A6178),
    secondaryContainer = Color(0xFFD3E4F8),
    tertiary = Color(0xFF00696E),
    tertiaryContainer = Color(0xFF9EF1F6),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFE1E8F0),
    background = Color(0xFFFCFCFF),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA6C8FF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF00497F),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFB3C8E0),
    tertiary = Color(0xFF82D4D9),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF2A3138),
    background = Color(0xFF101418),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
