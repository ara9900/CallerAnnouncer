package com.callerannouncer.app.service

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Offline-first Persian TTS wrapper around Android TextToSpeech.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var pendingSpeak: (() -> Unit)? = null

    fun initialize() {
        if (tts == null) {
            tts = TextToSpeech(appContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: $status")
            ready.set(false)
            return
        }
        val engine = tts ?: return
        val persian = Locale("fa")
        val persianIr = Locale("fa", "IR")

        val result = when {
            engine.isLanguageAvailable(persianIr) >= TextToSpeech.LANG_AVAILABLE ->
                engine.setLanguage(persianIr)
            engine.isLanguageAvailable(persian) >= TextToSpeech.LANG_AVAILABLE ->
                engine.setLanguage(persian)
            engine.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE ->
                engine.setLanguage(Locale.US) // offline fallback
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }

        ready.set(result >= TextToSpeech.LANG_AVAILABLE)
        Log.i(TAG, "TTS ready=$ready languageResult=$result")
        pendingSpeak?.invoke()
        pendingSpeak = null
    }

    fun isReady(): Boolean = ready.get()

    fun setSpeechParams(rate: Float, pitch: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    fun speak(text: String, repeatCount: Int = 1, onDone: (() -> Unit)? = null) {
        val action: () -> Unit = {
            val engine = tts
            if (engine == null || !ready.get() || text.isBlank()) {
                onDone?.invoke()
            } else {
                val utteranceId = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        onDone?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onDone?.invoke()
                    }
                })

                val params = Bundle()
                val spoken = buildString {
                    repeat(repeatCount.coerceIn(1, 5)) { index ->
                        if (index > 0) append(". ")
                        append(text)
                    }
                }
                engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                Unit
            }
        }

        if (ready.get()) {
            action()
        } else {
            pendingSpeak = action
            initialize()
        }
    }

    suspend fun speakAndAwait(text: String, repeatCount: Int = 1) =
        suspendCancellableCoroutine { cont ->
            speak(text, repeatCount) {
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation { stop() }
        }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        ready.set(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
