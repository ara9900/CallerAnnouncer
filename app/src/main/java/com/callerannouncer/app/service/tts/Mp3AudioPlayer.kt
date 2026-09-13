package com.callerannouncer.app.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Plays MP3 bytes from online TTS with the same routing options as [PcmAudioPlayer]. */
class Mp3AudioPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var stopped = false

    fun resetCancellation() {
        stopped = false
    }

    fun isStopped(): Boolean = stopped

    /**
     * Plays the clip [repeatCount] times on a single player. Creating a player per repeat
     * cost seconds of prepare latency on Bluetooth outputs.
     */
    suspend fun play(
        mp3Data: ByteArray,
        route: PlaybackRoute,
        outputDevice: AudioDeviceInfo? = null,
        repeatCount: Int = 1,
        gapMs: Long = REPEAT_GAP_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        if (stopped || mp3Data.isEmpty()) return@withContext false
        val tempFile = File.createTempFile("edge_tts_", ".mp3", appContext.cacheDir)
        try {
            tempFile.writeBytes(mp3Data)
            suspendCancellableCoroutine { continuation ->
                val player = MediaPlayer()
                mediaPlayer = player
                val requestedAt = System.currentTimeMillis()
                var playsLeft = repeatCount.coerceIn(1, 5)
                var settled = false

                fun finish(success: Boolean) {
                    if (settled) return
                    settled = true
                    handler.removeCallbacksAndMessages(null)
                    try {
                        player.release()
                    } catch (_: Exception) {
                    }
                    if (mediaPlayer == player) mediaPlayer = null
                    if (continuation.isActive) continuation.resume(success)
                }

                try {
                    player.setAudioAttributes(attributesFor(route))
                    player.setDataSource(tempFile.absolutePath)
                    player.setOnPreparedListener {
                        Log.i(TAG, "prepared in ${System.currentTimeMillis() - requestedAt}ms")
                        if (stopped) {
                            finish(false)
                            return@setOnPreparedListener
                        }
                        // Pinning only sticks once the player owns an audio track,
                        // so it has to happen here and not before prepare.
                        pinOutput(player, outputDevice)
                        player.start()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            Log.i(TAG, "playing on device type=${player.routedDevice?.type}")
                        }
                    }
                    player.setOnCompletionListener {
                        playsLeft--
                        if (stopped || playsLeft <= 0) {
                            Log.i(
                                TAG,
                                "playback done in ${System.currentTimeMillis() - requestedAt}ms",
                            )
                            finish(!stopped)
                        } else {
                            handler.postDelayed({
                                if (stopped) {
                                    finish(false)
                                } else {
                                    try {
                                        player.seekTo(0)
                                        player.start()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Repeat restart failed", e)
                                        finish(false)
                                    }
                                }
                            }, gapMs)
                        }
                    }
                    player.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        finish(false)
                        true
                    }
                    player.prepareAsync()
                } catch (e: Exception) {
                    Log.w(TAG, "Playback setup failed", e)
                    finish(false)
                }

                continuation.invokeOnCancellation {
                    stopped = true
                    try {
                        player.stop()
                    } catch (_: Exception) {
                    }
                    finish(false)
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun pinOutput(player: MediaPlayer, outputDevice: AudioDeviceInfo?) {
        if (outputDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val routed = player.setPreferredDevice(outputDevice)
        Log.i(TAG, "Pinned to ${outputDevice.productName} routed=$routed")
    }

    private fun attributesFor(route: PlaybackRoute): AudioAttributes = when (route) {
        PlaybackRoute.MEDIA -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        PlaybackRoute.INCOMING_CALL -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()
        PlaybackRoute.HEADSET_CALL -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let { player ->
            try {
                player.stop()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "Mp3AudioPlayer"
        private const val REPEAT_GAP_MS = 220L
    }
}
