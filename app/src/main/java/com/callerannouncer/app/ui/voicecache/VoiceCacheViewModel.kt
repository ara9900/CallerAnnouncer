package com.callerannouncer.app.ui.voicecache

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.callerannouncer.app.data.cache.TtsAudioCache
import com.callerannouncer.app.data.preferences.SettingsRepository
import com.callerannouncer.app.domain.model.TtsEngineMode
import com.callerannouncer.app.domain.model.VoiceCacheScope
import com.callerannouncer.app.service.VoiceCacheProgress
import com.callerannouncer.app.service.VoiceCacheService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceCacheViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val settingsRepository = SettingsRepository(application)

    val progress: StateFlow<VoiceCacheProgress.State> = VoiceCacheProgress.state

    val engineMode: StateFlow<TtsEngineMode> = settingsRepository.settingsFlow
        .map { it.ttsEngineMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsEngineMode.ONLINE_EDGE)

    private val _stats = MutableStateFlow(TtsAudioCache.Stats(0, 0L))
    val stats: StateFlow<TtsAudioCache.Stats> = _stats.asStateFlow()

    private val _scope = MutableStateFlow(VoiceCacheScope.FREQUENT)
    val scope: StateFlow<VoiceCacheScope> = _scope.asStateFlow()

    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            _stats.value = withContext(Dispatchers.IO) { TtsAudioCache.stats(app) }
        }
    }

    fun selectScope(value: VoiceCacheScope) {
        _scope.value = value
    }

    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
    }

    fun start() {
        VoiceCacheService.start(app, _scope.value, _wifiOnly.value)
    }

    fun cancel() {
        VoiceCacheService.cancel(app)
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { TtsAudioCache.clear(app) }
            refreshStats()
        }
    }
}
