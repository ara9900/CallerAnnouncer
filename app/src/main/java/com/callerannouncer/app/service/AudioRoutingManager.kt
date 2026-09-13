package com.callerannouncer.app.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.callerannouncer.app.data.preferences.AnnouncePolicyCache
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var incomingCallSessionActive = false
    private var savedSpeakerphoneOn: Boolean? = null
    private var ringDuckKeepAlive: Runnable? = null

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

    /** Returns the connected wired/BT output device, if any. */
    fun findHeadsetOutputDevice(): AudioDeviceInfo? {
        val headsetTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in headsetTypes && isUsableOutput(it) }
            .minByOrNull { headsetDevicePriority(it.type) }
    }

    /**
     * A paired laptop or car kit shows up as a Bluetooth SCO output even when nothing is
     * listening on it. Pinning playback there silently fails and the platform falls back
     * to the loudspeaker, so only trust SCO while a call-audio link is actually up.
     */
    private fun isUsableOutput(device: AudioDeviceInfo): Boolean {
        if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        val scoActive = try {
            audioManager.isBluetoothScoOn
        } catch (_: Exception) {
            false
        }
        if (!scoActive) {
            Log.i(TAG, "Ignoring idle SCO output ${device.productName}")
        }
        return scoActive
    }

    /**
     * Pins playback to the connected headset/Bluetooth device and disables speakerphone
     * so TTS is not duplicated on the phone speaker.
     */
    fun beginExclusiveHeadsetOutput(): AudioDeviceInfo? {
        val device = findHeadsetOutputDevice() ?: return null
        try {
            if (savedSpeakerphoneOn == null) {
                savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }
            if (audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = false
                Log.i(TAG, "Disabled speakerphone for headset-only playback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not disable speakerphone", e)
        }
        Log.i(TAG, "Routing audio exclusively to ${device.productName} (type=${device.type})")
        return device
    }

    fun endExclusiveHeadsetOutput() {
        try {
            savedSpeakerphoneOn?.let { previous ->
                audioManager.isSpeakerphoneOn = previous
                Log.i(TAG, "Restored speakerphone=$previous")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore speakerphone", e)
        } finally {
            savedSpeakerphoneOn = null
        }
    }

    /**
     * Duck the ringtone and take focus so the caller name can be heard over the ring.
     * Ringer mode, audio mode and speakerphone are left untouched: overriding them on
     * One UI routes our TTS into a muted path while the ringtone keeps the speaker.
     */
    fun beginIncomingCallAnnouncement(useHeadset: Boolean) {
        if (incomingCallSessionActive) return
        incomingCallSessionActive = true

        acquireWakeLock()
        duckRingtone(appContext)
        startRingDuckKeepAlive()
        if (useHeadset) {
            boostMediaVolumeForAnnouncement()
        } else {
            boostAnnouncementStreamVolume()
        }
        logAudioState("begin")
        // Exclusive focus in both cases: a duckable request lets the ringtone attenuate
        // the announcement into silence on One UI.
        val granted = requestExclusiveAudioFocus(useHeadset)
        Log.i(TAG, "Announcement focus granted=$granted useHeadset=$useHeadset")
    }

    fun endIncomingCallAnnouncement() {
        if (!incomingCallSessionActive) return
        incomingCallSessionActive = false

        stopRingDuckKeepAlive()
        abandonAudioFocus()
        restoreRingtone(appContext)
        releaseWakeLock()
        logAudioState("end")
    }

    fun requestFocusAndRoute(): Boolean {
        boostMediaVolumeIfSilent()
        return requestTransientAudioFocus()
    }

    fun release() {
        endIncomingCallAnnouncement()
        abandonAudioFocus()
        releaseWakeLock()
    }

    private fun logAudioState(phase: String) {
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(",") { "${it.type}" }
            Log.i(
                TAG,
                "audioState[$phase] mode=${audioManager.mode} ringer=${audioManager.ringerMode} " +
                    "ring=${audioManager.getStreamVolume(AudioManager.STREAM_RING)}/" +
                    "${audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)} " +
                    "music=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)} " +
                    "alarm=${audioManager.getStreamVolume(AudioManager.STREAM_ALARM)} " +
                    "speakerphone=${audioManager.isSpeakerphoneOn} " +
                    "musicActive=${audioManager.isMusicActive} outputs=$outputs",
            )
        } catch (e: Exception) {
            Log.w(TAG, "logAudioState failed", e)
        }
    }

    private fun startRingDuckKeepAlive() {
        stopRingDuckKeepAlive()
        val runnable = object : Runnable {
            override fun run() {
                if (!incomingCallSessionActive) return
                try {
                    val target = duckTarget(audioManager)
                    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > target) {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
                        Log.i(TAG, "Re-applied ring duck (OEM restored volume)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ring duck keep-alive failed", e)
                }
                mainHandler.postDelayed(this, RING_DUCK_INTERVAL_MS)
            }
        }
        ringDuckKeepAlive = runnable
        mainHandler.postDelayed(runnable, RING_DUCK_INTERVAL_MS)
    }

    private fun stopRingDuckKeepAlive() {
        ringDuckKeepAlive?.let { mainHandler.removeCallbacks(it) }
        ringDuckKeepAlive = null
    }

    private fun boostAnnouncementStreamVolume() {
        try {
            val stream = AudioManager.STREAM_ALARM
            val max = audioManager.getStreamMaxVolume(stream)
            val target = (max * 0.9f).toInt().coerceAtLeast(1)
            val current = audioManager.getStreamVolume(stream)
            if (current < target) {
                audioManager.setStreamVolume(stream, target, 0)
                Log.i(TAG, "Boosted STREAM_ALARM from $current to $target for call announcement")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost alarm stream", e)
        }
    }

    /** Headset announcements ride the media stream, so it must be loud enough to hear. */
    private fun boostMediaVolumeForAnnouncement() {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * 0.85f).toInt().coerceAtLeast(1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current < target) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.i(TAG, "Boosted STREAM_MUSIC from $current to $target for call announcement")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost media volume", e)
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

    private fun requestExclusiveAudioFocus(useHeadset: Boolean): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .apply {
                    if (useHeadset) {
                        setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    } else {
                        setUsage(AudioAttributes.USAGE_ALARM)
                        setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    }
                }
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
                if (useHeadset) AudioManager.STREAM_MUSIC else AudioManager.STREAM_ALARM,
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

    private fun headsetDevicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET -> 2
        AudioDeviceInfo.TYPE_USB_DEVICE -> 3
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 4
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 5
        AudioDeviceInfo.TYPE_HEARING_AID -> 6
        else -> 99
    }

    companion object {
        private const val TAG = "AudioRoutingManager"
        private const val RING_DUCK_INTERVAL_MS = 250L
        private const val PREFS_NAME = "audio_routing_state"
        private const val KEY_PRE_DUCK_RING_VOLUME = "pre_duck_ring_volume"

        private fun duckTarget(audioManager: AudioManager): Int {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            return (max * 0.12f).toInt().coerceAtLeast(0)
        }

        /**
         * Lower ring volume only. The pre-duck level is persisted because the phone-state
         * receiver ducks before the service starts, and the restore may run in another process.
         */
        @JvmStatic
        fun duckRingtone(context: Context) {
            val appContext = context.applicationContext
            try {
                val audioManager =
                    appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val target = duckTarget(audioManager)
                val current = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                if (!prefs.contains(KEY_PRE_DUCK_RING_VOLUME) && current > target) {
                    prefs.edit().putInt(KEY_PRE_DUCK_RING_VOLUME, current).apply()
                }
                audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
                Log.i(TAG, "Ducked ring $current -> $target (saved=${prefs.getInt(KEY_PRE_DUCK_RING_VOLUME, -1)})")
            } catch (e: Exception) {
                Log.w(TAG, "duckRingtone failed", e)
            }
        }

        /**
         * Duck only when this call will actually be announced. In headphones-only mode
         * without a headset the phone must ring at its normal volume.
         */
        @JvmStatic
        fun duckRingtoneIfAnnouncing(context: Context) {
            val appContext = context.applicationContext
            val policy = AnnouncePolicyCache.read(appContext)
            if (!policy.callEnabled) {
                Log.i(TAG, "Not ducking ring — call announcer disabled")
                return
            }
            if (!AudioRoutingManager(appContext).shouldAnnounce(policy.playMode)) {
                Log.i(TAG, "Not ducking ring — playMode=${policy.playMode} blocks announcement")
                return
            }
            duckRingtone(appContext)
        }

        /** Restore the ring volume captured before the first duck of this call. */
        @JvmStatic
        fun restoreRingtone(context: Context) {
            val appContext = context.applicationContext
            try {
                val audioManager =
                    appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val saved = prefs.getInt(KEY_PRE_DUCK_RING_VOLUME, -1)
                if (saved >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, saved, 0)
                    prefs.edit().remove(KEY_PRE_DUCK_RING_VOLUME).apply()
                    Log.i(TAG, "Restored ring volume to $saved")
                }
            } catch (e: Exception) {
                Log.w(TAG, "restoreRingtone failed", e)
            }
        }
    }
}
