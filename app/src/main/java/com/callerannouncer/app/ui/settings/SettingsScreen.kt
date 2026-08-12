package com.callerannouncer.app.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callerannouncer.app.domain.model.OnlineEdgeVoice
import com.callerannouncer.app.domain.model.PlayMode
import com.callerannouncer.app.domain.model.TtsEngineMode
import com.callerannouncer.app.ui.components.AppBackground
import com.callerannouncer.app.ui.components.SectionLabel
import com.callerannouncer.app.ui.theme.AppColors
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(20.dp)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "بازگشت",
                        tint = AppColors.Ink,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "تنظیمات",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.Ink,
                    )
                    Text(
                        text = "صدا، تکرار و متن اعلام را دقیق تنظیم کنید",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                }
            }

            SettingsPanel {
                ToggleLine(
                    title = "خواندن متن پیامک",
                    subtitle = "علاوه بر فرستنده، متن پیام هم خوانده شود",
                    checked = settings.readSmsBody,
                    onCheckedChange = viewModel::setReadSmsBody,
                )
            }

            SectionLabel(title = "موتور صدا", subtitle = "آفلاین یا آنلاین رایگان — برای مقایسه کیفیت")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TtsEngineMode.entries.forEach { mode ->
                    PlayModeOption(
                        selected = settings.ttsEngineMode == mode,
                        title = ttsEngineModeLabel(mode),
                        subtitle = ttsEngineModeHint(mode),
                        onClick = { viewModel.setTtsEngineMode(mode) },
                    )
                }
            }

            if (settings.ttsEngineMode == TtsEngineMode.ONLINE_EDGE) {
                SectionLabel(title = "صدای آنلاین", subtitle = "Microsoft Edge — Dilara یا Farid")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OnlineEdgeVoice.entries.forEach { voice ->
                        PlayModeOption(
                            selected = settings.onlineEdgeVoice == voice,
                            title = onlineEdgeVoiceLabel(voice),
                            subtitle = onlineEdgeVoiceHint(voice),
                            onClick = { viewModel.setOnlineEdgeVoice(voice) },
                        )
                    }
                }
            }

            SectionLabel(title = "صدا", subtitle = "سرعت، زیر و بمی و تعداد تکرار")
            SettingsPanel {
                SliderLine(
                    title = "تعداد تکرار",
                    valueLabel = "${settings.repeatCount}",
                    value = settings.repeatCount.toFloat(),
                    range = 1f..5f,
                    steps = 3,
                    onChange = { viewModel.setRepeatCount(it.roundToInt()) },
                )
                Spacer(modifier = Modifier.height(14.dp))
                SliderLine(
                    title = "سرعت گفتار",
                    valueLabel = String.format("%.1f", settings.speechRate),
                    value = settings.speechRate,
                    range = 0.5f..2.0f,
                    steps = 0,
                    onChange = viewModel::setSpeechRate,
                )
                Spacer(modifier = Modifier.height(14.dp))
                SliderLine(
                    title = "زیر و بمی",
                    valueLabel = String.format("%.1f", settings.pitch),
                    value = settings.pitch,
                    range = 0.5f..2.0f,
                    steps = 0,
                    onChange = viewModel::setPitch,
                )
            }

            SectionLabel(title = "مسیر پخش", subtitle = "چه زمانی اعلام شنیده شود")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayMode.entries.forEach { mode ->
                    PlayModeOption(
                        selected = settings.playMode == mode,
                        title = playModeLabel(mode),
                        subtitle = playModeHint(mode),
                        onClick = { viewModel.setPlayMode(mode) },
                    )
                }
            }

            SectionLabel(title = "متن اعلام", subtitle = "قالب جمله‌هایی که خوانده می‌شود")
            SettingsPanel {
                StyledField(
                    value = settings.callPrefix,
                    onValueChange = viewModel::setCallPrefix,
                    label = "پیشوند تماس",
                )
                Spacer(modifier = Modifier.height(12.dp))
                StyledField(
                    value = settings.callSuffix,
                    onValueChange = viewModel::setCallSuffix,
                    label = "پسوند تماس",
                )
                Spacer(modifier = Modifier.height(12.dp))
                StyledField(
                    value = settings.smsPrefix,
                    onValueChange = viewModel::setSmsPrefix,
                    label = "پیشوند پیامک",
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(AppColors.Paper.copy(alpha = 0.92f))
            .border(1.dp, AppColors.Line, PanelShape)
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun ToggleLine(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

@Composable
private fun SliderLine(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
        Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = AppColors.Copper)
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = AppColors.Ink,
            activeTrackColor = AppColors.Ink,
            inactiveTrackColor = AppColors.Line,
        ),
    )
}

@Composable
private fun PlayModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val border by animateColorAsState(
        targetValue = if (selected) AppColors.Ink else AppColors.Line,
        animationSpec = tween(220),
        label = "modeBorder",
    )
    val bg by animateColorAsState(
        targetValue = if (selected) AppColors.Ink.copy(alpha = 0.06f) else AppColors.Paper.copy(alpha = 0.9f),
        animationSpec = tween(220),
        label = "modeBg",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(bg)
            .border(1.dp, border, PanelShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink)
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
    }
}

@Composable
private fun StyledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AppColors.Ink,
            unfocusedBorderColor = AppColors.Line,
            focusedLabelColor = AppColors.Ink,
            cursorColor = AppColors.Ink,
            focusedContainerColor = AppColors.Paper,
            unfocusedContainerColor = AppColors.Paper,
        ),
    )
}

private fun ttsEngineModeLabel(mode: TtsEngineMode): String = when (mode) {
    TtsEngineMode.OFFLINE -> "آفلاین (Piper امیر)"
    TtsEngineMode.ONLINE_EDGE -> "آنلاین رایگان (Microsoft Edge)"
}

private fun ttsEngineModeHint(mode: TtsEngineMode): String = when (mode) {
    TtsEngineMode.OFFLINE -> "بدون اینترنت بعد از دانلود مدل — داخل اپ"
    TtsEngineMode.ONLINE_EDGE -> "نیاز به اینترنت — بدون API key و هزینه"
}

private fun onlineEdgeVoiceLabel(voice: OnlineEdgeVoice): String = when (voice) {
    OnlineEdgeVoice.DILARA -> "دیلارا (زن)"
    OnlineEdgeVoice.FARID -> "فرید (مرد)"
}

private fun onlineEdgeVoiceHint(voice: OnlineEdgeVoice): String = when (voice) {
    OnlineEdgeVoice.DILARA -> "fa-IR-DilaraNeural — Azure neural فارسی"
    OnlineEdgeVoice.FARID -> "fa-IR-FaridNeural — Azure neural فارسی"
}

private fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.ALWAYS -> "همیشه پخش شود"
    PlayMode.ONLY_HEADPHONES_BLUETOOTH -> "فقط هدفون / بلوتوث"
    PlayMode.SILENT_IF_MUTED -> "در حالت بی‌صدا اعلام نشود"
}

private fun playModeHint(mode: PlayMode): String = when (mode) {
    PlayMode.ALWAYS -> "اعلام روی بلندگوی گوشی یا هدفون انجام می‌شود"
    PlayMode.ONLY_HEADPHONES_BLUETOOTH -> "فقط وقتی خروجی صوتی خارجی وصل باشد"
    PlayMode.SILENT_IF_MUTED -> "اگر رینگر روی بی‌صدا یا ویبره باشد، اعلام نمی‌شود"
}
