package com.callerannouncer.app.domain.model

enum class TtsEngineMode {
    /** Embedded sherpa-onnx + Piper (no internet after model download). */
    OFFLINE,

    /** Microsoft Edge neural TTS — free, requires internet. */
    ONLINE_EDGE;

    companion object {
        fun fromName(name: String): TtsEngineMode =
            entries.find { it.name == name } ?: OFFLINE
    }
}
