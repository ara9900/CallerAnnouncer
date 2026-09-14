package com.callerannouncer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import com.callerannouncer.app.service.AnnouncerService

/**
 * Stops TTS when the headset play/pause button is pressed,
 * or when the user lowers volume while an announcement is playing.
 */
class AnnouncementStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                val event = intent.getParcelableExtraCompat(Intent.EXTRA_KEY_EVENT) ?: return
                if (event.action != KeyEvent.ACTION_DOWN) return
                when (event.keyCode) {
                    KeyEvent.KEYCODE_HEADSETHOOK,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_STOP,
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    -> {
                        Log.i(TAG, "Headset/media button — stopping announcement")
                        AnnouncerService.stopAnnouncement(context.applicationContext)
                    }
                }
            }
            VOLUME_CHANGED_ACTION -> {
                val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                val current = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                val previous = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                if (previous < 0 || current < 0) return
                if (current >= previous) return
                // Ignore STREAM_RING: we duck/mute it ourselves during call announcements,
                // and that broadcast was falsely aborting playback within milliseconds.
                if (
                    stream != AudioManager.STREAM_MUSIC &&
                    stream != AudioManager.STREAM_ALARM &&
                    stream != AudioManager.STREAM_VOICE_CALL
                ) {
                    return
                }
                if (!AnnouncerService.isSpeaking) return
                Log.i(TAG, "Volume down stream=$stream $previous->$current — stopping")
                AnnouncerService.stopAnnouncement(context.applicationContext)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableExtraCompat(key: String): KeyEvent? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, KeyEvent::class.java)
        } else {
            getParcelableExtra(key) as? KeyEvent
        }
    }

    companion object {
        private const val TAG = "AnnouncementStop"
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
