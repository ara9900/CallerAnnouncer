package com.callerannouncer.app.service.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

class PcmAudioPlayer(sampleRate: Int) {

    private var track: AudioTrack? = createTrack(sampleRate)
    @Volatile
    private var stopped = false

    fun playBlocking(samples: FloatArray) {
        stopped = false
        val audioTrack = track ?: return
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized")
            return
        }
        audioTrack.play()
        var offset = 0
        while (offset < samples.size && !stopped) {
            val written = audioTrack.write(
                samples,
                offset,
                samples.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) break
            offset += written
        }
        audioTrack.stop()
        audioTrack.flush()
    }

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
        ).coerceAtLeast(sampleRate / 2)

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
