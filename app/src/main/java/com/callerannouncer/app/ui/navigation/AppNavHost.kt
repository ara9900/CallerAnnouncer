package com.callerannouncer.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.callerannouncer.app.ui.dashboard.DashboardScreen
import com.callerannouncer.app.ui.dashboard.DashboardViewModel
import com.callerannouncer.app.ui.settings.SettingsScreen
import com.callerannouncer.app.ui.settings.SettingsViewModel
import com.callerannouncer.app.ui.voicecache.VoiceCacheScreen
import com.callerannouncer.app.ui.voicecache.VoiceCacheViewModel

object Routes {
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val VOICE_CACHE = "voice_cache"
}

@Composable
fun AppNavHost(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD,
        modifier = modifier
    ) {
        composable(Routes.DASHBOARD) {
            val vm: DashboardViewModel = viewModel()
            DashboardScreen(
                viewModel = vm,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onRequestPermissions = onRequestPermissions
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenVoiceCache = { navController.navigate(Routes.VOICE_CACHE) }
            )
        }
        composable(Routes.VOICE_CACHE) {
            val vm: VoiceCacheViewModel = viewModel()
            VoiceCacheScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
