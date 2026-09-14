package com.callerannouncer.app.service.tts

/** Where synthesized speech should be routed on the device. */
enum class PlaybackRoute {
    /** SMS, test voice, and general announcements. */
    MEDIA,

    /** Incoming call on the phone speaker — must be audible over the ringtone. */
    INCOMING_CALL,

    /**
     * Incoming call while a headset is connected. Must use the media stream so Android
     * routes to A2DP/wired headset only. Accessibility and alarm streams fall back to the
     * loudspeaker on One UI (or go silent when pinned to Bluetooth).
     */
    HEADSET_CALL,
}
