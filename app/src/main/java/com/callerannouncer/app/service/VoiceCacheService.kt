package com.callerannouncer.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callerannouncer.app.MainActivity
import com.callerannouncer.app.R
import com.callerannouncer.app.data.preferences.SettingsRepository
import com.callerannouncer.app.domain.AnnouncementText
import com.callerannouncer.app.domain.model.UserSettings
import com.callerannouncer.app.domain.model.VoiceCacheScope
import com.callerannouncer.app.service.tts.OnlineEdgeTtsEngine
import com.callerannouncer.app.util.ContactHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Renders every contact announcement once with the online voice and stores it on disk,
 * so later calls are announced instantly and without a network connection.
 */
class VoiceCacheService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                Log.i(TAG, "Cancel requested")
                job?.cancel()
                VoiceCacheProgress.finished("آماده‌سازی متوقف شد")
                stopSelf()
            }
            else -> {
                val cacheScope = VoiceCacheScope.fromName(intent?.getStringExtra(EXTRA_SCOPE))
                val wifiOnly = intent?.getBooleanExtra(EXTRA_WIFI_ONLY, true) ?: true
                startForegroundWithProgress()
                if (job?.isActive != true) {
                    job = scope.launch { run(cacheScope, wifiOnly) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(cacheScope: VoiceCacheScope, wifiOnly: Boolean) {
        VoiceCacheProgress.starting()
        try {
            if (wifiOnly && !isUnmetered()) {
                VoiceCacheProgress.finished("برای شروع به وای‌فای وصل شوید یا گزینه فقط وای‌فای را خاموش کنید")
                return
            }
            if (!ContactHelper.hasPermission(this, Manifest.permission.READ_CONTACTS)) {
                VoiceCacheProgress.finished("دسترسی مخاطبین داده نشده است")
                return
            }

            val settings = SettingsRepository(applicationContext).settingsFlow.first()
            val engine = OnlineEdgeTtsEngine(applicationContext).apply {
                setVoice(settings.onlineEdgeVoice)
                setSpeechParams(settings.speechRate, settings.pitch)
            }

            val names = collectContactNames(cacheScope)
            val pending = buildSentences(settings, names).filterNot { engine.isCached(it) }
            Log.i(TAG, "Scope=$cacheScope names=${names.size} pending=${pending.size}")
            VoiceCacheProgress.planned(pending.size)
            if (pending.isEmpty()) {
                VoiceCacheProgress.finished("همه اعلام‌ها از قبل آماده بود")
                return
            }

            val done = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val consecutiveFailures = AtomicInteger(0)
            val gate = Semaphore(MAX_PARALLEL)

            // Children of this job so cancelling the run really stops the network work.
            coroutineScope {
                pending.map { sentence ->
                    async {
                        if (consecutiveFailures.get() >= FAILURE_ABORT_THRESHOLD) return@async
                        gate.withPermit {
                            val ok = engine.prepare(sentence)
                            if (ok) {
                                consecutiveFailures.set(0)
                            } else {
                                failed.incrementAndGet()
                                consecutiveFailures.incrementAndGet()
                            }
                            val completed = done.incrementAndGet()
                            VoiceCacheProgress.advance(completed, failed.get(), sentence)
                            if (completed % NOTIFY_EVERY == 0) {
                                updateNotification(completed, pending.size)
                            }
                            // Gentle pacing so the free Edge endpoint does not throttle us.
                            delay(REQUEST_SPACING_MS)
                        }
                    }
                }.awaitAll()
            }

            val aborted = consecutiveFailures.get() >= FAILURE_ABORT_THRESHOLD
            VoiceCacheProgress.finished(
                when {
                    aborted -> "سرور پاسخ نمی‌دهد — بعداً دوباره تلاش کنید (تا اینجا ذخیره شد)"
                    failed.get() > 0 ->
                        "${done.get() - failed.get()} اعلام آماده شد، ${failed.get()} مورد ناموفق"
                    else -> "${done.get()} اعلام آماده و ذخیره شد"
                },
            )
        } catch (e: CancellationException) {
            // The cancel action already published its own message.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pre-cache run failed", e)
            VoiceCacheProgress.finished("آماده‌سازی ناتمام ماند: ${e.message ?: "خطای نامشخص"}")
        } finally {
            stopSelf()
        }
    }

    private fun buildSentences(settings: UserSettings, names: List<String>): List<String> {
        val sentences = LinkedHashSet<String>()
        names.forEach { name ->
            sentences += AnnouncementText.call(settings, name)
            sentences += AnnouncementText.smsSender(settings, name)
        }
        return sentences.toList()
    }

    /**
     * Ordered so a limited run covers the people who actually call: recent call log
     * frequency first, then starred contacts, then the rest of the phone book.
     */
    private fun collectContactNames(cacheScope: VoiceCacheScope): List<String> {
        val ordered = LinkedHashSet<String>()
        ordered += frequentCallerNames()
        ordered += contactNames(starredOnly = true)
        ordered += contactNames(starredOnly = false)
        val names = ordered.filter { it.isNotBlank() && it.any { ch -> ch.isLetter() } }
        return cacheScope.contactLimit?.let { names.take(it) } ?: names
    }

    private fun frequentCallerNames(): List<String> {
        if (!ContactHelper.hasPermission(this, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val tally = LinkedHashMap<String, Int>()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                var rows = 0
                while (cursor.moveToNext() && rows < CALL_LOG_ROWS) {
                    rows++
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.isNotBlank()) {
                        tally[name] = (tally[name] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Call log ranking failed", e)
        }
        return tally.entries.sortedByDescending { it.value }.map { it.key }
    }

    private fun contactNames(starredOnly: Boolean): List<String> {
        val names = mutableListOf<String>()
        val selection = StringBuilder("${ContactsContract.Contacts.HAS_PHONE_NUMBER}=1")
        if (starredOnly) selection.append(" AND ${ContactsContract.Contacts.STARRED}=1")
        try {
            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                selection.toString(),
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }?.let { names += it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact query failed (starred=$starredOnly)", e)
        }
        return names
    }

    private fun isUnmetered(): Boolean {
        return try {
            val manager = getSystemService(ConnectivityManager::class.java)
            val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        } catch (e: Exception) {
            Log.w(TAG, "Network check failed", e)
            true
        }
    }

    private fun startForegroundWithProgress() {
        ensureChannel()
        val notification = buildNotification(0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(done: Int, total: Int) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(done, total))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed", e)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "آماده‌سازی صدای مخاطبان",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "ذخیره صدای اعلام مخاطبان برای استفاده آفلاین"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(done: Int, total: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceCacheService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("آماده‌سازی صدای مخاطبان")
            .setContentText(if (total > 0) "$done از $total اعلام ذخیره شد" else "در حال آماده‌سازی…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setContentIntent(openIntent)
            .addAction(0, "توقف", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoiceCacheService"
        private const val CHANNEL_ID = "voice_cache_service"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_PARALLEL = 3
        private const val REQUEST_SPACING_MS = 120L
        private const val NOTIFY_EVERY = 5
        private const val CALL_LOG_ROWS = 600
        private const val FAILURE_ABORT_THRESHOLD = 25

        const val ACTION_START = "com.callerannouncer.app.ACTION_START_VOICE_CACHE"
        const val ACTION_CANCEL = "com.callerannouncer.app.ACTION_CANCEL_VOICE_CACHE"
        private const val EXTRA_SCOPE = "extra_scope"
        private const val EXTRA_WIFI_ONLY = "extra_wifi_only"

        fun start(context: Context, scope: VoiceCacheScope, wifiOnly: Boolean) {
            val intent = Intent(context, VoiceCacheService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SCOPE, scope.name)
                putExtra(EXTRA_WIFI_ONLY, wifiOnly)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, VoiceCacheService::class.java).apply { action = ACTION_CANCEL },
            )
        }
    }
}
