package com.callerannouncer.app.service.tts

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import com.callerannouncer.app.data.cache.TtsAudioCache
import com.callerannouncer.app.domain.model.OnlineEdgeVoice
import com.callerannouncer.app.service.tts.edge.EdgeTtsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Online Persian TTS using Microsoft Edge neural voices (Dilara / Farid). */
class OnlineEdgeTtsEngine(context: Context) {

    private val appContext = context.applicationContext
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
                val startedAt = System.currentTimeMillis()
                player.resetCancellation()
                // Rendered once per announcement: repeats replay the same audio instead of
                // waiting seconds for another network round trip.
                val mp3 = audioFor(text)
                if (mp3.isEmpty() || player.isStopped()) return@withContext false
                Log.i(TAG, "audio ready in ${System.currentTimeMillis() - startedAt}ms")
                repeat(times) { index ->
                    if (index > 0) {
                        Thread.sleep(REPEAT_GAP_MS)
                        if (player.isStopped()) {
                            Log.i(TAG, "Online speak cancelled before repeat $index")
                            return@withContext false
                        }
                    }
                    val played = player.play(mp3, route, outputDevice)
                    if (!played || player.isStopped()) {
                        Log.i(TAG, "Online repeat ${index + 1}/$times stopped (played=$played)")
                        return@withContext false
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Online speak failed", e)
                false
            }
        }
    }

    /** Renders [text] into the cache without playing it — used by contact pre-caching. */
    suspend fun prepare(text: String): Boolean {
        if (text.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                audioFor(text).isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Prepare failed for \"$text\": ${e.message}")
                false
            }
        }
    }

    fun isCached(text: String): Boolean =
        TtsAudioCache.contains(appContext, cacheKey(text))

    private suspend fun audioFor(text: String): ByteArray {
        val key = cacheKey(text)
        TtsAudioCache.get(appContext, key)?.let { cached ->
            Log.i(TAG, "Cache hit (${cached.size} bytes) for \"$text\"")
            return cached
        }
        val startedAt = System.currentTimeMillis()
        val mp3 = client.synthesize(
            text = text,
            voice = voice.voiceId,
            locale = voice.locale,
            rate = speechRate,
            pitch = pitch,
        )
        Log.i(TAG, "Synthesized ${mp3.size} bytes in ${System.currentTimeMillis() - startedAt}ms")
        if (mp3.isNotEmpty()) {
            TtsAudioCache.put(appContext, key, mp3)
        }
        return mp3
    }

    private fun cacheKey(text: String): String =
        TtsAudioCache.keyOf(voice.voiceId, text, speechRate, pitch)

    fun stop() = player.stop()

    companion object {
        private const val TAG = "OnlineEdgeTts"
        private const val REPEAT_GAP_MS = 250L
    }
}
