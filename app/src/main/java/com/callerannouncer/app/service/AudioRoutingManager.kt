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
    private var savedAudioMode: Int? = null
    private var communicationDeviceSet = false
    private var unlockHeadsetMedia = false
    private var scoStarted = false
    private var ringDuckKeepAlive: Runnable? = null

    fun shouldAnnounce(playMode: PlayMode): Boolean {
        return when (playMode) {
            PlayMode.ALWAYS -> true
            // Require a real usable output device, not just a paired BT profile — otherwise
            // we pass the gate and later fall back to the loudspeaker.
            PlayMode.ONLY_HEADPHONES_BLUETOOTH -> findHeadsetOutputDevice() != null
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
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
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
     * When playing on a headset, also force MODE_NORMAL: during MODE_RINGTONE Samsung
     * suspends A2DP so MediaPlayer reports success with routedDevice=null and silence.
     */
    fun beginIncomingCallAnnouncement(useHeadset: Boolean, duckRing: Boolean = true) {
        if (incomingCallSessionActive) return
        incomingCallSessionActive = true

        acquireWakeLock()
        if (duckRing) {
            duckRingtone(appContext)
            startRingDuckKeepAlive(forceSilent = false)
        }
        if (useHeadset) {
            unlockHeadsetMediaPath()
            boostMediaVolumeForAnnouncement()
        } else {
            boostAnnouncementStreamVolume()
        }
        logAudioState("begin")
        val granted = requestExclusiveAudioFocus(useHeadset)
        Log.i(
            TAG,
            "Announcement focus granted=$granted useHeadset=$useHeadset duckRing=$duckRing",
        )
    }

    fun endIncomingCallAnnouncement() {
        if (!incomingCallSessionActive) return
        incomingCallSessionActive = false

        stopRingDuckKeepAlive()
        abandonAudioFocus()
        restoreHeadsetMediaPath()
        restoreRingtone(appContext)
        releaseWakeLock()
        logAudioState("end")
    }

    /**
     * Telecom holds MODE_RINGTONE while the phone rings, which parks classic A2DP media.
     * Open the HFP/SCO (or BLE communication) path that still works during ringing.
     */
    private fun unlockHeadsetMediaPath() {
        try {
            if (savedAudioMode == null) {
                savedAudioMode = audioManager.mode
            }
            unlockHeadsetMedia = true
            // IN_COMMUNICATION is the mode SCO/HFP expects; NORMAL keeps getting overwritten
            // by Telecom and leaves A2DP parked.
            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.i(TAG, "Forced MODE_IN_COMMUNICATION (was $savedAudioMode) for headset call audio")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val communicationDevice = findCommunicationHeadset()
                if (communicationDevice != null) {
                    val ok = audioManager.setCommunicationDevice(communicationDevice)
                    communicationDeviceSet = ok
                    Log.i(
                        TAG,
                        "setCommunicationDevice ${communicationDevice.productName} " +
                            "ok=$ok type=${communicationDevice.type}",
                    )
                } else {
                    Log.i(TAG, "No valid communication headset device available yet")
                }
            }
            if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                scoStarted = true
                Log.i(TAG, "Started Bluetooth SCO for headset announcement")
            }
            boostVoiceCallVolume()
        } catch (e: Exception) {
            Log.w(TAG, "unlockHeadsetMediaPath failed", e)
        }
    }

    /**
     * Device to pin voice-communication playback to after SCO/BLE is up.
     * Never returns classic A2DP — that path is silent during ringtone.
     */
    fun findCallHeadsetDevice(): AudioDeviceInfo? {
        findCommunicationHeadset()?.let { return it }
        val callTypes = setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in callTypes }
            .filter { device ->
                if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return@filter true
                try {
                    audioManager.isBluetoothScoOn
                } catch (_: Exception) {
                    false
                }
            }
            .minByOrNull { communicationDevicePriority(it.type) }
    }

    /** Poll until SCO/BLE is usable, or [timeoutMs] elapses. */
    fun awaitCallHeadsetDevice(timeoutMs: Long = 1800L): AudioDeviceInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Retry communication-device selection as SCO comes up.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !communicationDeviceSet) {
                try {
                    val device = findCommunicationHeadset()
                    if (device != null) {
                        val ok = audioManager.setCommunicationDevice(device)
                        communicationDeviceSet = ok
                        Log.i(
                            TAG,
                            "setCommunicationDevice (retry) ${device.productName} ok=$ok type=${device.type}",
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setCommunicationDevice retry failed", e)
                }
            }
            findCallHeadsetDevice()?.let { found ->
                Log.i(TAG, "Call headset ready ${found.productName} type=${found.type}")
                return found
            }
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                break
            }
        }
        val fallback = findCallHeadsetDevice()
        Log.i(
            TAG,
            "Call headset wait done scoOn=${try {
                audioManager.isBluetoothScoOn
            } catch (_: Exception) {
                false
            }} device=${fallback?.productName} type=${fallback?.type}",
        )
        return fallback
    }

    private fun boostVoiceCallVolume() {
        try {
            val stream = AudioManager.STREAM_VOICE_CALL
            val max = audioManager.getStreamMaxVolume(stream)
            val target = (max * 0.9f).toInt().coerceAtLeast(1)
            val current = audioManager.getStreamVolume(stream)
            if (current < target) {
                audioManager.setStreamVolume(stream, target, 0)
                Log.i(TAG, "Boosted STREAM_VOICE_CALL from $current to $target")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost voice call volume", e)
        }
    }

    /** A2DP (type 8) is rejected by setCommunicationDevice — use BLE/SCO/wired only. */
    private fun findCommunicationHeadset(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val preferred = setOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
        return audioManager.availableCommunicationDevices
            .filter { it.type in preferred }
            .minByOrNull { communicationDevicePriority(it.type) }
    }

    private fun communicationDevicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET -> 2
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 3
        AudioDeviceInfo.TYPE_HEARING_AID -> 4
        else -> 99
    }

    private fun restoreHeadsetMediaPath() {
        try {
            if (scoStarted) {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                Log.i(TAG, "Stopped Bluetooth SCO")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopBluetoothSco failed", e)
        } finally {
            scoStarted = false
        }
        try {
            if (communicationDeviceSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "Cleared communication device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearCommunicationDevice failed", e)
        } finally {
            communicationDeviceSet = false
        }
        try {
            savedAudioMode?.let { previous ->
                audioManager.mode = previous
                Log.i(TAG, "Restored audio mode=$previous")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore audio mode failed", e)
        } finally {
            savedAudioMode = null
            unlockHeadsetMedia = false
        }
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

    private fun startRingDuckKeepAlive(forceSilent: Boolean) {
        stopRingDuckKeepAlive()
        val runnable = object : Runnable {
            override fun run() {
                if (!incomingCallSessionActive) return
                try {
                    val target = if (forceSilent) 0 else duckTarget(audioManager)
                    if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > target) {
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
                        Log.i(TAG, "Re-applied ring duck target=$target (OEM restored volume)")
                    }
                    if (unlockHeadsetMedia &&
                        audioManager.mode != AudioManager.MODE_IN_COMMUNICATION
                    ) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        Log.i(TAG, "Re-applied MODE_IN_COMMUNICATION (OEM restored mode)")
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
                        setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                if (useHeadset) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_ALARM,
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
        // Buds3 Pro (and most modern buds) ring/play over LE Audio — prefer it over
        // classic A2DP which is suspended while Telecom holds MODE_RINGTONE.
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        AudioDeviceInfo.TYPE_USB_DEVICE -> 4
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> 5
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 6
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 7
        AudioDeviceInfo.TYPE_HEARING_AID -> 8
        else -> 99
    }

    companion object {
        private const val TAG = "AudioRoutingManager"
        private const val RING_DUCK_INTERVAL_MS = 250L
        private const val PREFS_NAME = "audio_routing_state"
        private const val KEY_PRE_DUCK_RING_VOLUME = "pre_duck_ring_volume"
        private const val KEY_PRE_DUCK_RINGER_MODE = "pre_duck_ringer_mode"
        private const val KEY_DUCK_ACTIVE = "ring_duck_active"

        private val restoreHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var restoreRunnable: Runnable? = null

        private fun duckTarget(audioManager: AudioManager): Int {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            // Keep at least 1 so One UI does not flip into vibrate/silent.
            return (max * 0.12f).toInt().coerceAtLeast(1)
        }

        /** Lower ring volume only. Saves the exact pre-duck level (even if the user set it low). */
        @JvmStatic
        fun duckRingtone(context: Context) {
            val appContext = context.applicationContext
            try {
                val audioManager =
                    appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val target = duckTarget(audioManager)
                val current = audioManager.getStreamVolume(AudioManager.STREAM_RING)

                // Snapshot once per duck session — exact value, never coerce upward later.
                if (!prefs.getBoolean(KEY_DUCK_ACTIVE, false) && current > target) {
                    prefs.edit()
                        .putInt(KEY_PRE_DUCK_RING_VOLUME, current)
                        .putInt(KEY_PRE_DUCK_RINGER_MODE, audioManager.ringerMode)
                        .putBoolean(KEY_DUCK_ACTIVE, true)
                        .commit()
                }

                if (current > target) {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
                }
                Log.i(
                    TAG,
                    "Ducked ring $current -> ${audioManager.getStreamVolume(AudioManager.STREAM_RING)} " +
                        "(saved=${prefs.getInt(KEY_PRE_DUCK_RING_VOLUME, -1)} active=${prefs.getBoolean(KEY_DUCK_ACTIVE, false)})",
                )
            } catch (e: Exception) {
                Log.w(TAG, "duckRingtone failed", e)
            }
        }

        /**
         * Duck when a call announcement will fight the ringtone for the Bluetooth link.
         * Headphones-only without a headset must leave the ringtone completely alone.
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

        /** Restore only if we currently own an active duck session. */
        @JvmStatic
        fun restoreRingtoneIfDucked(context: Context) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_DUCK_ACTIVE, false)) {
                Log.i(TAG, "No active ring duck — skip restore")
                return
            }
            restoreRingtone(context)
        }

        /**
         * Restore the exact ring volume/mode captured before the first duck of this call.
         * Cancels any previous re-apply loop so concurrent restores do not fight.
         */
        @JvmStatic
        fun restoreRingtone(context: Context) {
            val appContext = context.applicationContext
            try {
                cancelRestoreLoop()
                val audioManager =
                    appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_DUCK_ACTIVE, false) &&
                    !prefs.contains(KEY_PRE_DUCK_RING_VOLUME)
                ) {
                    Log.i(TAG, "restoreRingtone: nothing to restore")
                    return
                }

                val saved = prefs.getInt(KEY_PRE_DUCK_RING_VOLUME, -1)
                val savedMode = prefs.getInt(
                    KEY_PRE_DUCK_RINGER_MODE,
                    AudioManager.RINGER_MODE_NORMAL,
                )
                if (saved < 0) {
                    prefs.edit().putBoolean(KEY_DUCK_ACTIVE, false).commit()
                    return
                }

                applyRingRestore(audioManager, saved, savedMode, attempt = 0)

                var attempts = 0
                val reapply = object : Runnable {
                    override fun run() {
                        attempts++
                        val current = try {
                            audioManager.getStreamVolume(AudioManager.STREAM_RING)
                        } catch (_: Exception) {
                            -1
                        }
                        if (current >= 0 && current < saved) {
                            applyRingRestore(audioManager, saved, savedMode, attempt = attempts)
                        }
                        val actual = try {
                            audioManager.getStreamVolume(AudioManager.STREAM_RING)
                        } catch (_: Exception) {
                            -1
                        }
                        if (actual >= saved || attempts >= 10) {
                            prefs.edit()
                                .remove(KEY_PRE_DUCK_RING_VOLUME)
                                .remove(KEY_PRE_DUCK_RINGER_MODE)
                                .putBoolean(KEY_DUCK_ACTIVE, false)
                                .commit()
                            if (restoreRunnable === this) restoreRunnable = null
                            Log.i(TAG, "Ring restore settled actual=$actual target=$saved")
                            return
                        }
                        restoreHandler.postDelayed(this, 300L)
                    }
                }
                restoreRunnable = reapply
                restoreHandler.postDelayed(reapply, 300L)
            } catch (e: Exception) {
                Log.w(TAG, "restoreRingtone failed", e)
            }
        }

        private fun cancelRestoreLoop() {
            restoreRunnable?.let { restoreHandler.removeCallbacks(it) }
            restoreRunnable = null
        }

        private fun applyRingRestore(
            audioManager: AudioManager,
            volume: Int,
            ringerMode: Int,
            attempt: Int,
        ) {
            try {
                // Restore the user's exact ringer mode — do not force NORMAL if they were
                // on vibrate/silent before we ducked (we only duck when announcing, which
                // already requires NORMAL for SILENT_IF_MUTED, but ALWAYS mode may differ).
                val mode = if (ringerMode >= 0) ringerMode else audioManager.ringerMode
                if (audioManager.ringerMode != mode) {
                    audioManager.ringerMode = mode
                }
                audioManager.setStreamVolume(
                    AudioManager.STREAM_RING,
                    volume,
                    AudioManager.FLAG_ALLOW_RINGER_MODES,
                )
                val actual = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                Log.i(
                    TAG,
                    "Restored ring volume to $volume (actual=$actual mode=${audioManager.ringerMode} attempt=$attempt)",
                )
            } catch (e: Exception) {
                Log.w(TAG, "applyRingRestore failed", e)
            }
        }
    }
}
