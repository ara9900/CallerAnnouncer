package com.callerannouncer.app.data.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Disk cache of synthesized announcements so an online voice can be reused offline.
 *
 * Entries are keyed by the spoken text plus the voice parameters it was rendered with,
 * which means contact renames, prefix edits and voice changes simply produce new keys
 * instead of needing a contact-by-contact bookkeeping table.
 */
object TtsAudioCache {

    data class Stats(val entries: Int, val bytes: Long)

    fun get(context: Context, key: String): ByteArray? {
        val file = fileFor(context, key)
        if (!file.exists()) return null
        return try {
            // Touch so least-recently-used eviction keeps the announcements actually used.
            file.setLastModified(System.currentTimeMillis())
            file.readBytes().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Read failed for $key", e)
            null
        }
    }

    fun contains(context: Context, key: String): Boolean = fileFor(context, key).length() > 0

    fun put(context: Context, key: String, audio: ByteArray) {
        if (audio.isEmpty()) return
        try {
            val target = fileFor(context, key)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeBytes(audio)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            // Directory scans are expensive during bulk pre-caching, so check periodically.
            if (writesSinceTrim.incrementAndGet() >= TRIM_CHECK_INTERVAL) {
                writesSinceTrim.set(0)
                trimToLimit(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed for $key", e)
        }
    }

    fun keyOf(voiceId: String, text: String, rate: Float, pitch: Float): String {
        val normalized = buildString {
            append(KEY_VERSION)
            append('|')
            append(voiceId)
            append('|')
            append(oneDecimal(rate))
            append('|')
            append(oneDecimal(pitch))
            append('|')
            append(text.trim())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun stats(context: Context): Stats {
        val files = cacheDir(context).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?: return Stats(0, 0L)
        return Stats(entries = files.size, bytes = files.sumOf { it.length() })
    }

    fun clear(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Cache cleared")
    }

    private fun trimToLimit(context: Context) {
        val files = cacheDir(context).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_BYTES) return
            total -= file.length()
            file.delete()
        }
        Log.i(TAG, "Trimmed cache down to $total bytes")
    }

    private fun fileFor(context: Context, key: String) = File(cacheDir(context), "$key.mp3")

    private fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    private fun oneDecimal(value: Float): String = ((value * 10f).roundToInt() / 10f).toString()

    private val writesSinceTrim = AtomicInteger(0)

    private const val TAG = "TtsAudioCache"
    private const val DIR_NAME = "tts_voice_cache"
    private const val KEY_VERSION = "v1"
    private const val MAX_BYTES = 250L * 1024L * 1024L
    private const val TRIM_CHECK_INTERVAL = 200
}
