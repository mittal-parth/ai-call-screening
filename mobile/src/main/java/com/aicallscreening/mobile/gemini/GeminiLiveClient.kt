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
    val alertTriggered: Boolean = false,
)

class GeminiLiveClient(
    private val onHighRisk: (ScamVerdict) -> Unit,
    private val okHttpClient: OkHttpClient = defaultClient(),
) {
    private val _state = MutableStateFlow(GeminiConnectionState())
    val state: StateFlow<GeminiConnectionState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private val setupComplete = AtomicBoolean(false)
    private val alertSent = AtomicBoolean(false)

    fun connect() {
        disconnect()
        alertSent.set(false)
        setupComplete.set(false)
        _state.value = GeminiConnectionState(status = "Connecting")

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank()) {
            _state.value = GeminiConnectionState(status = "Missing GEMINI_API_KEY")
            return
        }

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
            return
        }
        val message = GeminiJson.buildAudioMessage(PcmUtils.bytesToBase64(pcmBytes))
        socket.send(message)
    }

    fun disconnect() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        setupComplete.set(false)
        _state.value = _state.value.copy(status = "Disconnected")
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Gemini WebSocket opened")
            _state.value = _state.value.copy(status = "Connected")
            webSocket.send(GeminiJson.buildSetupMessage())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (GeminiJson.isSetupComplete(text)) {
                setupComplete.set(true)
                _state.value = _state.value.copy(status = "Ready")
                Log.i(TAG, "Gemini setup complete")
                return
            }

            val extracted = GeminiJson.extractTextFromServerMessage(text) ?: return
            _state.value = _state.value.copy(transcript = extracted)
            val verdict = VerdictParser.parse(extracted)
            if (verdict != null) {
                _state.value = _state.value.copy(latestVerdict = verdict, status = "Analyzing")
                Log.i(TAG, "Verdict: ${verdict.risk} | ${verdict.reason}")
                if (VerdictParser.isHighRisk(verdict) && alertSent.compareAndSet(false, true)) {
                    _state.value = _state.value.copy(alertTriggered = true)
                    onHighRisk(verdict)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onMessage(webSocket, bytes.utf8())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Gemini WebSocket failure", t)
            _state.value = _state.value.copy(status = "Error: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Gemini WebSocket closed: $code $reason")
            setupComplete.set(false)
            _state.value = _state.value.copy(status = "Disconnected")
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
    }
}
