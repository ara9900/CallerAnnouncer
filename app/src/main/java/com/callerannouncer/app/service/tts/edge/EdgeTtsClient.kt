package com.callerannouncer.app.service.tts.edge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Free online Persian TTS via Microsoft Edge Read Aloud WebSocket API.
 * Protocol aligned with the official edge-tts Python library (v7.x).
 */
class EdgeTtsClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(
        text: String,
        voice: String,
        locale: String = "fa-IR",
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
    ): ByteArray = withContext(Dispatchers.IO) {
        val cleaned = removeIncompatibleCharacters(text)
        if (cleaned.isBlank()) throw IllegalArgumentException("Empty text")

        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext synthesizeOnce(cleaned, voice, locale, rate, pitch)
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Edge TTS attempt ${attempt + 1}/$MAX_ATTEMPTS failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Edge TTS failed")
    }

    private suspend fun synthesizeOnce(
        text: String,
        voice: String,
        locale: String,
        rate: Float,
        pitch: Float,
    ): ByteArray = suspendCancellableCoroutine { continuation ->
        val connectionId = newConnectionId()
        val requestId = newConnectionId()
        val secMsGec = generateSecMsGec()
        val url = buildString {
            append(WSS_URL)
            append("&ConnectionId=").append(connectionId)
            append("&Sec-MS-GEC=").append(secMsGec)
            append("&Sec-MS-GEC-Version=1-").append(CHROMIUM_FULL_VERSION)
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("Sec-WebSocket-Version", "13")
            .header("Cookie", "muid=${generateMuid()};")
            .build()

        val audioBuffer = ByteArrayOutputStream()
        var finished = false

        fun completeSuccess(data: ByteArray) {
            if (finished) return
            finished = true
            if (data.isEmpty()) {
                continuation.resumeWithException(IllegalStateException("Edge TTS returned no audio"))
            } else {
                Log.i(TAG, "Edge TTS ok bytes=${data.size} voice=$voice")
                continuation.resume(data)
            }
        }

        fun completeError(error: Throwable) {
            if (finished) return
            finished = true
            continuation.resumeWithException(error)
        }

        val webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Edge TTS connected voice=$voice")
                    webSocket.send(buildConfigMessage())
                    webSocket.send(
                        buildSsmlMessage(
                            requestId = requestId,
                            ssml = buildSsml(text, voice, locale, rate, pitch),
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val path = parseTextPath(text)
                    when (path) {
                        "turn.end" -> {
                            webSocket.close(1000, "done")
                            completeSuccess(audioBuffer.toByteArray())
                        }
                        "response", "turn.start", "audio.metadata" -> Unit
                        else -> Log.w(TAG, "Edge TTS text path=$path body=${text.take(200)}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val packet = bytes.toByteArray()
                    if (packet.size < 2) return
                    val headerLength =
                        ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
                    if (headerLength <= 0 || headerLength + 2 > packet.size) return

                    val headerText = String(packet, 2, headerLength, Charsets.UTF_8)
                    val headers = parseHeaders(headerText)
                    val path = headers["Path"] ?: return
                    if (path != "audio") return

                    val contentType = headers["Content-Type"]
                    val bodyOffset = headerLength + 2
                    val bodySize = packet.size - bodyOffset
                    if (bodySize <= 0) {
                        if (contentType == null) return
                        return
                    }
                    if (contentType != null && contentType != "audio/mpeg") return
                    audioBuffer.write(packet, bodyOffset, bodySize)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val details = response?.let { "HTTP ${it.code} ${it.message}" } ?: "no response"
                    Log.e(TAG, "Edge TTS WebSocket failed $details", t)
                    completeError(IllegalStateException("Edge TTS connection failed ($details)", t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!finished) {
                        if (audioBuffer.size() > 0) {
                            completeSuccess(audioBuffer.toByteArray())
                        } else {
                            completeError(
                                IllegalStateException("Edge TTS closed without audio: $code $reason"),
                            )
                        }
                    }
                }
            },
        )

        continuation.invokeOnCancellation {
            webSocket.cancel()
        }
    }

    private fun buildConfigMessage(): String {
        val timestamp = edgeTimestamp()
        return buildString {
            append("X-Timestamp:").append(timestamp).append("\r\n")
            append("Content-Type:application/json; charset=utf-8\r\n")
            append("Path:speech.config\r\n\r\n")
            append(CONFIG_JSON)
            append("\r\n")
        }
    }

    private fun buildSsmlMessage(requestId: String, ssml: String): String {
        val timestamp = edgeTimestamp()
        return buildString {
            append("X-RequestId:").append(requestId).append("\r\n")
            append("Content-Type:application/ssml+xml\r\n")
            append("X-Timestamp:").append(timestamp).append("Z\r\n")
            append("Path:ssml\r\n\r\n")
            append(ssml)
        }
    }

    private fun buildSsml(
        text: String,
        voiceId: String,
        locale: String,
        rate: Float,
        pitch: Float,
    ): String {
        val voice = toEdgeVoiceName(voiceId)
        val ratePercent = formatProsodyPercent(rate)
        val pitchHz = formatProsodyHz(pitch)
        val escaped = escapeSsml(text)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='$pitchHz' rate='$ratePercent' volume='+0%'>" +
            escaped +
            "</prosody></voice></speak>"
    }

    /** edge-tts transforms short voice ids into the long Microsoft voice name. */
    private fun toEdgeVoiceName(voiceId: String): String {
        val match = VOICE_ID_PATTERN.matchEntire(voiceId) ?: return voiceId
        var lang = match.groupValues[1]
        var region = match.groupValues[2]
        var name = match.groupValues[3]
        val dash = name.indexOf('-')
        if (dash >= 0) {
            region = "$region-${name.substring(0, dash)}"
            name = name.substring(dash + 1)
        }
        return "Microsoft Server Speech Text to Speech Voice ($lang-$region, $name)"
    }

    private fun parseTextPath(message: String): String? = parseHeaders(message)["Path"]

    private fun parseHeaders(headerBlock: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val bodyStart = headerBlock.indexOf("\r\n\r\n")
        val lines = if (bodyStart >= 0) {
            headerBlock.substring(0, bodyStart)
        } else {
            headerBlock
        }
        for (line in lines.split("\r\n")) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            headers[key] = value
        }
        return headers
    }

    private fun escapeSsml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private fun removeIncompatibleCharacters(text: String): String = buildString(text.length) {
        for (ch in text) {
            val code = ch.code
            append(
                when {
                    code in 0..8 || code in 11..12 || code in 14..31 -> ' '
                    else -> ch
                },
            )
        }
    }.trim()

    private fun formatProsodyPercent(rate: Float): String {
        val percent = ((rate.coerceIn(0.5f, 2.0f) - 1.0f) * 100f).toInt()
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    private fun formatProsodyHz(pitch: Float): String {
        val hz = ((pitch.coerceIn(0.5f, 2.0f) - 1.0f) * 50f).toInt()
        return if (hz >= 0) "+${hz}Hz" else "${hz}Hz"
    }

    private fun edgeTimestamp(): String {
        val format = SimpleDateFormat(
            "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
            Locale.US,
        )
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    private fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + WIN_EPOCH_OFFSET
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val input = "${ticks}$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun generateMuid(): String = buildString(32) {
        repeat(32) {
            append("0123456789ABCDEF"[Random.nextInt(16)])
        }
    }

    private fun newConnectionId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        private const val TAG = "EdgeTtsClient"
        private const val MAX_ATTEMPTS = 2
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val WIN_EPOCH_OFFSET = 11_644_473_600L
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
                "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
        private const val CONFIG_JSON =
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
        private val VOICE_ID_PATTERN =
            Regex("^([a-z]{2,})-([A-Z]{2,})-(.+Neural)$")
    }
}
