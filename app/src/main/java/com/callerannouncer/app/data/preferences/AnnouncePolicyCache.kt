package com.callerannouncer.app.data.preferences

import android.content.Context
import com.callerannouncer.app.domain.model.PlayMode
import com.callerannouncer.app.domain.model.UserSettings

/**
 * Synchronous mirror of the few settings the phone-state receiver needs.
 * DataStore is suspend-only, but the receiver has to decide whether to touch the
 * ringtone within milliseconds of the first RINGING broadcast.
 */
object AnnouncePolicyCache {

    data class Policy(
        val callEnabled: Boolean,
        val playMode: PlayMode,
    )

    private const val PREFS_NAME = "announce_policy_cache"
    private const val KEY_CALL_ENABLED = "call_enabled"
    private const val KEY_PLAY_MODE = "play_mode"

    fun update(context: Context, settings: UserSettings) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CALL_ENABLED, settings.isCallAnnouncerEnabled)
            .putString(KEY_PLAY_MODE, settings.playMode.name)
            .apply()
    }

    fun read(context: Context): Policy {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Policy(
            callEnabled = prefs.getBoolean(KEY_CALL_ENABLED, true),
            playMode = PlayMode.fromName(
                prefs.getString(KEY_PLAY_MODE, PlayMode.ALWAYS.name) ?: PlayMode.ALWAYS.name,
            ),
        )
    }
}
