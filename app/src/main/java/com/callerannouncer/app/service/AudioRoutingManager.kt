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
import com.callerannouncer.app.domain.model.PlayMode

/**
 * Handles audio focus and ringtone silencing so TTS can be heard during incoming calls.
 */
class AudioRoutingManager(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager =
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var savedRingVolume: Int? = null
    private var savedNotificationVolume: Int? = null
    private var savedRingerMode: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var incomingCallSessionActive = false
    private var savedSpeakerphoneOn: Boolean? = null
    private var forcedSpeakerphoneForCall = false
    private var ringSilenceKeepAlive: Runnable? = null

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
            .filter { it.type in headsetTypes }
            .minByOrNull { headsetDevicePriority(it.type) }
    }

    /** Built-in loudspeaker device, if exposed by the OEM. */
    fun findBuiltinSpeakerDevice(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }
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
     * Silence the ringtone while announcing. On sound mode the ring otherwise buries TTS;
     * OEMs (especially Samsung) often restore ring volume, so we keep re-applying mute.
     */
    fun beginIncomingCallAnnouncement() {
        if (incomingCallSessionActive) return
        incomingCallSessionActive = true

        acquireWakeLock()

        try {
            // Prevent Android from switching into an "in-call" audio mode
            // that commonly mutes media/TTS streams.
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set audio mode", e)
        }

        try {
            if (savedRingerMode == null) {
                savedRingerMode = audioManager.ringerMode
            }
            if (savedRingVolume == null) {
                savedRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            }
            if (savedNotificationVolume == null) {
                savedNotificationVolume =
                    audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            }
            silenceRingtoneForAnnouncement()
            forceSpeakerForCallAnnouncement()
            startRingSilenceKeepAlive()
            Log.i(
                TAG,
                "Silenced ringtone for announcement " +
                    "(modeWas=$savedRingerMode volWas=$savedRingVolume " +
                    "headset=${isHeadsetOrBluetoothConnected()})",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not silence ringtone", e)
        }

        // Navigation-guidance usage maps to media stream on many OEMs — keep it loud.
        boostAnnouncementStreamVolume()
        boostMediaVolumeForCallAnnouncement()

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

        stopRingSilenceKeepAlive()
        abandonAudioFocus()
        restoreRingtoneAfterAnnouncement()
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

    private fun silenceRingtoneForAnnouncement() {
        // Mute APIs + volume 0 stop the audible ring so TTS can be heard in sound mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_RING,
                    AudioManager.ADJUST_MUTE,
                    0,
                )
            } catch (e: Exception) {
                Log.w(TAG, "adjustStreamVolume MUTE RING failed", e)
            }
            try {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.ADJUST_MUTE,
                    0,
                )
            } catch (e: Exception) {
                Log.w(TAG, "adjustStreamVolume MUTE NOTIFICATION failed", e)
            }
        }
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "setStreamVolume RING/NOTIFICATION 0 failed", e)
        }
        // SILENT stops ringtone audio more reliably than VIBRATE on Samsung.
        try {
            if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch ringer to silent — trying vibrate", e)
            try {
                if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Could not switch ringer to vibrate", e2)
            }
        }
    }

    /** Route announcement to the loudspeaker — ignore BT so ring/TTS share the same output. */
    private fun forceSpeakerForCallAnnouncement() {
        try {
            if (savedSpeakerphoneOn == null) {
                savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }
            if (!audioManager.isSpeakerphoneOn) {
                audioManager.isSpeakerphoneOn = true
                forcedSpeakerphoneForCall = true
                Log.i(TAG, "Forced speakerphone ON for call announcement")
            } else {
                forcedSpeakerphoneForCall = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not force speakerphone", e)
        }
    }

    private fun restoreRingtoneAfterAnnouncement() {
        try {
            if (forcedSpeakerphoneForCall) {
                savedSpeakerphoneOn?.let { previous ->
                    audioManager.isSpeakerphoneOn = previous
                    Log.i(TAG, "Restored speakerphone=$previous after call announcement")
                }
                savedSpeakerphoneOn = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore speakerphone", e)
        } finally {
            forcedSpeakerphoneForCall = false
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_RING,
                    AudioManager.ADJUST_UNMUTE,
                    0,
                )
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.ADJUST_UNMUTE,
                    0,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "adjustStreamVolume UNMUTE failed", e)
        }
        try {
            savedRingerMode?.let { mode ->
                audioManager.ringerMode = mode
                Log.i(TAG, "Restored ringerMode=$mode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore ringer mode", e)
        } finally {
            savedRingerMode = null
        }
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
        try {
            savedNotificationVolume?.let { previous ->
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, previous, 0)
                Log.i(TAG, "Restored STREAM_NOTIFICATION to $previous")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore notification volume", e)
        } finally {
            savedNotificationVolume = null
        }
    }

    private fun startRingSilenceKeepAlive() {
        stopRingSilenceKeepAlive()
        val runnable = object : Runnable {
            override fun run() {
                if (!incomingCallSessionActive) return
                try {
                    if (audioManager.mode != AudioManager.MODE_NORMAL) {
                        audioManager.mode = AudioManager.MODE_NORMAL
                    }
                    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                        Log.i(TAG, "Re-applied RING mute (OEM restored volume)")
                    }
                    if (audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                    }
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        !audioManager.isStreamMute(AudioManager.STREAM_RING)
                    ) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_RING,
                            AudioManager.ADJUST_MUTE,
                            0,
                        )
                    }
                    if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    }
                    if (forcedSpeakerphoneForCall && !audioManager.isSpeakerphoneOn) {
                        audioManager.isSpeakerphoneOn = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ring silence keep-alive failed", e)
                }
                mainHandler.postDelayed(this, RING_SILENCE_INTERVAL_MS)
            }
        }
        ringSilenceKeepAlive = runnable
        mainHandler.postDelayed(runnable, RING_SILENCE_INTERVAL_MS)
    }

    private fun stopRingSilenceKeepAlive() {
        ringSilenceKeepAlive?.let { mainHandler.removeCallbacks(it) }
        ringSilenceKeepAlive = null
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

    private fun boostMediaVolumeForCallAnnouncement() {
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

    private fun requestExclusiveAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
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
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
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
        private const val RING_SILENCE_INTERVAL_MS = 250L

        /**
         * Best-effort ringtone mute callable from the phone-state receiver before the
         * service starts — critical on Samsung where the ring is already loud by then.
         */
        @JvmStatic
        fun silenceRingtoneNow(context: Context) {
            try {
                val audioManager =
                    context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_RING,
                            AudioManager.ADJUST_MUTE,
                            0,
                        )
                    } catch (_: Exception) {
                    }
                }
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                } catch (_: Exception) {
                }
                try {
                    if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    }
                } catch (_: Exception) {
                    try {
                        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                        }
                    } catch (_: Exception) {
                    }
                }
                try {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    audioManager.isSpeakerphoneOn = true
                } catch (_: Exception) {
                }
                Log.i(TAG, "silenceRingtoneNow applied (early)")
            } catch (e: Exception) {
                Log.w(TAG, "silenceRingtoneNow failed", e)
            }
        }
    }
}
