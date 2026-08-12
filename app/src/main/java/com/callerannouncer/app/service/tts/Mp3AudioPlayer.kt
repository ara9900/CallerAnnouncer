package com.callerannouncer.app.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Plays MP3 bytes from online TTS with the same routing options as [PcmAudioPlayer]. */
class Mp3AudioPlayer(context: Context) {

    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var stopped = false

    fun resetCancellation() {
        stopped = false
    }

    fun isStopped(): Boolean = stopped

    suspend fun play(
        mp3Data: ByteArray,
        route: PlaybackRoute,
        outputDevice: AudioDeviceInfo? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (stopped || mp3Data.isEmpty()) return@withContext false
        val tempFile = File.createTempFile("edge_tts_", ".mp3", appContext.cacheDir)
        try {
            tempFile.writeBytes(mp3Data)
            suspendCancellableCoroutine { continuation ->
                val player = MediaPlayer()
                mediaPlayer = player
                try {
                    val attrs = when (route) {
                        PlaybackRoute.MEDIA -> AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        PlaybackRoute.INCOMING_CALL -> AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    }
                    player.setAudioAttributes(attrs)
                    if (outputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        player.setPreferredDevice(outputDevice)
                    }
                    player.setDataSource(tempFile.absolutePath)
                    player.setOnPreparedListener {
                        if (stopped) {
                            player.release()
                            continuation.resume(false)
                        } else {
                            player.start()
                        }
                    }
                    player.setOnCompletionListener {
                        player.release()
                        if (mediaPlayer == player) mediaPlayer = null
                        if (continuation.isActive) continuation.resume(!stopped)
                    }
                    player.setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                        player.release()
                        if (mediaPlayer == player) mediaPlayer = null
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("MediaPlayer error $what/$extra"),
                            )
                        }
                        true
                    }
                    player.prepareAsync()
                } catch (e: Exception) {
                    player.release()
                    if (mediaPlayer == player) mediaPlayer = null
                    continuation.resumeWithException(e)
                }
                continuation.invokeOnCancellation {
                    stopped = true
                    try {
                        player.stop()
                    } catch (_: Exception) {
                    }
                    player.release()
                    if (mediaPlayer == player) mediaPlayer = null
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    fun stop() {
        stopped = true
        mediaPlayer?.let { player ->
            try {
                player.stop()
            } catch (_: Exception) {
            }
            player.release()
        }
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "Mp3AudioPlayer"
    }
}
