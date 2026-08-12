package com.callerannouncer.app.domain.model

enum class OnlineEdgeVoice(val voiceId: String, val locale: String) {
    DILARA("fa-IR-DilaraNeural", "fa-IR"),
    FARID("fa-IR-FaridNeural", "fa-IR");

    companion object {
        fun fromName(name: String): OnlineEdgeVoice =
            entries.find { it.name == name } ?: DILARA
    }
}
