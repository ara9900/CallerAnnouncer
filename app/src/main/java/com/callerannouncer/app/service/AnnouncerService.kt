package com.callerannouncer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callerannouncer.app.MainActivity
import com.callerannouncer.app.R
import com.callerannouncer.app.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnnouncerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var ttsManager: TtsManager
    private lateinit var audioRoutingManager: AudioRoutingManager
    private val speakMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        ttsManager = TtsManager(applicationContext).also { it.initialize() }
        audioRoutingManager = AudioRoutingManager(applicationContext)
        startInForeground()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANNOUNCE_CALL -> {
                val name = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty()
                scope.launch { announceCall(name.ifBlank { number.ifBlank { "ناشناس" } }) }
            }
            ACTION_ANNOUNCE_SMS -> {
                val sender = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
                val body = intent.getStringExtra(EXTRA_SMS_BODY).orEmpty()
                scope.launch { announceSms(sender.ifBlank { "ناشناس" }, body) }
            }
            ACTION_TEST_VOICE -> {
                scope.launch { announceRaw("این یک آزمایش صدای اعلام‌کننده تماس و پیامک است") }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun announceCall(displayName: String) {
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.isCallAnnouncerEnabled) return
        val text = "${settings.callPrefix} $displayName ${settings.callSuffix}"
        speak(text, settings.repeatCount, settings.speechRate, settings.pitch, settings.playMode)
    }

    private suspend fun announceSms(sender: String, body: String) {
        val settings = settingsRepository.settingsFlow.first()
        if (!settings.isSmsAnnouncerEnabled) return
        val text = buildString {
            append(settings.smsPrefix)
            append(' ')
            append(sender)
            if (settings.readSmsBody && body.isNotBlank()) {
                append(". متن پیام: ")
                append(body)
            }
        }
        speak(text, settings.repeatCount, settings.speechRate, settings.pitch, settings.playMode)
    }

    private suspend fun announceRaw(text: String) {
        val settings = settingsRepository.settingsFlow.first()
        speak(text, 1, settings.speechRate, settings.pitch, settings.playMode)
    }

    private suspend fun speak(
        text: String,
        repeatCount: Int,
        rate: Float,
        pitch: Float,
        playMode: com.callerannouncer.app.domain.model.PlayMode,
    ) {
        speakMutex.withLock {
            if (!audioRoutingManager.shouldAnnounce(playMode)) {
                Log.i(TAG, "Skipped by playMode=$playMode")
                return
            }
            if (!audioRoutingManager.requestFocusAndRoute()) {
                Log.w(TAG, "Audio focus denied")
            }
            try {
                ttsManager.setSpeechParams(rate, pitch)
                ttsManager.speakAndAwait(text, repeatCount)
            } finally {
                audioRoutingManager.release()
            }
        }
    }

    private fun startInForeground() {
        ensureChannel()
        val notification = buildNotification()
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

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("سرویس اعلام تماس و پیامک فعال است")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
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
        const val ACTION_STOP = "com.callerannouncer.app.ACTION_STOP"

        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_SMS_BODY = "extra_sms_body"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, AnnouncerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
            context.stopService(Intent(context, AnnouncerService::class.java))
        }

        fun announceCall(context: Context, displayName: String, phoneNumber: String) {
            start(context)
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_ANNOUNCE_CALL
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
            }
            context.startService(intent)
        }

        fun announceSms(context: Context, displayName: String, body: String) {
            start(context)
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_ANNOUNCE_SMS
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_SMS_BODY, body)
            }
            context.startService(intent)
        }

        fun testVoice(context: Context) {
            start(context)
            val intent = Intent(context, AnnouncerService::class.java).apply {
                action = ACTION_TEST_VOICE
            }
            context.startService(intent)
        }
    }
}
