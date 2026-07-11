package com.aicallscreening.mobile.gemini

import android.util.Log
import com.aicallscreening.common.GeminiJson
import com.aicallscreening.common.PcmUtils
import com.aicallscreening.common.ScamConfig
import com.aicallscreening.common.ScamVerdict
import com.aicallscreening.common.VerdictParser
import com.aicallscreening.mobile.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class GeminiConnectionState(
    val status: String = "Disconnected",
    val latestVerdict: ScamVerdict? = null,
    val transcript: String? = null,
    val callTranscript: String = "",
    val alertTriggered: Boolean = false,
)

class GeminiLiveClient(
    private val onHighRisk: (ScamVerdict) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val okHttpClient: OkHttpClient = defaultClient(),
) {
    private val _state = MutableStateFlow(GeminiConnectionState())
    val state: StateFlow<GeminiConnectionState> = _state.asStateFlow()

    /** Mirrors a message to both Logcat and the optional UI log sink. */
    private fun emit(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    private var webSocket: WebSocket? = null
    private val setupComplete = AtomicBoolean(false)
    private val alertSent = AtomicBoolean(false)
    private var audioChunksSent = 0L
    private var audioBytesSent = 0L
    private val transcriptBuffer = StringBuilder()
    private val callTranscriptBuffer = StringBuilder()

    fun connect() {
        disconnect()
        alertSent.set(false)
        setupComplete.set(false)
        transcriptBuffer.clear()
        callTranscriptBuffer.clear()
        audioChunksSent = 0L
        audioBytesSent = 0L
        _state.value = GeminiConnectionState(status = "Connecting")

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            _state.value = GeminiConnectionState(status = "Missing GEMINI_API_KEY")
            emit("ERROR: GEMINI_API_KEY is blank — set it in local.properties")
            return
        }

        emit("Connecting… key=${apiKey.take(4)}… model=${ScamConfig.GEMINI_LIVE_MODEL}")
        val request = Request.Builder()
            .url(
                "wss://generativelanguage.googleapis.com/ws/" +
                    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent" +
                    "?key=$apiKey",
            )
            .build()

        webSocket = okHttpClient.newWebSocket(request, Listener())
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        val socket = webSocket ?: return
        if (!setupComplete.get()) {
            Log.d(TAG, "Dropping ${pcmBytes.size}B audio chunk — setup not complete")
            return
        }
        val message = GeminiJson.buildAudioMessage(PcmUtils.bytesToBase64(pcmBytes))
        socket.send(message)
        audioChunksSent++
        audioBytesSent += pcmBytes.size
        // Throttle: one line every ~30 chunks (~3s at 100ms/chunk) to avoid flooding.
        if (audioChunksSent % 30L == 1L) {
            emit("→ Gemini audio: chunk #$audioChunksSent, ${pcmBytes.size}B this chunk, $audioBytesSent B total")
        }
    }

    /** Signals end-of-turn so the model flushes cached audio and responds. */
    fun endAudioStream() {
        val socket = webSocket ?: return
        if (!setupComplete.get()) return
        socket.send(GeminiJson.buildAudioStreamEndMessage())
        emit("→ audioStreamEnd (flush, expect response)")
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        setupComplete.set(false)
        _state.value = _state.value.copy(status = "Disconnected")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            emit("WebSocket opened (HTTP ${response.code}) — sending setup")
            _state.value = _state.value.copy(status = "Connected")
            webSocket.send(GeminiJson.buildSetupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (GeminiJson.isSetupComplete(text)) {
                setupComplete.set(true)
                _state.value = _state.value.copy(status = "Ready")
                emit("Setup complete ✓ — streaming audio now")
                return
            }

            var handled = false

            // Transcription of the call audio (what's being said on the line).
            GeminiJson.extractInputTranscription(text)?.let { fragment ->
                callTranscriptBuffer.append(fragment)
                if (callTranscriptBuffer.length > MAX_CALL_TRANSCRIPT) {
                    callTranscriptBuffer.delete(0, callTranscriptBuffer.length - MAX_CALL_TRANSCRIPT)
                }
                _state.value = _state.value.copy(callTranscript = callTranscriptBuffer.toString())
                emit("🗣️ call: $fragment")
                handled = true
            }

            // Model's spoken verdict (Gemini's reasoning), streamed incrementally.
            GeminiJson.extractOutputTranscription(text)?.let { fragment ->
                transcriptBuffer.append(fragment)
                emit("🤖 verdict: $fragment")
                val full = transcriptBuffer.toString()
                _state.value = _state.value.copy(transcript = full)
                VerdictParser.parse(full)?.let { verdict ->
                    _state.value = _state.value.copy(latestVerdict = verdict, status = "Analyzing")
                    emit("VERDICT: ${verdict.risk} | ${verdict.reason}")
                    if (VerdictParser.isHighRisk(verdict) && alertSent.compareAndSet(false, true)) {
                        _state.value = _state.value.copy(alertTriggered = true)
                        onHighRisk(verdict)
                    }
                }
                handled = true
            }

            // At verdict-turn boundaries, reset only the verdict buffer (keep the call log).
            if (text.contains("\"turnComplete\"")) {
                transcriptBuffer.clear()
                return
            }

            if (handled) return

            // Log meaningful control frames only; skip bulky audio blobs and
            // session-resumption keep-alives (pure noise).
            if (!text.contains("\"inlineData\"") && !text.contains("\"sessionResumptionUpdate\"")) {
                emit("RAW ← ${text.take(300)}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Gemini WebSocket failure", t)
            emit("FAILURE: ${t.message}${response?.let { " (HTTP ${it.code})" } ?: ""}")
            _state.value = _state.value.copy(status = "Error: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            emit("WebSocket closed: $code $reason")
            setupComplete.set(false)
            _state.value = _state.value.copy(status = "Disconnected")
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val MAX_CALL_TRANSCRIPT = 4000

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
    }
}
