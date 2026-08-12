package com.callerannouncer.app.ui.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.callerannouncer.app.service.tts.TtsModelState
import com.callerannouncer.app.ui.components.AppBackground
import com.callerannouncer.app.ui.components.BrandMark
import com.callerannouncer.app.ui.components.CallSmsToggles
import com.callerannouncer.app.ui.components.PermissionsPanel
import com.callerannouncer.app.ui.components.SectionLabel
import com.callerannouncer.app.ui.components.ServiceHero

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onOpenSettings: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val serviceRunning by viewModel.serviceRunning.collectAsStateWithLifecycle()
    val missing by viewModel.missingPermissions.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsModelState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val heroAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "heroAlpha",
    )
    val heroOffset by animateFloatAsState(
        targetValue = if (entered) 0f else 28f,
        animationSpec = tween(560, easing = FastOutSlowInEasing),
        label = "heroOffset",
    )
    val bodyAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(520, delayMillis = 120, easing = FastOutSlowInEasing),
        label = "bodyAlpha",
    )

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BrandMark(onSettings = onOpenSettings)

            ServiceHero(
                running = serviceRunning,
                onToggleService = {
                    if (serviceRunning) viewModel.stopService() else viewModel.startService()
                },
                onTestVoice = viewModel::testVoice,
                modifier = Modifier
                    .graphicsLayer { translationY = heroOffset }
                    .alpha(heroAlpha),
            )

            Column(
                modifier = Modifier.alpha(bodyAlpha),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SectionLabel(
                    title = "قابلیت‌ها",
                    subtitle = "اعلام تماس و پیامک را جداگانه کنترل کنید",
                )
                CallSmsToggles(
                    callEnabled = settings.isCallAnnouncerEnabled,
                    smsEnabled = settings.isSmsAnnouncerEnabled,
                    onCallChange = viewModel::setCallEnabled,
                    onSmsChange = viewModel::setSmsEnabled,
                )

                SectionLabel(
                    title = "دسترسی‌ها",
                    subtitle = "بدون این مجوزها، اعلام خودکار کار نمی‌کند",
                )
                PermissionsPanel(
                    missing = missing,
                    onRequestPermissions = onRequestPermissions,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
