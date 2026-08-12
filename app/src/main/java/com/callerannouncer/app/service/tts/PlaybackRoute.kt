package com.callerannouncer.app.service.tts

/** Where synthesized speech should be routed on the device. */
enum class PlaybackRoute {
    /** SMS, test voice, and general announcements. */
    MEDIA,

    /** Incoming call — must be audible over the ringtone. */
    INCOMING_CALL,
}
