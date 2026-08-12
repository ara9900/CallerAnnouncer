package com.callerannouncer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.callerannouncer.app.service.AnnouncerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AnnouncerService.start(context.applicationContext)
        }
    }
}
