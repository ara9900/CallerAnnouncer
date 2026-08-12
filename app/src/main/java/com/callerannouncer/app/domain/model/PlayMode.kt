package com.callerannouncer.app.domain.model

enum class PlayMode {
    ALWAYS,
    ONLY_HEADPHONES_BLUETOOTH,
    SILENT_IF_MUTED;

    companion object {
        fun fromName(name: String): PlayMode =
            entries.find { it.name == name } ?: ALWAYS
    }
}
