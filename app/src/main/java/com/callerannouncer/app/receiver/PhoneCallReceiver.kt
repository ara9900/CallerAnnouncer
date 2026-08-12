package com.callerannouncer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.callerannouncer.app.service.AnnouncerService
import com.callerannouncer.app.util.ContactHelper

/**
 * Detects incoming calls and announces the contact name (or number / ناشناس).
 */
class PhoneCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        // EXTRA_INCOMING_NUMBER requires READ_CALL_LOG on API 28+
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val displayName = ContactHelper.resolveDisplayName(context, number)

        Log.i(TAG, "Incoming call from=$number name=$displayName")
        AnnouncerService.announceCall(
            context = context.applicationContext,
            displayName = displayName,
            phoneNumber = number.orEmpty()
        )
    }

    companion object {
        private const val TAG = "PhoneCallReceiver"
    }
}
