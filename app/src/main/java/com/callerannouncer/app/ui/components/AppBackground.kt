package com.callerannouncer.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import com.callerannouncer.app.ui.theme.AppColors

@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val drift = rememberInfiniteTransition(label = "bg")
    val shift by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shift",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(AppColors.MistDeep, AppColors.Mist, AppColors.Paper),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AppColors.Ink.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                    center = Offset(w * (0.15f + shift * 0.08f), h * 0.08f),
                    radius = w * 0.75f,
                ),
                center = Offset(w * (0.15f + shift * 0.08f), h * 0.08f),
                radius = w * 0.75f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AppColors.Copper.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                    center = Offset(w * (0.92f - shift * 0.06f), h * 0.42f),
                    radius = w * 0.55f,
                ),
                center = Offset(w * (0.92f - shift * 0.06f), h * 0.42f),
                radius = w * 0.55f,
            )
            val step = 28f
            var y = 0f
            while (y < h) {
                drawLine(
                    color = AppColors.Ink.copy(alpha = 0.035f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f)),
                )
                y += step
            }
        }
        content()
    }
}
