package com.callerannouncer.app.domain.model

/** How many contacts a pre-cache run should cover. */
enum class VoiceCacheScope(val contactLimit: Int?) {
    FREQUENT(60),
    TOP(300),
    ALL(null);

    companion object {
        fun fromName(value: String?): VoiceCacheScope =
            entries.firstOrNull { it.name == value } ?: FREQUENT
    }
}
