package com.callerannouncer.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.callerannouncer.app.ui.theme.AppColors

private val HeroShape = RoundedCornerShape(28.dp)
private val ControlShape = RoundedCornerShape(16.dp)

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "اعلام‌گر",
                style = MaterialTheme.typography.displaySmall,
                color = AppColors.Ink,
            )
            Text(
                text = "اعلام صوتی تماس و پیامک — آفلاین",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Muted,
            )
        }
        if (onSettings != null) {
            TextButton(onClick = onSettings) {
                Text("تنظیمات", color = AppColors.InkSoft)
            }
        }
    }
}

@Composable
fun ServiceHero(
    running: Boolean,
    onToggleService: () -> Unit,
    onTestVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring",
    )
    val onSurface by animateColorAsState(
        targetValue = if (running) AppColors.OnInk else AppColors.Ink,
        animationSpec = tween(450),
        label = "onSurface",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(HeroShape)
            .background(
                brush = if (running) {
                    Brush.linearGradient(
                        colors = listOf(AppColors.Ink, AppColors.InkSoft),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(AppColors.Paper, AppColors.MistDeep),
                    )
                },
            )
            .border(
                width = 1.dp,
                color = if (running) Color.Transparent else AppColors.Line,
                shape = HeroShape,
            )
            .padding(horizontal = 22.dp, vertical = 26.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
                Canvas(modifier = Modifier.size(112.dp)) {
                    val stroke = 3.dp.toPx()
                    if (running) {
                        drawCircle(
                            color = AppColors.Success.copy(alpha = 0.28f),
                            radius = size.minDimension / 2f * ring,
                            style = Stroke(width = stroke),
                        )
                        drawCircle(
                            color = AppColors.Success.copy(alpha = 0.55f),
                            radius = size.minDimension / 2.55f,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    } else {
                        drawCircle(
                            color = AppColors.Line,
                            radius = size.minDimension / 2.4f,
                            style = Stroke(width = stroke),
                        )
                    }
                    drawCircle(
                        color = if (running) {
                            AppColors.SuccessSoft.copy(alpha = 0.2f)
                        } else {
                            AppColors.MistDeep
                        },
                        radius = size.minDimension / 3.1f,
                    )
                }
                Icon(
                    imageVector = if (running) {
                        Icons.AutoMirrored.Rounded.VolumeUp
                    } else {
                        Icons.Rounded.Stop
                    },
                    contentDescription = null,
                    tint = if (running) AppColors.SuccessSoft else AppColors.Muted,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            AnimatedContent(
                targetState = running,
                transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(180)) },
                label = "statusText",
            ) { isOn ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isOn) "سرویس فعال است" else "سرویس متوقف است",
                        style = MaterialTheme.typography.headlineMedium,
                        color = onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isOn) {
                            "تماس و پیامک‌های ورودی با صدای فارسی اعلام می‌شوند"
                        } else {
                            "برای اعلام خودکار، سرویس پس‌زمینه را روشن کنید"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = onSurface.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onToggleService,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = ControlShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) AppColors.Paper else AppColors.Copper,
                        contentColor = if (running) AppColors.Ink else AppColors.OnInk,
                    ),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (running) "توقف" else "شروع سرویس",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                OutlinedButton(
                    onClick = onTestVoice,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = ControlShape,
                    border = BorderStroke(1.dp, onSurface.copy(alpha = 0.35f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onSurface),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("آزمایش صدا", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun SectionLabel(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.Ink,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted,
            )
        }
    }
}
