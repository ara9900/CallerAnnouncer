package com.callerannouncer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.callerannouncer.app.service.AnnouncerService
import com.callerannouncer.app.util.ContactHelper

/**
 * Intercepts incoming SMS and forwards sender + body to AnnouncerService.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val senderNumber = messages.firstOrNull()?.displayOriginatingAddress
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val displayName = ContactHelper.resolveDisplayName(context, senderNumber)

        Log.i(TAG, "SMS from=$senderNumber name=$displayName")
        AnnouncerService.announceSms(
            context = context.applicationContext,
            displayName = displayName,
            body = body
        )
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
