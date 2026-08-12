package com.callerannouncer.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callerannouncer.app.data.preferences.SettingsRepository
import com.callerannouncer.app.domain.model.UserSettings
import com.callerannouncer.app.service.AnnouncerService
import com.callerannouncer.app.service.tts.TtsModelManager
import com.callerannouncer.app.service.tts.TtsModelState
import com.callerannouncer.app.util.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val settings: UserSettings = UserSettings(),
    val serviceRunning: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _serviceRunning = MutableStateFlow(AnnouncerService.isRunning)
    private val _missingPermissions =
        MutableStateFlow(PermissionHelper.missingPermissions(application))

    val settings: StateFlow<UserSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()
    val missingPermissions: StateFlow<List<String>> = _missingPermissions.asStateFlow()
    val ttsModelState: StateFlow<TtsModelState> = TtsModelManager.state

    init {
        viewModelScope.launch {
            TtsModelManager.ensureModelReady(getApplication())
        }
    }

    fun refresh() {
        val app = getApplication<Application>()
        _serviceRunning.value = AnnouncerService.isRunning
        _missingPermissions.value = PermissionHelper.missingPermissions(app)
    }

    fun setCallEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setCallAnnouncerEnabled(enabled) }
    }

    fun setSmsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setSmsAnnouncerEnabled(enabled) }
    }

    fun startService() {
        AnnouncerService.start(getApplication())
        refresh()
    }

    fun stopService() {
        AnnouncerService.stop(getApplication())
        refresh()
    }

    fun testVoice() {
        AnnouncerService.testVoice(getApplication())
        refresh()
    }
}
