package com.callerannouncer.app.service.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object TtsModelManager {

    private const val TAG = "TtsModelManager"
    private const val MODEL_FOLDER = "vits-piper-fa_IR-amir-medium"
    private const val MODEL_ARCHIVE = "$MODEL_FOLDER.tar.bz2"
    private const val DOWNLOAD_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-amir-medium.tar.bz2"

    private val downloadMutex = Mutex()

    private val _state = MutableStateFlow<TtsModelState>(TtsModelState.Idle)
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    fun modelDirectory(context: Context): File =
        File(context.filesDir, MODEL_FOLDER)

    fun isModelReady(context: Context): Boolean {
        val dir = modelDirectory(context)
        return File(dir, OfflinePersianTtsEngine.MODEL_FILE).exists() &&
            File(dir, "tokens.txt").exists() &&
            File(dir, OfflinePersianTtsEngine.ESPEAK_DIR).isDirectory
    }

    suspend fun ensureModelReady(context: Context): Boolean {
        if (isModelReady(context)) {
            _state.value = TtsModelState.Ready
            return true
        }
        return downloadMutex.withLock {
            if (isModelReady(context)) {
                _state.value = TtsModelState.Ready
                return@withLock true
            }
            downloadAndExtract(context)
        }
    }

    private suspend fun downloadAndExtract(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            _state.value = TtsModelState.Downloading(0)
            val archiveFile = File(context.cacheDir, MODEL_ARCHIVE)
            downloadFile(DOWNLOAD_URL, archiveFile) { progress ->
                _state.value = TtsModelState.Downloading(progress)
            }

            _state.value = TtsModelState.Extracting
            val targetRoot = context.filesDir
            extractTarBz2(archiveFile, targetRoot)
            archiveFile.delete()

            val ready = isModelReady(context)
            _state.value = if (ready) TtsModelState.Ready else TtsModelState.Error("فایل‌های مدل ناقص است")
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _state.value = TtsModelState.Error("دانلود مدل صدا ناموفق بود. اینترنت را بررسی کنید.")
            false
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            connect()
        }
        try {
            val total = connection.contentLengthLong.coerceAtLeast(1L)
            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            onProgress(100)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractTarBz2(archive: File, destRoot: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream())).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val outFile = File(destRoot, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { tar.copyTo(it) }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}

sealed interface TtsModelState {
    data object Idle : TtsModelState
    data class Downloading(val progress: Int) : TtsModelState
    data object Extracting : TtsModelState
    data object Ready : TtsModelState
    data class Error(val message: String) : TtsModelState
}
