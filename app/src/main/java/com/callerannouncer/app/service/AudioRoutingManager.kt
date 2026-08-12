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
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.callerannouncer.app.domain.model.PlayMode

/**
 * Handles audio focus and ringtone ducking so TTS can be heard during incoming calls.
 */
class AudioRoutingManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var focusRequest: AudioFocusRequest? = null
    private var savedRingVolume: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var incomingCallSessionActive = false

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

    /** Duck ringtone and take focus so caller name is audible over the ring. */
    fun beginIncomingCallAnnouncement() {
        if (incomingCallSessionActive) return
        incomingCallSessionActive = true

        acquireWakeLock()

        try {
            // Prevent Android from switching the device into an "in-call" audio mode
            // that commonly mutes media/TTS streams.
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }

            savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            // Duck ring, but keep it audible.
            val ducked = (maxRing * 0.25f).toInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, ducked, 0)
            Log.i(TAG, "Ducked STREAM_RING from $savedRingVolume to $ducked")
        } catch (e: Exception) {
            Log.w(TAG, "Could not duck ring volume", e)
        }

        // Ensure TTS (we route via USAGE_ALARM) is audible over the ringtone.
        boostAnnouncementStreamVolume()
        // Also make sure the media stream isn't at 0 (some OEMs mirror ring behavior).
        boostMediaVolumeIfSilent()

        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (_: Exception) {
        }

        requestExclusiveAudioFocus()
    }

    fun endIncomingCallAnnouncement() {
        if (!incomingCallSessionActive) return
        incomingCallSessionActive = false

        abandonAudioFocus()

        try {
            savedRingVolume?.let { previous ->
                audioManager.setStreamVolume(AudioManager.STREAM_RING, previous, 0)
                Log.i(TAG, "Restored STREAM_RING to $previous")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore ring volume", e)
        } finally {
            savedRingVolume = null
        }

        releaseWakeLock()
    }

    fun requestFocusAndRoute(): Boolean {
        boostMediaVolumeIfSilent()
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
        return requestTransientAudioFocus()
    }

    fun release() {
        endIncomingCallAnnouncement()
        abandonAudioFocus()
        releaseWakeLock()
    }

    private fun boostAnnouncementStreamVolume() {
        try {
            val stream = AudioManager.STREAM_ALARM
            val max = audioManager.getStreamMaxVolume(stream)
            // Some OEMs link ring/call routing with the alarm stream.
            // We set a strong minimum so the caller name is actually audible.
            val target = (max * 0.65f).toInt().coerceAtLeast(1)
            val current = audioManager.getStreamVolume(stream)
            if (current < target) {
                audioManager.setStreamVolume(stream, target, 0)
                Log.i(TAG, "Boosted STREAM_ALARM from $current to $target for call announcement")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost alarm stream", e)
        }
    }

    private fun boostMediaVolumeIfSilent() {
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
    }

    private fun requestExclusiveAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun requestTransientAudioFocus(): Boolean {
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
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
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

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallerAnnouncer::CallAnnouncement",
            ).apply {
                acquire(90_000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed", e)
        } finally {
            wakeLock = null
        }
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        return try {
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            val adapter: BluetoothAdapter? = manager?.adapter
                ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
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
