package com.callerannouncer.app.service.tts

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import com.callerannouncer.app.domain.model.OnlineEdgeVoice
import com.callerannouncer.app.service.tts.edge.EdgeTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Online Persian TTS using Microsoft Edge neural voices (Dilara / Farid). */
class OnlineEdgeTtsEngine(context: Context) {

    private val client = EdgeTtsClient()
    private val player = Mp3AudioPlayer(context.applicationContext)
    private var voice: OnlineEdgeVoice = OnlineEdgeVoice.DILARA
    private var speechRate: Float = 1.0f
    private var pitch: Float = 1.0f

    fun setVoice(selected: OnlineEdgeVoice) {
        voice = selected
    }

    fun setSpeechParams(rate: Float, pitchValue: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        pitch = pitchValue.coerceIn(0.5f, 2.0f)
    }

    suspend fun speak(
        text: String,
        repeatCount: Int,
        route: PlaybackRoute,
        outputDevice: AudioDeviceInfo? = null,
    ): Boolean {
        if (text.isBlank()) return false
        val times = repeatCount.coerceIn(1, 5)
        return withContext(Dispatchers.IO) {
            try {
                player.resetCancellation()
                repeat(times) { index ->
                    if (index > 0) {
                        Thread.sleep(250)
                        if (player.isStopped()) {
                            Log.i(TAG, "Online speak cancelled before repeat $index")
                            return@withContext false
                        }
                    }
                    val mp3 = client.synthesize(
                        text = text,
                        voice = voice.voiceId,
                        locale = voice.locale,
                        rate = speechRate,
                        pitch = pitch,
                    )
                    if (player.isStopped()) return@withContext false
                    val played = player.play(mp3, route, outputDevice)
                    if (!played || player.isStopped()) return@withContext false
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Online speak failed", e)
                false
            }
        }
    }

    fun stop() = player.stop()

    companion object {
        private const val TAG = "OnlineEdgeTts"
    }
}
