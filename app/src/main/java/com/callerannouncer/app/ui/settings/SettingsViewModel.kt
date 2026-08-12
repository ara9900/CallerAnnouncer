package com.callerannouncer.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callerannouncer.app.data.preferences.SettingsRepository
import com.callerannouncer.app.domain.model.OnlineEdgeVoice
import com.callerannouncer.app.domain.model.PlayMode
import com.callerannouncer.app.domain.model.TtsEngineMode
import com.callerannouncer.app.domain.model.UserSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    val settings: StateFlow<UserSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setReadSmsBody(enabled: Boolean) =
        viewModelScope.launch { repository.setReadSmsBody(enabled) }

    fun setRepeatCount(count: Int) =
        viewModelScope.launch { repository.setRepeatCount(count) }

    fun setPlayMode(mode: PlayMode) =
        viewModelScope.launch { repository.setPlayMode(mode) }

    fun setTtsEngineMode(mode: TtsEngineMode) =
        viewModelScope.launch { repository.setTtsEngineMode(mode) }

    fun setOnlineEdgeVoice(voice: OnlineEdgeVoice) =
        viewModelScope.launch { repository.setOnlineEdgeVoice(voice) }

    fun setCallPrefix(value: String) =
        viewModelScope.launch { repository.setCallPrefix(value) }

    fun setCallSuffix(value: String) =
        viewModelScope.launch { repository.setCallSuffix(value) }

    fun setSpeechRate(value: Float) =
        viewModelScope.launch { repository.setSpeechRate(value) }

    fun setPitch(value: Float) =
        viewModelScope.launch { repository.setPitch(value) }

    fun setSmsPrefix(value: String) =
        viewModelScope.launch { repository.setSmsPrefix(value) }
}
