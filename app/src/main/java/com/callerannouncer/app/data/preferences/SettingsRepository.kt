package com.callerannouncer.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.callerannouncer.app.domain.model.PlayMode
import com.callerannouncer.app.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "caller_announcer_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CALL_ENABLED = booleanPreferencesKey("is_call_announcer_enabled")
        val SMS_ENABLED = booleanPreferencesKey("is_sms_announcer_enabled")
        val READ_SMS_BODY = booleanPreferencesKey("read_sms_body")
        val REPEAT_COUNT = intPreferencesKey("repeat_count")
        val PLAY_MODE = stringPreferencesKey("play_mode")
        val CALL_PREFIX = stringPreferencesKey("call_prefix")
        val CALL_SUFFIX = stringPreferencesKey("call_suffix")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PITCH = floatPreferencesKey("pitch")
        val SMS_PREFIX = stringPreferencesKey("sms_prefix")
    }

    val settingsFlow: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            isCallAnnouncerEnabled = prefs[Keys.CALL_ENABLED] ?: true,
            isSmsAnnouncerEnabled = prefs[Keys.SMS_ENABLED] ?: true,
            readSmsBody = prefs[Keys.READ_SMS_BODY] ?: false,
            repeatCount = (prefs[Keys.REPEAT_COUNT] ?: 2).coerceIn(1, 5),
            playMode = PlayMode.fromName(prefs[Keys.PLAY_MODE] ?: PlayMode.ALWAYS.name),
            callPrefix = prefs[Keys.CALL_PREFIX] ?: "تماس از طرف",
            callSuffix = prefs[Keys.CALL_SUFFIX] ?: "در حال زنگ زدن است",
            speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
            pitch = prefs[Keys.PITCH] ?: 1.0f,
            smsPrefix = prefs[Keys.SMS_PREFIX] ?: "پیامک از",
        )
    }

    suspend fun setCallAnnouncerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CALL_ENABLED] = enabled }
    }

    suspend fun setSmsAnnouncerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SMS_ENABLED] = enabled }
    }

    suspend fun setReadSmsBody(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.READ_SMS_BODY] = enabled }
    }

    suspend fun setRepeatCount(count: Int) {
        context.settingsDataStore.edit { it[Keys.REPEAT_COUNT] = count.coerceIn(1, 5) }
    }

    suspend fun setPlayMode(mode: PlayMode) {
        context.settingsDataStore.edit { it[Keys.PLAY_MODE] = mode.name }
    }

    suspend fun setCallPrefix(prefix: String) {
        context.settingsDataStore.edit { it[Keys.CALL_PREFIX] = prefix }
    }

    suspend fun setCallSuffix(suffix: String) {
        context.settingsDataStore.edit { it[Keys.CALL_SUFFIX] = suffix }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.settingsDataStore.edit { it[Keys.SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setPitch(pitch: Float) {
        context.settingsDataStore.edit { it[Keys.PITCH] = pitch.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setSmsPrefix(prefix: String) {
        context.settingsDataStore.edit { it[Keys.SMS_PREFIX] = prefix }
    }
}
