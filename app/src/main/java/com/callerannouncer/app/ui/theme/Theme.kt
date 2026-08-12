package com.callerannouncer.app.ui.theme

import android.app.Activity
import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = AppColors.Ink,
    onPrimary = AppColors.OnInk,
    primaryContainer = AppColors.MistDeep,
    onPrimaryContainer = AppColors.Ink,
    secondary = AppColors.Copper,
    onSecondary = AppColors.OnInk,
    secondaryContainer = AppColors.CopperSoft,
    onSecondaryContainer = AppColors.Ink,
    tertiary = AppColors.Success,
    background = AppColors.Mist,
    onBackground = AppColors.Ink,
    surface = AppColors.Paper,
    onSurface = AppColors.Ink,
    surfaceVariant = AppColors.MistDeep,
    onSurfaceVariant = AppColors.Muted,
    outline = AppColors.Line,
    error = AppColors.Danger,
    errorContainer = AppColors.DangerSoft,
)

@Composable
fun CallerAnnouncerTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = AndroidColor.TRANSPARENT
            window.navigationBarColor = AndroidColor.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = LightColors,
            typography = Typography,
            content = content,
        )
    }
}
