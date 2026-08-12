package com.callerannouncer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import com.callerannouncer.app.service.AnnouncerService
import com.callerannouncer.app.util.ContactHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects incoming calls and announces the contact name (or number / ناشناس).
 */
class PhoneCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> handleRinging(context, intent)
            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_IDLE -> {
                Log.i(TAG, "Call state=$state — stopping announcement")
                AnnouncerService.stopCallAnnouncement(context.applicationContext)
                if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    resetDebounce()
                }
            }
        }
    }

    private fun handleRinging(context: Context, intent: Intent) {
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        pendingAnnounceRunnable?.let { handler.removeCallbacks(it) }
        pendingAnnounceRunnable = null

        if (number.isBlank()) {
            // Samsung and some OEMs fire RINGING twice: first without number, then with it.
            val appContext = context.applicationContext
            pendingAnnounceRunnable = Runnable {
                pendingAnnounceRunnable = null
                announceRinging(appContext, "")
            }
            handler.postDelayed(pendingAnnounceRunnable!!, BLANK_NUMBER_DELAY_MS)
            Log.i(TAG, "RINGING without number — waiting ${BLANK_NUMBER_DELAY_MS}ms for caller id")
            return
        }

        announceRinging(context.applicationContext, number)
    }

    private fun announceRinging(context: Context, number: String) {
        if (!shouldAnnounce(number)) {
            Log.i(TAG, "Skipping duplicate ring event for number=$number")
            return
        }

        val displayName = ContactHelper.resolveDisplayName(context, number)

        Log.i(TAG, "Incoming call from=$number name=$displayName")
        AnnouncerService.announceCall(
            context = context,
            displayName = displayName,
            phoneNumber = number,
        )
    }

    private fun resetDebounce() {
        pendingAnnounceRunnable?.let { handler.removeCallbacks(it) }
        pendingAnnounceRunnable = null
        lastNumber = ""
        lastAnnounceAt.set(0)
    }

    private fun shouldAnnounce(number: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAnnounceAt.get()
        val sameNumber = number == lastNumber
        if (sameNumber && now - last < DEBOUNCE_MS) return false
        lastNumber = number
        lastAnnounceAt.set(now)
        return true
    }

    companion object {
        private const val TAG = "PhoneCallReceiver"
        private const val DEBOUNCE_MS = 15_000L
        private const val BLANK_NUMBER_DELAY_MS = 350L

        private val handler = Handler(Looper.getMainLooper())

        @Volatile
        private var pendingAnnounceRunnable: Runnable? = null

        @Volatile
        private var lastNumber: String = ""

        private val lastAnnounceAt = AtomicLong(0)
    }
}
