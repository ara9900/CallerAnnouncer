package com.callerannouncer.app

import android.app.Application
import com.callerannouncer.app.service.tts.TtsModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallerAnnouncerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            TtsModelManager.ensureModelReady(this@CallerAnnouncerApp)
        }
    }
}
