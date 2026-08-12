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
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Free online Persian TTS via Microsoft Edge Read Aloud WebSocket API.
 * Same service used by the edge-tts Python library — no API key required.
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
        if (text.isBlank()) throw IllegalArgumentException("Empty text")
        suspendCancellableCoroutine { continuation ->
            val connectionId = UUID.randomUUID().toString().replace("-", "")
            val secMsGec = generateSecMsGec()
            val url = buildString {
                append(WSS_URL)
                append("?ConnectionId=").append(connectionId)
                append("&TrustedClientToken=").append(TRUSTED_CLIENT_TOKEN)
                append("&Sec-MS-GEC=").append(secMsGec)
                append("&Sec-MS-GEC-Version=1-").append(CHROMIUM_FULL_VERSION)
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Origin", ORIGIN)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
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
                        val timestamp = Date().toString()
                        val configMessage = buildFrame(
                            headers = mapOf(
                                "Content-Type" to "application/json; charset=utf-8",
                                "Path" to "speech.config",
                                "X-Timestamp" to timestamp,
                            ),
                            body = CONFIG_JSON,
                        )
                        webSocket.send(configMessage)

                        val ssml = buildSsml(
                            text = text,
                            voice = voice,
                            locale = locale,
                            rate = rate,
                            pitch = pitch,
                        )
                        val ssmlMessage = buildFrame(
                            headers = mapOf(
                                "Content-Type" to "application/ssml+xml",
                                "Path" to "ssml",
                                "X-RequestId" to connectionId,
                                "X-Timestamp" to timestamp,
                            ),
                            body = ssml,
                        )
                        webSocket.send(ssmlMessage)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val path = parsePathHeader(text)
                        if (path == "turn.end") {
                            webSocket.close(1000, "done")
                            completeSuccess(audioBuffer.toByteArray())
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val packet = bytes.toByteArray()
                        if (packet.size < 2) return
                        val headerLength =
                            ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
                        val bodyOffset = headerLength + 2
                        if (packet.size > bodyOffset) {
                            audioBuffer.write(packet, bodyOffset, packet.size - bodyOffset)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Edge TTS WebSocket failed code=${response?.code}", t)
                        completeError(t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!finished && audioBuffer.size() > 0) {
                            completeSuccess(audioBuffer.toByteArray())
                        }
                    }
                },
            )

            continuation.invokeOnCancellation {
                webSocket.cancel()
            }
        }
    }

    private fun buildFrame(headers: Map<String, String>, body: String): String {
        val headerBlock = headers.entries.joinToString("\r\n") { (key, value) ->
            "$key: $value"
        }
        return "$headerBlock\r\n\r\n$body"
    }

    private fun parsePathHeader(message: String): String? {
        for (line in message.lineSequence()) {
            if (line.startsWith("Path:", ignoreCase = true)) {
                return line.substringAfter(':').trim()
            }
            if (line.isBlank()) break
        }
        return null
    }

    private fun buildSsml(
        text: String,
        voice: String,
        locale: String,
        rate: Float,
        pitch: Float,
    ): String {
        val ratePercent = formatProsodyPercent(rate)
        val pitchHz = formatProsodyHz(pitch)
        val escaped = escapeSsml(text)
        return """
            <speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>
              <voice name='$voice'>
                <prosody pitch='$pitchHz' rate='$ratePercent' volume='+0%'>
                  $escaped
                </prosody>
              </voice>
            </speak>
        """.trimIndent()
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

    private fun formatProsodyPercent(rate: Float): String {
        val percent = ((rate.coerceIn(0.5f, 2.0f) - 1.0f) * 100f).toInt()
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    private fun formatProsodyHz(pitch: Float): String {
        val hz = ((pitch.coerceIn(0.5f, 2.0f) - 1.0f) * 50f).toInt()
        return if (hz >= 0) "+${hz}Hz" else "${hz}Hz"
    }

    private fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000L + WIN_EPOCH_OFFSET
        ticks -= ticks % 300L
        ticks *= 10_000_000L
        val input = "${ticks.toString().substringBefore('.')}$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { byte -> "%02X".format(byte) }
    }

    private fun generateMuid(): String = buildString(32) {
        repeat(32) {
            append("0123456789ABCDEF"[Random.nextInt(16)])
        }
    }

    companion object {
        private const val TAG = "EdgeTtsClient"
        private const val WSS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL_VERSION = "131.0.2903.112"
        private const val WIN_EPOCH_OFFSET = 11_644_473_600L
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
        private const val CONFIG_JSON =
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
    }
}
