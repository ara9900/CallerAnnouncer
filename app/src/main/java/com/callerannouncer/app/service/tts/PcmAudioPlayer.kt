package com.callerannouncer.app.service.tts

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

/**
 * Streams PCM float samples through [AudioTrack] and waits until playback finishes
 * before returning (stopping early causes only a brief click/noise).
 */
class PcmAudioPlayer(private val sampleRate: Int) {

    private var track: AudioTrack? = null
    private var activeRoute: PlaybackRoute? = null
    private var activeOutputDevice: AudioDeviceInfo? = null
    @Volatile
    private var stopped = false

    /** Clears a prior [stop] so a new [speak] session can start. */
    fun resetCancellation() {
        stopped = false
    }

    fun beginSession(route: PlaybackRoute, outputDevice: AudioDeviceInfo? = null) {
        stopped = false
        val routeChanged = activeRoute != route
        val deviceChanged = activeOutputDevice?.id != outputDevice?.id
        if (
            track == null ||
            routeChanged ||
            deviceChanged ||
            track?.state != AudioTrack.STATE_INITIALIZED
        ) {
            track?.release()
            track = createTrack(sampleRate, route, outputDevice)
            activeRoute = route
            activeOutputDevice = outputDevice
        } else if (outputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val routed = track?.setPreferredDevice(outputDevice) == true
            Log.i(TAG, "setPreferredDevice ${outputDevice.productName} result=$routed")
        }
        val audioTrack = track
        if (audioTrack == null || audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized for route=$route")
            return
        }
        audioTrack.pause()
        audioTrack.flush()
        audioTrack.play()
    }

    fun writeSamples(samples: FloatArray): Boolean {
        if (stopped || samples.isEmpty()) return false
        val audioTrack = track ?: return false
        var offset = 0
        while (offset < samples.size && !stopped) {
            val written = audioTrack.write(
                samples,
                offset,
                samples.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) {
                Log.e(TAG, "AudioTrack.write returned $written")
                return false
            }
            offset += written
        }
        return true
    }

    /** Blocks until buffered audio has been played or [stop] is called. */
    fun awaitPlayback(totalSamples: Int) {
        val audioTrack = track ?: return
        if (stopped || totalSamples <= 0) return

        val expectedMs = (totalSamples.toLong() * 1000L / sampleRate) + 500L
        val deadline = System.currentTimeMillis() + expectedMs.coerceAtMost(120_000L)
        while (System.currentTimeMillis() < deadline && !stopped) {
            val played = audioTrack.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (played >= totalSamples) break
            Thread.sleep(10)
        }
    }

    fun endSession() {
        track?.pause()
        track?.flush()
    }

    fun isStopped(): Boolean = stopped

    fun stop() {
        stopped = true
        track?.pause()
        track?.flush()
    }

    fun release() {
        stop()
        track?.release()
        track = null
        activeRoute = null
        activeOutputDevice = null
    }

    private fun createTrack(
        sampleRate: Int,
        route: PlaybackRoute,
        outputDevice: AudioDeviceInfo?,
    ): AudioTrack {
        val bufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        require(bufferBytes > 0) {
            "AudioTrack.getMinBufferSize failed ($bufferBytes) for rate=$sampleRate"
        }

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

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        val audioTrack = AudioTrack(
            attrs,
            format,
            bufferBytes,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (outputDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val routed = audioTrack.setPreferredDevice(outputDevice)
            Log.i(TAG, "Pinned output to ${outputDevice.productName} (type=${outputDevice.type}) routed=$routed")
        }
        return audioTrack
    }

    companion object {
        private const val TAG = "PcmAudioPlayer"
    }
}
