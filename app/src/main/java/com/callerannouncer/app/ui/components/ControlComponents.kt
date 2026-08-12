package com.callerannouncer.app.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.callerannouncer.app.ui.theme.AppColors
import com.callerannouncer.app.util.PermissionHelper

private val SurfaceShape = RoundedCornerShape(20.dp)

@Composable
fun FeatureToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(
        targetValue = if (checked) AppColors.Ink.copy(alpha = 0.22f) else AppColors.Line,
        animationSpec = tween(250),
        label = "border",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SurfaceShape)
            .background(AppColors.Paper.copy(alpha = 0.92f))
            .border(1.dp, border, SurfaceShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) AppColors.Ink else AppColors.Muted,
            modifier = Modifier.size(26.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
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
fun PermissionsPanel(
    missing: List<String>,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ok = missing.isEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SurfaceShape)
            .background(if (ok) AppColors.SuccessSoft.copy(alpha = 0.55f) else AppColors.Paper.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = if (ok) AppColors.Success.copy(alpha = 0.25f) else AppColors.Line,
                shape = SurfaceShape,
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = if (ok) AppColors.Success else AppColors.Copper,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (ok) "مجوزها کامل است" else "چند مجوز لازم است",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Ink,
            )
        }
        Text(
            text = if (ok) {
                "دسترسی‌های تماس، پیامک و اعلان برای اعلام خودکار آماده است."
            } else {
                "برای تشخیص تماس و پیامک، این مجوزها را اعطا کنید:"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
        )
        if (!ok) {
            missing.forEach { perm ->
                Text(
                    text = PermissionHelper.labelFor(perm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.InkSoft,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Ink,
                    contentColor = AppColors.OnInk,
                ),
            ) {
                Text("اعطای مجوزها", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun CallSmsToggles(
    callEnabled: Boolean,
    smsEnabled: Boolean,
    onCallChange: (Boolean) -> Unit,
    onSmsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FeatureToggle(
            title = "اعلام تماس",
            subtitle = "نام یا شماره تماس‌گیرنده خوانده می‌شود",
            checked = callEnabled,
            icon = Icons.Rounded.Call,
            onCheckedChange = onCallChange,
        )
        FeatureToggle(
            title = "اعلام پیامک",
            subtitle = "فرستنده پیامک اعلام می‌شود",
            checked = smsEnabled,
            icon = Icons.Rounded.Sms,
            onCheckedChange = onSmsChange,
        )
    }
}
