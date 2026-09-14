package com.callerannouncer.app.service.tts

import android.content.Context
import android.media.AudioDeviceInfo
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
    private var secondaryPlayer: PcmAudioPlayer? = null

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

    suspend fun warmUp(): Boolean = ensureReady()

    suspend fun speak(
        text: String,
        speed: Float,
        repeatCount: Int,
        route: PlaybackRoute = PlaybackRoute.MEDIA,
        outputDevice: AudioDeviceInfo? = null,
    ): Boolean = speak(
        text = text,
        speed = speed,
        repeatCount = repeatCount,
        legs = listOf(PlaybackLeg(route, outputDevice)),
    )

    suspend fun speak(
        text: String,
        speed: Float,
        repeatCount: Int,
        legs: List<PlaybackLeg>,
    ): Boolean {
        if (text.isBlank() || legs.isEmpty()) return false
        if (!ensureReady()) return false

        val engine = tts ?: return false
        val player = audioPlayer ?: return false
        val normalizedSpeed = speed.coerceIn(0.6f, 1.6f)
        val times = repeatCount.coerceIn(1, 5)
        val primaryLeg = legs.first()
        val secondaryLeg = legs.getOrNull(1)

        return withContext(Dispatchers.IO) {
            try {
                player.resetCancellation()
                val secondary = secondaryLeg?.let {
                    (secondaryPlayer ?: PcmAudioPlayer(engine.sampleRate()).also {
                        secondaryPlayer = it
                    }).also { it.resetCancellation() }
                }
                repeat(times) { index ->
                    if (index > 0) {
                        Thread.sleep(250)
                        if (player.isStopped() || secondary?.isStopped() == true) {
                            Log.i(TAG, "speak cancelled before repeat $index")
                            return@withContext false
                        }
                    }
                    player.beginSession(primaryLeg.route, primaryLeg.outputDevice)
                    secondary?.beginSession(secondaryLeg!!.route, secondaryLeg.outputDevice)

                    val callback: (FloatArray) -> Int = { chunk ->
                        when {
                            player.isStopped() || secondary?.isStopped() == true -> 0
                            else -> {
                                val okPrimary = player.writeSamples(chunk)
                                val okSecondary = secondary?.writeSamples(chunk) ?: true
                                if (okPrimary && okSecondary) 1 else 0
                            }
                        }
                    }

                    val audio = engine.generateWithCallback(
                        text = text,
                        sid = 0,
                        speed = normalizedSpeed,
                        callback = callback,
                    )

                    if (player.isStopped() || secondary?.isStopped() == true) {
                        Log.i(TAG, "speak cancelled during synthesis")
                        player.endSession()
                        secondary?.endSession()
                        return@withContext false
                    }

                    if (audio.samples.isEmpty()) {
                        Log.e(TAG, "Generated empty audio for text=$text")
                        player.endSession()
                        secondary?.endSession()
                        return@withContext false
                    }

                    val durationSec = audio.samples.size.toFloat() / engine.sampleRate()
                    Log.i(
                        TAG,
                        "Generated ${audio.samples.size} samples (~${"%.1f".format(durationSec)}s) legs=${legs.size}",
                    )

                    player.awaitPlayback(audio.samples.size)
                    secondary?.awaitPlayback(audio.samples.size)
                    player.endSession()
                    secondary?.endSession()

                    if (player.isStopped() || secondary?.isStopped() == true) {
                        Log.i(TAG, "speak cancelled during playback")
                        return@withContext false
                    }
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
        secondaryPlayer?.stop()
    }

    fun shutdown() {
        stop()
        tts?.release()
        tts = null
        audioPlayer?.release()
        audioPlayer = null
        secondaryPlayer?.release()
        secondaryPlayer = null
    }

    companion object {
        private const val TAG = "OfflinePersianTts"
        const val MODEL_FILE = "fa_IR-amir-medium.onnx"
        const val ESPEAK_DIR = "espeak-ng-data"
    }
}
