package com.callerannouncer.app.ui.voicecache

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callerannouncer.app.domain.model.TtsEngineMode
import com.callerannouncer.app.domain.model.VoiceCacheScope
import com.callerannouncer.app.ui.components.AppBackground
import com.callerannouncer.app.ui.components.SectionLabel
import com.callerannouncer.app.ui.theme.AppColors

private val PanelShape = RoundedCornerShape(20.dp)
private val HeroShape = RoundedCornerShape(28.dp)
private val ControlShape = RoundedCornerShape(16.dp)

@Composable
fun VoiceCacheScreen(
    viewModel: VoiceCacheViewModel,
    onBack: () -> Unit,
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val selectedScope by viewModel.scope.collectAsStateWithLifecycle()
    val wifiOnly by viewModel.wifiOnly.collectAsStateWithLifecycle()
    val engineMode by viewModel.engineMode.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(progress.running, progress.done) {
        if (!progress.running) viewModel.refreshStats()
    }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "بازگشت",
                        tint = AppColors.Ink,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "صدای مخاطبان",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Ink,
                    )
                    Text(
                        text = "یک بار دریافت، همیشه آفلاین و بی‌درنگ",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                }
            }

            ProgressHero(
                running = progress.running,
                fraction = progress.fraction,
                done = progress.done,
                total = progress.total,
                currentLabel = progress.currentLabel,
                message = progress.message,
                onStart = viewModel::start,
                onCancel = viewModel::cancel,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    value = "${stats.entries}",
                    label = "اعلام ذخیره‌شده",
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    value = formatBytes(stats.bytes),
                    label = "فضای اشغال‌شده",
                )
            }

            if (engineMode != TtsEngineMode.ONLINE_EDGE) {
                NoticePanel(
                    text = "موتور صدا روی آفلاین است. این کش برای صدای آنلاین ساخته می‌شود؛ " +
                        "برای استفاده از آن، موتور صدا را در تنظیمات روی آنلاین بگذارید.",
                )
            }

            SectionLabel(
                title = "دامنه آماده‌سازی",
                subtitle = "هر مخاطب دو اعلام دارد: تماس و پیامک",
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceCacheScope.entries.forEach { option ->
                    ScopeOption(
                        selected = selectedScope == option,
                        title = scopeTitle(option),
                        subtitle = scopeHint(option),
                        enabled = !progress.running,
                        onClick = { viewModel.selectScope(option) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .background(AppColors.Paper.copy(alpha = 0.92f))
                    .border(1.dp, AppColors.Line, PanelShape)
                    .padding(16.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "فقط با وای‌فای",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.Ink,
                        )
                        Text(
                            text = "جلوی مصرف اینترنت همراه را می‌گیرد",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Muted,
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = viewModel::setWifiOnly,
                        enabled = !progress.running,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppColors.OnInk,
                            checkedTrackColor = AppColors.Ink,
                            uncheckedThumbColor = AppColors.Paper,
                            uncheckedTrackColor = AppColors.Line,
                            uncheckedBorderColor = AppColors.Line,
                        ),
                    )
                }
            }

            NoticePanel(
                text = "اعلام هر مخاطب بار اولی که زنگ می‌زند هم به‌طور خودکار ذخیره می‌شود. " +
                    "اگر متن اعلام، صدا یا سرعت گفتار را عوض کنید، اعلام‌ها با تنظیم جدید دوباره ساخته می‌شوند.",
            )

            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !progress.running && stats.entries > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = ControlShape,
                border = BorderStroke(1.dp, AppColors.Danger.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Danger),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("پاک کردن کش صدا", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = AppColors.Paper,
            title = { Text("پاک کردن کش صدا؟", color = AppColors.Ink) },
            text = {
                Text(
                    text = "همه اعلام‌های ذخیره‌شده حذف می‌شوند و دفعه بعد باید از اینترنت گرفته شوند.",
                    color = AppColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearCache()
                }) {
                    Text("پاک کن", color = AppColors.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("انصراف", color = AppColors.InkSoft)
                }
            },
        )
    }
}

@Composable
private fun ProgressHero(
    running: Boolean,
    fraction: Float,
    done: Int,
    total: Int,
    currentLabel: String,
    message: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(450),
        label = "cacheProgress",
    )
    val onSurface by animateColorAsState(
        targetValue = if (running) AppColors.OnInk else AppColors.Ink,
        animationSpec = tween(400),
        label = "heroInk",
    )

    Box(
        modifier = Modifier
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
                    Brush.linearGradient(colors = listOf(AppColors.Paper, AppColors.MistDeep))
                },
            )
            .border(
                width = 1.dp,
                color = if (running) AppColors.Ink else AppColors.Line,
                shape = HeroShape,
            )
            .padding(horizontal = 22.dp, vertical = 26.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                Canvas(modifier = Modifier.size(132.dp)) {
                    val stroke = 9.dp.toPx()
                    val inset = stroke / 2f
                    drawArc(
                        color = if (running) {
                            AppColors.OnInk.copy(alpha = 0.18f)
                        } else {
                            AppColors.Line
                        },
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (animated > 0f) {
                        drawArc(
                            color = if (running) AppColors.SuccessSoft else AppColors.Success,
                            startAngle = -90f,
                            sweepAngle = 360f * animated,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${(animated * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = onSurface,
                    )
                    if (total > 0) {
                        Text(
                            text = "$done / $total",
                            style = MaterialTheme.typography.labelMedium,
                            color = onSurface.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = when {
                    running -> "در حال آماده‌سازی صدای مخاطبان"
                    message.isNotBlank() -> message
                    else -> "آماده شروع"
                },
                style = MaterialTheme.typography.titleMedium,
                color = onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    running && currentLabel.isNotBlank() -> currentLabel
                    running -> "اتصال به سرور صدا…"
                    else -> "صدای اعلام مخاطبان یک بار دریافت و روی گوشی ذخیره می‌شود"
                },
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.78f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(22.dp))

            if (running) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = ControlShape,
                    border = BorderStroke(1.dp, onSurface.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = onSurface),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("توقف", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = ControlShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Copper,
                        contentColor = AppColors.OnInk,
                    ),
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("شروع آماده‌سازی", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(PanelShape)
            .background(AppColors.Paper.copy(alpha = 0.92f))
            .border(1.dp, AppColors.Line, PanelShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = AppColors.Ink)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
    }
}

@Composable
private fun NoticePanel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.InkSoft,
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(AppColors.CopperSoft.copy(alpha = 0.45f))
            .border(1.dp, AppColors.Copper.copy(alpha = 0.22f), PanelShape)
            .padding(14.dp),
    )
}

@Composable
private fun ScopeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val border by animateColorAsState(
        targetValue = if (selected) AppColors.Ink else AppColors.Line,
        animationSpec = tween(220),
        label = "scopeBorder",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(
                if (selected) {
                    AppColors.Ink.copy(alpha = 0.06f)
                } else {
                    AppColors.Paper.copy(alpha = 0.9f)
                },
            )
            .border(1.dp, border, PanelShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) AppColors.Ink else AppColors.Muted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
    }
}

private fun scopeTitle(scope: VoiceCacheScope): String = when (scope) {
    VoiceCacheScope.FREQUENT -> "پرتماس‌ترین‌ها"
    VoiceCacheScope.TOP -> "۳۰۰ مخاطب اول"
    VoiceCacheScope.ALL -> "همه مخاطبان"
}

private fun scopeHint(scope: VoiceCacheScope): String = when (scope) {
    VoiceCacheScope.FREQUENT ->
        "۶۰ نفری که بیشتر با آن‌ها در تماس بوده‌اید — سریع‌ترین گزینه"
    VoiceCacheScope.TOP ->
        "پرتماس‌ها، سپس مخاطبان ستاره‌دار و بقیه دفترچه تلفن"
    VoiceCacheScope.ALL ->
        "کامل‌ترین حالت؛ روی دفترچه‌های بزرگ چند دقیقه طول می‌کشد"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0"
    bytes < 1024L * 1024L -> String.format("%.0f کیلوبایت", bytes / 1024f)
    else -> String.format("%.1f مگابایت", bytes / (1024f * 1024f))
}
