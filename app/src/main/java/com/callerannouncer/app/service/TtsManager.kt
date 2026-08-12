package com.callerannouncer.app.service

import android.content.Context
import com.callerannouncer.app.service.tts.OfflinePersianTtsEngine
import com.callerannouncer.app.service.tts.PlaybackRoute
import com.callerannouncer.app.service.tts.TtsModelManager

/**
 * Facade for offline embedded Persian TTS (sherpa-onnx + Piper fa_IR).
 */
class TtsManager(context: Context) {

    private val engine = OfflinePersianTtsEngine(context.applicationContext)
    private var speechRate: Float = 1.0f

    fun initialize() = Unit

    fun setSpeechParams(rate: Float, @Suppress("UNUSED_PARAMETER") pitch: Float) {
        speechRate = rate.coerceIn(0.6f, 1.6f)
    }

    suspend fun warmUp(): Boolean = engine.warmUp()

    suspend fun speakAndAwait(
        text: String,
        repeatCount: Int = 1,
        route: PlaybackRoute = PlaybackRoute.MEDIA,
    ): Boolean = engine.speak(
        text = text,
        speed = speechRate,
        repeatCount = repeatCount,
        route = route,
    )

    fun stop() = engine.stop()

    fun shutdown() = engine.shutdown()

    companion object {
        suspend fun ensureModel(context: Context): Boolean =
            TtsModelManager.ensureModelReady(context.applicationContext)
    }
}
