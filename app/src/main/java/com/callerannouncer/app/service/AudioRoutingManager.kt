package com.callerannouncer.app.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.callerannouncer.app.domain.model.PlayMode

/**
 * Handles audio focus. Avoids Bluetooth SCO for TTS media playback
 * (SCO is for call audio and often mutes media/TTS output).
 */
class AudioRoutingManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null

    fun shouldAnnounce(playMode: PlayMode): Boolean {
        return when (playMode) {
            PlayMode.ALWAYS -> true
            PlayMode.ONLY_HEADPHONES_BLUETOOTH -> isHeadsetOrBluetoothConnected()
            PlayMode.SILENT_IF_MUTED -> {
                val ringer = audioManager.ringerMode
                ringer != AudioManager.RINGER_MODE_SILENT &&
                    ringer != AudioManager.RINGER_MODE_VIBRATE
            }
        }
    }

    fun isHeadsetOrBluetoothConnected(): Boolean {
        val wired = audioManager.isWiredHeadsetOnSafe()
        val a2dp = audioManager.isBluetoothA2dpOn
        val sco = audioManager.isBluetoothScoOn || isBluetoothHeadsetConnected()
        return wired || a2dp || sco
    }

    fun requestFocusAndRoute(): Boolean {
        // Ensure media stream is not at zero for audible test/announce
        try {
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (max * 0.4f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.i(TAG, "STREAM_MUSIC was 0; raised to $target")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not adjust media volume", e)
        }

        // Keep mode normal so TTS uses media path (not call/SCO)
        try {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (_: Exception) {
        }

        return requestAudioFocus()
    }

    fun release() {
        abandonAudioFocus()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            val result = audioManager.requestAudioFocus(request)
            result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        return try {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            val adapter: BluetoothAdapter? = manager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) return false
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED ||
                adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission missing", e)
            false
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun AudioManager.isWiredHeadsetOnSafe(): Boolean = try {
        isWiredHeadsetOn
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "AudioRoutingManager"
    }
}
