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
                // One network round trip per announcement — repeats replay the same audio,
                // otherwise every repeat waited seconds for a fresh synthesis.
                val mp3 = synthesizeCached(text)
                if (mp3.isEmpty() || player.isStopped()) return@withContext false
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

    /** Repeat callers from the same contact skip the network entirely. */
    private suspend fun synthesizeCached(text: String): ByteArray {
        val key = "$text|${voice.voiceId}|$speechRate|$pitch"
        synchronized(cache) { cache[key] }?.let {
            Log.i(TAG, "Reusing cached synthesis (${it.size} bytes)")
            return it
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
            synchronized(cache) {
                cache[key] = mp3
                while (cache.size > MAX_CACHE_ENTRIES) {
                    val eldest = cache.keys.firstOrNull() ?: break
                    cache.remove(eldest)
                }
            }
        }
        return mp3
    }

    fun stop() = player.stop()

    companion object {
        private const val TAG = "OnlineEdgeTts"
        private const val REPEAT_GAP_MS = 250L
        private const val MAX_CACHE_ENTRIES = 12

        /** Access-ordered so the eldest entry is the least recently used one. */
        private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    }
}
