package com.callerannouncer.app.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Offline-first Persian TTS wrapper around Android TextToSpeech.
 */
class TtsManager(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)
    private var initDeferred = CompletableDeferred<Boolean>()

    fun initialize() {
        if (tts != null) return
        initDeferred = CompletableDeferred()
        tts = TextToSpeech(appContext, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: $status")
            ready.set(false)
            completeInit(false)
            return
        }

        val engine = tts
        if (engine == null) {
            completeInit(false)
            return
        }

        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "setAudioAttributes failed", e)
        }

        val languageOk = configureLanguage(engine)
        ready.set(languageOk)
        Log.i(TAG, "TTS ready=$languageOk engines=${engine.defaultEngine}")
        completeInit(languageOk)
    }

    private fun configureLanguage(engine: TextToSpeech): Boolean {
        val candidates = listOf(
            Locale("fa", "IR"),
            Locale("fa"),
            Locale.getDefault(),
            Locale.US,
            Locale.ENGLISH,
        )
        for (locale in candidates) {
            val available = engine.isLanguageAvailable(locale)
            if (available < TextToSpeech.LANG_AVAILABLE) continue
            val result = engine.setLanguage(locale)
            Log.i(TAG, "Tried locale=$locale available=$available set=$result")
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                return true
            }
        }
        // Last resort: keep engine default voice so speak() can still produce audio
        Log.w(TAG, "No preferred locale; using engine default voice")
        return true
    }

    private fun completeInit(success: Boolean) {
        if (!initDeferred.isCompleted) {
            initDeferred.complete(success)
        }
    }

    fun isReady(): Boolean = ready.get()

    fun setSpeechParams(rate: Float, pitch: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    suspend fun ensureReady(timeoutMs: Long = 8_000): Boolean {
        if (ready.get() && tts != null) return true
        initialize()
        return withTimeoutOrNull(timeoutMs) { initDeferred.await() } == true || ready.get()
    }

    suspend fun speakAndAwait(text: String, repeatCount: Int = 1): Boolean {
        if (text.isBlank()) return false
        if (!ensureReady()) {
            Log.e(TAG, "TTS not ready; cannot speak")
            return false
        }

        val engine = tts
        if (engine == null) return false

        val spoken = buildString {
            repeat(repeatCount.coerceIn(1, 5)) { index ->
                if (index > 0) append(". ")
                append(text)
            }
        }

        val done = CompletableDeferred<Boolean>()
        val utteranceId = UUID.randomUUID().toString()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.i(TAG, "TTS onStart id=$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                Log.i(TAG, "TTS onDone id=$utteranceId")
                if (!done.isCompleted) done.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS onError id=$utteranceId")
                if (!done.isCompleted) done.complete(false)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS onError id=$utteranceId code=$errorCode")
                if (!done.isCompleted) done.complete(false)
            }
        })

        val params = Bundle()
        val result = engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() returned $result")
            return false
        }

        return withTimeoutOrNull(30_000) { done.await() } ?: run {
            Log.e(TAG, "TTS speak timed out")
            engine.stop()
            false
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        ready.set(false)
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (!initDeferred.isCompleted) {
            initDeferred.complete(false)
        }
    }

    companion object {
        private const val TAG = "TtsManager"
    }
}
