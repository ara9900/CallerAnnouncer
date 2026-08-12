package com.callerannouncer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val TealPrimary = Color(0xFF0F766E)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealContainer = Color(0xFFCCFBF1)
private val AmberSecondary = Color(0xFFB45309)
private val SurfaceLight = Color(0xFFF0FDFA)
private val SurfaceDark = Color(0xFF0F172A)

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealContainer,
    secondary = AmberSecondary,
    background = SurfaceLight,
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF115E59),
    secondary = Color(0xFFFBBF24),
    background = SurfaceDark,
    surface = Color(0xFF1E293B),
)

@Composable
fun CallerAnnouncerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content
        )
    }
}
