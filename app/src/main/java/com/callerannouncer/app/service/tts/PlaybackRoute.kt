package com.callerannouncer.app.service.tts

/** Where synthesized speech should be routed on the device. */
enum class PlaybackRoute {
    /** SMS, test voice, and general announcements. */
    MEDIA,

    /** Incoming call on the phone speaker — must be audible over the ringtone. */
    INCOMING_CALL,

    /**
     * Incoming call while a headset is connected. The media usage gets suspended when
     * the ringtone grabs the Bluetooth link, and the alarm usage is duplicated onto the
     * loudspeaker; the accessibility usage stays on the headset and keeps playing.
     */
    HEADSET_CALL,
}
