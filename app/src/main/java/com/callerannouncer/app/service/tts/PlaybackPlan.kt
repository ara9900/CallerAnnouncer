package com.callerannouncer.app.service.tts

import android.media.AudioDeviceInfo
import com.callerannouncer.app.domain.model.PlayMode

/** One output target for an announcement. */
data class PlaybackLeg(
    val route: PlaybackRoute,
    val outputDevice: AudioDeviceInfo? = null,
)

/**
 * Resolved playback policy for one announcement.
 *
 * ALWAYS (+ audible SILENT_IF_MUTED): speaker and headset together when a headset exists.
 * ONLY_HEADPHONES_BLUETOOTH: headset only. For calls we duck the ringtone so A2DP
 * stays free for the announcement (Samsung otherwise plays only the ring in the buds).
 */
data class PlaybackPlan(
    val shouldAnnounce: Boolean,
    val duckRing: Boolean,
    val legs: List<PlaybackLeg>,
) {
    companion object {
        fun skip(): PlaybackPlan = PlaybackPlan(
            shouldAnnounce = false,
            duckRing = false,
            legs = emptyList(),
        )

        fun resolve(
            playMode: PlayMode,
            forIncomingCall: Boolean,
            headset: AudioDeviceInfo?,
            allowAnnounce: Boolean,
        ): PlaybackPlan {
            if (!allowAnnounce) return skip()

            return when (playMode) {
                PlayMode.ONLY_HEADPHONES_BLUETOOTH -> {
                    if (headset == null) return skip()
                    PlaybackPlan(
                        shouldAnnounce = true,
                        // Ringtone on A2DP silences media announcements on Samsung buds.
                        // User prefers hearing the name over keeping the ringtone.
                        duckRing = forIncomingCall,
                        legs = listOf(
                            PlaybackLeg(
                                route = if (forIncomingCall) {
                                    PlaybackRoute.HEADSET_CALL
                                } else {
                                    PlaybackRoute.MEDIA
                                },
                                outputDevice = headset,
                            ),
                        ),
                    )
                }

                PlayMode.ALWAYS,
                PlayMode.SILENT_IF_MUTED,
                -> {
                    if (headset != null) {
                        PlaybackPlan(
                            shouldAnnounce = true,
                            // Duck only for calls — SMS notification sound stays untouched.
                            duckRing = forIncomingCall,
                            legs = listOf(
                                // ALARM reaches the built-in speaker even while A2DP is active.
                                PlaybackLeg(
                                    route = PlaybackRoute.INCOMING_CALL,
                                    outputDevice = null,
                                ),
                                PlaybackLeg(
                                    route = if (forIncomingCall) {
                                        PlaybackRoute.HEADSET_CALL
                                    } else {
                                        PlaybackRoute.MEDIA
                                    },
                                    outputDevice = headset,
                                ),
                            ),
                        )
                    } else {
                        PlaybackPlan(
                            shouldAnnounce = true,
                            duckRing = forIncomingCall,
                            legs = listOf(
                                PlaybackLeg(
                                    route = if (forIncomingCall) {
                                        PlaybackRoute.INCOMING_CALL
                                    } else {
                                        PlaybackRoute.MEDIA
                                    },
                                    outputDevice = null,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}
