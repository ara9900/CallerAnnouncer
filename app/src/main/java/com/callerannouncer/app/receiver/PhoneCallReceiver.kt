package com.callerannouncer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        if (!shouldAnnounce(number)) {
            Log.i(TAG, "Skipping duplicate ring event for number=$number")
            return
        }

        val displayName = ContactHelper.resolveDisplayName(context, number)

        Log.i(TAG, "Incoming call from=$number name=$displayName")
        AnnouncerService.announceCall(
            context = context.applicationContext,
            displayName = displayName,
            phoneNumber = number,
        )
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

        @Volatile
        private var lastNumber: String = ""

        private val lastAnnounceAt = AtomicLong(0)
    }
}
