package com.callerannouncer.app.domain.model

data class UserSettings(
    val isCallAnnouncerEnabled: Boolean = true,
    val isSmsAnnouncerEnabled: Boolean = true,
    val readSmsBody: Boolean = false,
    val repeatCount: Int = 2,
    val playMode: PlayMode = PlayMode.ALWAYS,
    val ttsEngineMode: TtsEngineMode = TtsEngineMode.OFFLINE,
    val onlineEdgeVoice: OnlineEdgeVoice = OnlineEdgeVoice.DILARA,
    val callPrefix: String = "تماس از طرف",
    val callSuffix: String = "در حال زنگ زدن است",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val smsPrefix: String = "پیامک از",
)
