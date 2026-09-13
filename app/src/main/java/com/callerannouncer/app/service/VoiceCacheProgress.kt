package com.callerannouncer.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live progress of the contact voice pre-cache run, observed by the UI. */
object VoiceCacheProgress {

    data class State(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val currentLabel: String = "",
        val message: String = "",
    ) {
        val fraction: Float
            get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun starting() {
        _state.value = State(running = true, message = "در حال خواندن دفترچه تلفن…")
    }

    fun planned(total: Int) {
        _state.value = _state.value.copy(
            total = total,
            message = if (total == 0) "همه اعلام‌ها از قبل آماده است" else "",
        )
    }

    fun advance(done: Int, failed: Int, label: String) {
        _state.value = _state.value.copy(done = done, failed = failed, currentLabel = label)
    }

    fun finished(message: String) {
        _state.value = _state.value.copy(running = false, currentLabel = "", message = message)
    }
}
