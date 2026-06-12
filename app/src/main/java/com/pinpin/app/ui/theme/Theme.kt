package com.pinpin.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * App-wide Material 3 theme.
 *
 * The color scheme follows the system theme: when the system is in
 * dark mode we use the OneUI deep-slate dark palette, otherwise the
 * OneUI-light palette. Switching happens via [isSystemInDarkTheme],
 * which Material3 recomposes on when the configuration changes.
 *
 * The system bars are drawn as transparent in `themes.xml`; Compose
 * draws content underneath via `enableEdgeToEdge()` in
 * [com.pinpin.app.ui.MainActivity].
 */
private val PinPinDarkColorScheme = darkColorScheme(
    primary = PinPinPrimary,
    onPrimary = PinPinOnPrimary,
    background = PinPinBackground,
    onBackground = PinPinOnBackground,
    surface = PinPinSurface,
    onSurface = PinPinOnSurface,
    surfaceVariant = PinPinSurfaceVariant,
    onSurfaceVariant = PinPinOnSurfaceVariant,
    surfaceContainer = PinPinSurfaceContainer,
    error = PinPinError,
    onError = PinPinOnError,
)

private val PinPinLightColorScheme = lightColorScheme(
    primary = PinPinLightPrimary,
    onPrimary = PinPinLightOnPrimary,
    background = PinPinLightBackground,
    onBackground = PinPinLightOnBackground,
    surface = PinPinLightSurface,
    onSurface = PinPinLightOnSurface,
    surfaceVariant = PinPinLightSurfaceVariant,
    onSurfaceVariant = PinPinLightOnSurfaceVariant,
    surfaceContainer = PinPinLightSurfaceContainer,
    error = PinPinLightError,
    onError = PinPinLightOnError,
)

@Composable
fun PinPinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) PinPinDarkColorScheme else PinPinLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PinPinTypography,
        content = content,
    )
}