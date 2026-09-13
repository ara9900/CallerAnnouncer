package com.callerannouncer.app.domain

import com.callerannouncer.app.domain.model.UserSettings

/**
 * Single source of truth for the sentences that get spoken. Pre-caching renders the very
 * same strings the announcer will ask for later, so the cache keys line up exactly.
 */
object AnnouncementText {

    fun call(settings: UserSettings, displayName: String): String =
        normalize("${settings.callPrefix} $displayName ${settings.callSuffix}")

    fun smsSender(settings: UserSettings, sender: String): String =
        normalize("${settings.smsPrefix} $sender")

    /**
     * Spoken separately from the sender so the sender half can be served from the cache
     * offline; only the message body itself still needs a live synthesis.
     */
    fun smsBody(body: String): String = normalize("متن پیام: $body")

    private fun normalize(value: String): String =
        value.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
}
