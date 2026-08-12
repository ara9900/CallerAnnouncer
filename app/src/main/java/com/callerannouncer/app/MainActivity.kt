package com.callerannouncer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.callerannouncer.app.service.AnnouncerService
import com.callerannouncer.app.ui.navigation.AppNavHost
import com.callerannouncer.app.ui.theme.CallerAnnouncerTheme
import com.callerannouncer.app.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // UI refreshes on resume via DashboardViewModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (PermissionHelper.allGranted(this) && !AnnouncerService.isRunning) {
            AnnouncerService.start(this)
        }

        setContent {
            CallerAnnouncerTheme {
                AppNavHost(
                    onRequestPermissions = {
                        permissionLauncher.launch(PermissionHelper.requiredPermissions())
                    }
                )
            }
        }
    }
}
