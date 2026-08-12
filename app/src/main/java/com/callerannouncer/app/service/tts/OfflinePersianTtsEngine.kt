package com.callerannouncer.app.service.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Embedded offline Persian neural TTS (Piper VITS via sherpa-onnx).
 * Does not use Android system TextToSpeech.
 */
class OfflinePersianTtsEngine(private val context: Context) {

    private val appContext = context.applicationContext
    private val initMutex = Mutex()
    private var tts: OfflineTts? = null
    private var audioPlayer: PcmAudioPlayer? = null

    suspend fun ensureReady(): Boolean {
        if (tts != null) return true
        return initMutex.withLock {
            if (tts != null) return@withLock true
            if (!TtsModelManager.ensureModelReady(appContext)) {
                Log.e(TAG, "Persian TTS model not ready")
                return@withLock false
            }
            try {
                withContext(Dispatchers.IO) {
                    val modelDir = TtsModelManager.modelDirectory(appContext)
                    val config = getOfflineTtsConfig(
                        modelDir = modelDir.absolutePath,
                        modelName = MODEL_FILE,
                        acousticModelName = "",
                        vocoder = "",
                        voices = "",
                        lexicon = "",
                        dataDir = File(modelDir, ESPEAK_DIR).absolutePath,
                        dictDir = "",
                        ruleFsts = "",
                        ruleFars = "",
                        numThreads = 2,
                    )
                    val engine = OfflineTts(assetManager = null, config = config)
                    tts = engine
                    audioPlayer = PcmAudioPlayer(engine.sampleRate())
                }
                Log.i(TAG, "Offline Persian TTS initialized")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init offline TTS", e)
                false
            }
        }
    }

    suspend fun speak(text: String, speed: Float, repeatCount: Int): Boolean {
        if (text.isBlank()) return false
        if (!ensureReady()) return false

        val engine = tts ?: return false
        val player = audioPlayer ?: return false
        val normalizedSpeed = speed.coerceIn(0.6f, 1.6f)
        val times = repeatCount.coerceIn(1, 5)

        return withContext(Dispatchers.IO) {
            try {
                repeat(times) { index ->
                    if (index > 0) {
                        Thread.sleep(250)
                    }
                    player.beginSession()

                    val callback: (FloatArray) -> Int = { chunk ->
                        if (player.isStopped()) {
                            0
                        } else {
                            player.writeSamples(chunk)
                            1
                        }
                    }

                    val audio = engine.generateWithCallback(
                        text = text,
                        sid = 0,
                        speed = normalizedSpeed,
                        callback = callback,
                    )

                    if (audio.samples.isEmpty()) {
                        Log.e(TAG, "Generated empty audio for text=$text")
                        player.endSession()
                        return@withContext false
                    }

                    val durationSec = audio.samples.size.toFloat() / engine.sampleRate()
                    Log.i(
                        TAG,
                        "Generated ${audio.samples.size} samples (~${"%.1f".format(durationSec)}s)",
                    )

                    player.awaitPlayback(audio.samples.size)
                    player.endSession()
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "speak failed", e)
                false
            }
        }
    }

    fun stop() {
        audioPlayer?.stop()
    }

    fun shutdown() {
        stop()
        tts?.release()
        tts = null
        audioPlayer?.release()
        audioPlayer = null
    }

    companion object {
        private const val TAG = "OfflinePersianTts"
        const val MODEL_FILE = "fa_IR-amir-medium.onnx"
        const val ESPEAK_DIR = "espeak-ng-data"
    }
}
