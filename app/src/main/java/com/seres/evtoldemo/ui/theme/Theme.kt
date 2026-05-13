package com.seres.evtoldemo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandAccent,
    onPrimary = Night900,
    primaryContainer = BrandSeed,
    onPrimaryContainer = TextMain,
    secondary = BrandSeed,
    onSecondary = TextMain,
    secondaryContainer = Night700,
    onSecondaryContainer = TextMain,
    tertiary = BrandHighlight,
    onTertiary = Night900,
    tertiaryContainer = BrandSupport.copy(alpha = 0.28f),
    onTertiaryContainer = TextMain,
    background = Night900,
    onBackground = TextMain,
    surface = Night800,
    onSurface = TextMain,
    surfaceVariant = Night700,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Night700.copy(alpha = 0.72f),
    outline = BrandSeed.copy(alpha = 0.55f),
    outlineVariant = TextSecondary.copy(alpha = 0.35f),
    error = BrandHighlight,
    onError = Night900,
    errorContainer = BrandHighlight.copy(alpha = 0.22f),
    onErrorContainer = TextMain
)

private val LightColorScheme = lightColorScheme(
    primary = BrandSeed,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = BrandAccent.copy(alpha = 0.24f),
    onPrimaryContainer = Night900,
    secondary = BrandSeed,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = BrandSeed.copy(alpha = 0.16f),
    onSecondaryContainer = Night900,
    tertiary = BrandHighlight,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = BrandHighlight.copy(alpha = 0.14f),
    onTertiaryContainer = Night900,
    background = androidx.compose.ui.graphics.Color(0xFFF5F7FC),
    onBackground = androidx.compose.ui.graphics.Color(0xFF111827),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF111827),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7ECF5),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF516079),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF0F4FA),
    outline = androidx.compose.ui.graphics.Color(0xFF8A98B2),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFD3DAE8),
    error = androidx.compose.ui.graphics.Color(0xFFB3261E),
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFF9DEDC),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410E0B)
)

@Composable
fun EvtolLanDemoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
