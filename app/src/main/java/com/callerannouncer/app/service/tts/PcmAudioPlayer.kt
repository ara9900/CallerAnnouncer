package com.callerannouncer.app.service.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Streams PCM float samples through [AudioTrack] and waits until playback finishes
 * before returning (stopping early causes only a brief click/noise).
 */
class PcmAudioPlayer(private val sampleRate: Int) {

    private var track: AudioTrack? = createTrack(sampleRate)
    @Volatile
    private var stopped = false

    fun beginSession() {
        stopped = false
        var audioTrack = track
        if (audioTrack == null || audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            audioTrack?.release()
            audioTrack = createTrack(sampleRate)
            track = audioTrack
        }
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
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
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        return AudioTrack(
            attrs,
            format,
            minBuf,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    companion object {
        private const val TAG = "PcmAudioPlayer"
    }
}
