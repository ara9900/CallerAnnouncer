package com.callerannouncer.app.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import com.callerannouncer.app.domain.model.OnlineEdgeVoice
import com.callerannouncer.app.domain.model.TtsEngineMode
import com.callerannouncer.app.service.tts.OfflinePersianTtsEngine
import com.callerannouncer.app.service.tts.OnlineEdgeTtsEngine
import com.callerannouncer.app.service.tts.PlaybackRoute
import com.callerannouncer.app.service.tts.TtsModelManager

/**
 * Routes speech to offline (sherpa-onnx) or online (Microsoft Edge neural) engines.
 * Online mode falls back to offline if the network TTS fails.
 */
class TtsManager(context: Context) {

    private val appContext = context.applicationContext
    private val offlineEngine = OfflinePersianTtsEngine(appContext)
    private val onlineEngine = OnlineEdgeTtsEngine(appContext)
    private var engineMode: TtsEngineMode = TtsEngineMode.OFFLINE
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    fun initialize() = Unit

    fun configure(mode: TtsEngineMode, onlineVoice: OnlineEdgeVoice) {
        engineMode = mode
        onlineEngine.setVoice(onlineVoice)
    }

    fun setSpeechParams(rate: Float, pitchValue: Float) {
        speechRate = rate.coerceIn(0.6f, 1.6f)
        pitch = pitchValue.coerceIn(0.5f, 2.0f)
        onlineEngine.setSpeechParams(rate, pitchValue)
    }

    suspend fun warmUp(): Boolean = when (engineMode) {
        TtsEngineMode.OFFLINE -> offlineEngine.warmUp()
        TtsEngineMode.ONLINE_EDGE -> true
    }

    suspend fun speakAndAwait(
        text: String,
        repeatCount: Int = 1,
        route: PlaybackRoute = PlaybackRoute.MEDIA,
        outputDevice: AudioDeviceInfo? = null,
    ): Boolean = when (engineMode) {
        TtsEngineMode.OFFLINE -> offlineEngine.speak(
            text = text,
            speed = speechRate,
            repeatCount = repeatCount,
            route = route,
            outputDevice = outputDevice,
        )
        TtsEngineMode.ONLINE_EDGE -> {
            val onlineOk = onlineEngine.speak(
                text = text,
                repeatCount = repeatCount,
                route = route,
                outputDevice = outputDevice,
            )
            if (onlineOk) {
                true
            } else {
                Log.w(TAG, "Online TTS failed — falling back to offline")
                if (!TtsModelManager.ensureModelReady(appContext)) {
                    false
                } else {
                    offlineEngine.speak(
                        text = text,
                        speed = speechRate,
                        repeatCount = repeatCount,
                        route = route,
                        outputDevice = outputDevice,
                    )
                }
            }
        }
    }

    fun stop() {
        offlineEngine.stop()
        onlineEngine.stop()
    }

    fun shutdown() {
        offlineEngine.shutdown()
        onlineEngine.stop()
    }

    companion object {
        private const val TAG = "TtsManager"

        suspend fun ensureOfflineModel(context: Context): Boolean =
            TtsModelManager.ensureModelReady(context.applicationContext)
    }
}
