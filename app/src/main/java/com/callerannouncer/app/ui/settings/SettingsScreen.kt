package com.callerannouncer.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callerannouncer.app.domain.model.PlayMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تنظیمات") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("خواندن متن پیامک", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "در صورت فعال بودن، متن پیام نیز اعلام می‌شود",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = settings.readSmsBody,
                    onCheckedChange = viewModel::setReadSmsBody
                )
            }

            Column {
                Text(
                    "تعداد تکرار: ${settings.repeatCount}",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = settings.repeatCount.toFloat(),
                    onValueChange = { viewModel.setRepeatCount(it.roundToInt()) },
                    valueRange = 1f..5f,
                    steps = 3
                )
            }

            Column {
                Text(
                    "سرعت گفتار: ${"%.1f".format(settings.speechRate)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = settings.speechRate,
                    onValueChange = viewModel::setSpeechRate,
                    valueRange = 0.5f..2.0f
                )
            }

            Column {
                Text(
                    "زیر و بمی صدا: ${"%.1f".format(settings.pitch)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = settings.pitch,
                    onValueChange = viewModel::setPitch,
                    valueRange = 0.5f..2.0f
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("حالت پخش صدا", style = MaterialTheme.typography.titleMedium)
                PlayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.playMode == mode,
                        onClick = { viewModel.setPlayMode(mode) },
                        label = { Text(playModeLabel(mode)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            OutlinedTextField(
                value = settings.callPrefix,
                onValueChange = viewModel::setCallPrefix,
                label = { Text("پیشوند تماس") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = settings.callSuffix,
                onValueChange = viewModel::setCallSuffix,
                label = { Text("پسوند تماس") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = settings.smsPrefix,
                onValueChange = viewModel::setSmsPrefix,
                label = { Text("پیشوند پیامک") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

private fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.ALWAYS -> "همیشه پخش شود"
    PlayMode.ONLY_HEADPHONES_BLUETOOTH -> "فقط با هدفون / بلوتوث"
    PlayMode.SILENT_IF_MUTED -> "در حالت بی‌صدا اعلام نشود"
}
