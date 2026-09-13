package com.callerannouncer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.callerannouncer.app.MainActivity
import com.callerannouncer.app.R
import com.callerannouncer.app.data.preferences.SettingsRepository
import com.callerannouncer.app.domain.AnnouncementText
import com.callerannouncer.app.domain.model.OnlineEdgeVoice
import com.callerannouncer.app.domain.model.PlayMode
import com.callerannouncer.app.domain.model.TtsEngineMode
import com.callerannouncer.app.receiver.AnnouncementStopReceiver
import com.callerannouncer.app.service.tts.PlaybackRoute
import com.callerannouncer.app.service.tts.TtsModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AnnouncerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ttsManager: TtsManager
    private lateinit var audioRoutingManager: AudioRoutingManager
    private val speakMutex = Mutex()
    private var activeSpeakJob: Job? = null
    private var mediaSession: MediaSession? = null
    private var stopControlsRegistered = false

    private val volumeStopReceiver = AnnouncementStopReceiver()
    private val mediaButtonReceiver = AnnouncementStopReceiver()

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        ttsManager = TtsManager(applicationContext).also { it.initialize() }
        audioRoutingManager = AudioRoutingManager(applicationContext)
        startInForeground()
        isRunning = true
        scope.launch(Dispatchers.IO) {
            val settings = settingsRepository.settingsFlow.first()
            ttsManager.configure(settings.ttsEngineMode, settings.onlineEdgeVoice)
            if (settings.ttsEngineMode == TtsEngineMode.OFFLINE &&
                TtsModelManager.ensureModelReady(applicationContext)
            ) {
                val warmed = ttsManager.warmUp()
                Log.i(TAG, "Offline TTS warm-up result=$warmed")
            }
        }
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_ANNOUNCE_CALL -> {
                val name = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
                startSpeakJob {
                    announceCall(name.ifBlank { number.ifBlank { "ناشناس" } })
                }
            }
            ACTION_ANNOUNCE_SMS -> {
                val sender = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
                val body = intent.getStringExtra(EXTRA_SMS_BODY).orEmpty()
                startSpeakJob {
                    announceSms(sender.ifBlank { "ناشناس" }, body)
                }
            }
            ACTION_TEST_VOICE -> {
                startSpeakJob { announceTest() }
            }
            ACTION_STOP_CALL_ANNOUNCEMENT,
            ACTION_STOP_ANNOUNCEMENT -> {
                stopAnnouncementInternal(reason = intent.action.orEmpty())
            }
            ACTION_STOP -> {
                stopAnnouncementInternal(reason = ACTION_STOP)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSpeakJob(block: suspend () -> Unit) {
        activeSpeakJob?.cancel()
        activeSpeakJob = scope.launch {
            try {
                block()
            } finally {
                if (activeSpeakJob === this) {
                    activeSpeakJob = null
                }
            }
        }
    }

    private suspend fun announceCall(displayName: String) {
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.isCallAnnouncerEnabled) return
        val text = AnnouncementText.call(settings, displayName)
        speak(
            text = text,
            repeatCount = settings.callRepeatCount,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = settings.playMode,
            forcePlay = false,
            forIncomingCall = true,
            ttsEngineMode = settings.ttsEngineMode,
            onlineEdgeVoice = settings.onlineEdgeVoice,
        )
    }

    private suspend fun announceSms(sender: String, body: String) {
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.isSmsAnnouncerEnabled) return
        val senderSpoken = speak(
            text = AnnouncementText.smsSender(settings, sender),
            repeatCount = settings.smsRepeatCount,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = settings.playMode,
            forcePlay = false,
            ttsEngineMode = settings.ttsEngineMode,
            onlineEdgeVoice = settings.onlineEdgeVoice,
        )
        if (!senderSpoken || !settings.readSmsBody || body.isBlank()) return
        // The body is read once regardless of the repeat count — repeating a whole
        // message is never what the user wants.
        speak(
            text = AnnouncementText.smsBody(body),
            repeatCount = 1,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = settings.playMode,
            forcePlay = false,
            ttsEngineMode = settings.ttsEngineMode,
            onlineEdgeVoice = settings.onlineEdgeVoice,
        )
    }

    private suspend fun announceTest() {
        val settings = settingsRepository.settingsFlow.first()
        val testText = when (settings.ttsEngineMode) {
            TtsEngineMode.OFFLINE ->
                "این یک آزمایش صدای اعلام‌گر با موتور فارسی آفلاین است"
            TtsEngineMode.ONLINE_EDGE ->
                "این یک آزمایش صدای آنلاین مایکروسافت اج است"
        }
        val ok = speak(
            text = testText,
            repeatCount = 1,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = PlayMode.ALWAYS,
            forcePlay = true,
            ttsEngineMode = settings.ttsEngineMode,
            onlineEdgeVoice = settings.onlineEdgeVoice,
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(
                applicationContext,
                when {
                    ok -> "آزمایش صدا پخش شد"
                    settings.ttsEngineMode == TtsEngineMode.OFFLINE &&
                        !TtsModelManager.isModelReady(applicationContext) ->
                        "مدل صدای فارسی هنوز آماده نیست — اینترنت را چک کنید و صبر کنید"
                    settings.ttsEngineMode == TtsEngineMode.ONLINE_EDGE ->
                        "پخش آنلاین ناموفق بود — اینترنت/VPN را بررسی کنید (در تماس‌ها به آفلاین برمی‌گردد)"
                    else -> "پخش صدا ناموفق بود — اپ را ببندید و دوباره باز کنید"
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private suspend fun speak(
        text: String,
        repeatCount: Int,
        rate: Float,
        pitch: Float,
        playMode: PlayMode,
        forcePlay: Boolean,
        forIncomingCall: Boolean = false,
        ttsEngineMode: TtsEngineMode,
        onlineEdgeVoice: OnlineEdgeVoice,
    ): Boolean {
        return speakMutex.withLock {
            if (!forcePlay && !audioRoutingManager.shouldAnnounce(playMode)) {
                Log.i(TAG, "Skipped by playMode=$playMode")
                return@withLock false
            }
            ttsManager.configure(ttsEngineMode, onlineEdgeVoice)
            if (ttsEngineMode == TtsEngineMode.OFFLINE &&
                !TtsModelManager.ensureModelReady(applicationContext)
            ) {
                Log.e(TAG, "Offline TTS model not ready")
                return@withLock false
            }
            isSpeaking = true
            registerStopControls()
            refreshNotification(speaking = true)
            // A connected headset owns the announcement so it is not duplicated on the
            // phone speaker. Calls keep the alarm-stream route either way: it is the only
            // usage that stays audible while the telephony ringtone holds focus.
            val headsetDevice = audioRoutingManager.beginExclusiveHeadsetOutput()
            if (forIncomingCall) {
                isAnnouncingIncomingCall = true
                audioRoutingManager.beginIncomingCallAnnouncement()
            } else {
                val focusOk = audioRoutingManager.requestFocusAndRoute()
                if (!focusOk) {
                    Log.w(TAG, "Audio focus denied — continuing anyway")
                }
            }
            try {
                ttsManager.setSpeechParams(rate, pitch)
                val route = if (forIncomingCall) {
                    PlaybackRoute.INCOMING_CALL
                } else {
                    PlaybackRoute.MEDIA
                }
                val spoken = ttsManager.speakAndAwait(
                    text = text,
                    repeatCount = repeatCount,
                    route = route,
                    outputDevice = headsetDevice,
                )
                Log.i(
                    TAG,
                    "speak result=$spoken mode=$ttsEngineMode incoming=$forIncomingCall " +
                        "route=$route headset=${headsetDevice != null} repeat=$repeatCount text=$text",
                )
                spoken
            } finally {
                audioRoutingManager.endExclusiveHeadsetOutput()
                if (forIncomingCall) {
                    isAnnouncingIncomingCall = false
                    audioRoutingManager.endIncomingCallAnnouncement()
                } else {
                    audioRoutingManager.release()
                }
                isSpeaking = false
                unregisterStopControls()
                refreshNotification(speaking = false)
            }
        }
    }

    private fun stopAnnouncementInternal(reason: String) {
        Log.i(TAG, "Stopping announcement reason=$reason speaking=$isSpeaking")
        activeSpeakJob?.cancel()
        activeSpeakJob = null
        ttsManager.stop()
        if (isAnnouncingIncomingCall) {
            isAnnouncingIncomingCall = false
            audioRoutingManager.endIncomingCallAnnouncement()
        } else {
            audioRoutingManager.release()
        }
        isSpeaking = false
        unregisterStopControls()
        refreshNotification(speaking = false)
    }

    private fun registerStopControls() {
        if (stopControlsRegistered) return
        stopControlsRegistered = true
        try {
            ContextCompat.registerReceiver(
                this,
                volumeStopReceiver,
                IntentFilter(AnnouncementStopReceiver.VOLUME_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Volume receiver register failed", e)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                mediaButtonReceiver,
                IntentFilter(Intent.ACTION_MEDIA_BUTTON),
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Media button receiver register failed", e)
        }
        try {
            mediaSession?.release()
            mediaSession = MediaSession(this, "CallerAnnouncer").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val event = mediaButtonIntent.getParcelableExtraCompatKey()
                        if (event?.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_STOP,
                                -> {
                                    stopAnnouncementInternal("media_session")
                                    return true
                                }
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }

                    override fun onPause() {
                        stopAnnouncementInternal("media_pause")
                    }

                    override fun onStop() {
                        stopAnnouncementInternal("media_stop")
                    }

                    override fun onPlay() {
                        // Ignore — headset middle button often sends play/pause together.
                        stopAnnouncementInternal("media_play")
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_STOP or
                                PlaybackState.ACTION_PLAY_PAUSE or
                                PlaybackState.ACTION_PLAY
                        )
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .build()
                )
                isActive = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaSession setup failed", e)
        }
    }

    private fun unregisterStopControls() {
        if (!stopControlsRegistered) return
        stopControlsRegistered = false
        try {
            unregisterReceiver(volumeStopReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(mediaButtonReceiver)
        } catch (_: Exception) {
        }
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompatKey(): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        }
    }

    private fun startInForeground() {
        ensureChannel()
        val notification = buildNotification(speaking = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification(speaking: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(speaking))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "سرویس اعلام‌کننده",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "اجرای پس‌زمینه اعلام تماس و پیامک"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(speaking: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AnnouncerService::class.java).apply {
                action = ACTION_STOP_ANNOUNCEMENT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (speaking) {
            "در حال اعلام — برای قطع، دکمه را بزنید یا ولوم را کم کنید"
        } else {
            "سرویس اعلام تماس و پیامک فعال است"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "قطع اعلام", stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        isSpeaking = false
        unregisterStopControls()
        ttsManager.shutdown()
        audioRoutingManager.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AnnouncerService"
        const val CHANNEL_ID = "announcer_service"
        const val NOTIFICATION_ID = 1001

        const val ACTION_ANNOUNCE_CALL = "com.callerannouncer.app.ACTION_ANNOUNCE_CALL"
        const val ACTION_ANNOUNCE_SMS = "com.callerannouncer.app.ACTION_ANNOUNCE_SMS"
        const val ACTION_TEST_VOICE = "com.callerannouncer.app.ACTION_TEST_VOICE"
        const val ACTION_STOP_CALL_ANNOUNCEMENT =
            "com.callerannouncer.app.ACTION_STOP_CALL_ANNOUNCEMENT"
        const val ACTION_STOP_ANNOUNCEMENT =
            "com.callerannouncer.app.ACTION_STOP_ANNOUNCEMENT"
        const val ACTION_STOP = "com.callerannouncer.app.ACTION_STOP"

        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_SMS_BODY = "extra_sms_body"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isSpeaking: Boolean = false
            private set

        @Volatile
        private var isAnnouncingIncomingCall: Boolean = false

        private fun startServiceCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) {
            startServiceCompat(context, Intent(context, AnnouncerService::class.java))
        }

        fun stop(context: Context) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
            context.stopService(Intent(context, AnnouncerService::class.java))
        }

        fun announceCall(context: Context, displayName: String, phoneNumber: String) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_ANNOUNCE_CALL
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            }
            startServiceCompat(context, intent)
        }

        fun announceSms(context: Context, displayName: String, body: String) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_ANNOUNCE_SMS
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_SMS_BODY, body)
            }
            startServiceCompat(context, intent)
        }

        fun testVoice(context: Context) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_TEST_VOICE
            }
            startServiceCompat(context, intent)
        }

        fun stopCallAnnouncement(context: Context) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_STOP_CALL_ANNOUNCEMENT
            }
            context.startService(intent)
        }

        fun stopAnnouncement(context: Context) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_STOP_ANNOUNCEMENT
            }
            context.startService(intent)
        }
    }
}
