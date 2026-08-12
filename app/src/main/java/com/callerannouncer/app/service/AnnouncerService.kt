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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callerannouncer.app.MainActivity
import com.callerannouncer.app.R
import com.callerannouncer.app.data.preferences.SettingsRepository
import com.callerannouncer.app.domain.model.PlayMode
import com.callerannouncer.app.service.tts.PlaybackRoute
import com.callerannouncer.app.service.tts.TtsModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        ttsManager = TtsManager(applicationContext).also { it.initialize() }
        audioRoutingManager = AudioRoutingManager(applicationContext)
        startInForeground()
        isRunning = true
        scope.launch(Dispatchers.IO) {
            if (TtsModelManager.ensureModelReady(applicationContext)) {
                val warmed = ttsManager.warmUp()
                Log.i(TAG, "TTS warm-up result=$warmed")
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
                scope.launch { announceCall(name.ifBlank { number.ifBlank { "ناشناس" } }) }
            }
            ACTION_ANNOUNCE_SMS -> {
                val sender = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
                val body = intent.getStringExtra(EXTRA_SMS_BODY).orEmpty()
                scope.launch { announceSms(sender.ifBlank { "ناشناس" }, body) }
            }
            ACTION_TEST_VOICE -> {
                scope.launch { announceTest() }
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
        speak(
            text = text,
            repeatCount = settings.repeatCount,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = settings.playMode,
            forcePlay = false,
            forIncomingCall = true,
        )
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
        speak(
            text = text,
            repeatCount = settings.repeatCount,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = settings.playMode,
            forcePlay = false,
        )
    }

    private suspend fun announceTest() {
        val settings = settingsRepository.settingsFlow.first()
        val ok = speak(
            text = "این یک آزمایش صدای اعلام‌گر با موتور فارسی آفلاین است",
            repeatCount = 1,
            rate = settings.speechRate,
            pitch = settings.pitch,
            playMode = PlayMode.ALWAYS,
            forcePlay = true,
        )
        withContext(Dispatchers.Main) {
            Toast.makeText(
                applicationContext,
                when {
                    ok -> "آزمایش صدا پخش شد"
                    !TtsModelManager.isModelReady(applicationContext) ->
                        "مدل صدای فارسی هنوز آماده نیست — اینترنت را چک کنید و صبر کنید"
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
    ): Boolean {
        return speakMutex.withLock {
            if (!forcePlay && !audioRoutingManager.shouldAnnounce(playMode)) {
                Log.i(TAG, "Skipped by playMode=$playMode")
                return@withLock false
            }
            if (!TtsModelManager.ensureModelReady(applicationContext)) {
                Log.e(TAG, "TTS model not ready")
                return@withLock false
            }
            if (forIncomingCall) {
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
                )
                Log.i(TAG, "speak result=$spoken incoming=$forIncomingCall text=$text")
                spoken
            } finally {
                if (forIncomingCall) {
                    audioRoutingManager.endIncomingCallAnnouncement()
                } else {
                    audioRoutingManager.release()
                }
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
    }
}
