package com.aicallscreening.mobile.gemini

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aicallscreening.common.AudioConfig
import com.aicallscreening.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone Gemini Live API test harness. Bypasses the watch + call pipeline
 * entirely: connects directly and streams audio to Gemini, showing every event
 * (connection, setup, live transcription, verdicts, errors) on screen.
 *
 * Two sources: "Run test (mic)" streams THIS phone's microphone so you can speak
 * and watch your words + the scam verdict appear live; "Test clip" streams the
 * bundled demo PCM instead (useful when no mic is available, e.g. some emulators).
 */
class GeminiTestActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val client = GeminiLiveClient(
        onHighRisk = { appendLog("🚨 HIGH RISK callback fired: ${it.reason}") },
        onLog = { appendLog(it) },
    )
    private var testJob: Job? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMicStreaming()
            } else {
                appendLog("⚠️ Microphone permission denied — cannot record your voice")
            }
        }

    private fun appendLog(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        _log.update { (it + stamped).takeLast(500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val status by client.state.collectAsState()
                    val lines by log.collectAsState()
                    GeminiTestScreen(
                        status = status.status,
                        heard = status.callTranscript,
                        verdict = status.latestVerdict?.let { "${it.risk} — ${it.reason}" },
                        lines = lines,
                        onRunMic = { runMicTest() },
                        onRunClip = { runClipTest() },
                        onStop = { stopTest() },
                        onClear = { _log.value = emptyList() },
                    )
                }
            }
        }
    }

    /** Primary path: stream this phone's live microphone so the user can speak. */
    private fun runMicTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startMicStreaming()
        } else {
            appendLog("Requesting microphone permission…")
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMicStreaming() {
        testJob?.cancel()
        _log.value = emptyList()
        appendLog("=== Live mic test — connecting, then speak ===")
        client.connect()
        testJob = scope.launch {
            if (!awaitReady()) return@launch
            appendLog("🎤 Recording — speak now. Your words appear above; pause for a verdict.")
            streamMicrophone()
        }
    }

    /** Secondary path: stream the bundled demo clip (no mic needed). */
    private fun runClipTest() {
        testJob?.cancel()
        _log.value = emptyList()
        appendLog("=== Demo-clip test — connecting ===")
        client.connect()
        testJob = scope.launch {
            if (!awaitReady()) return@launch
            val clip = resources.openRawResource(R.raw.scam_demo_clip).use { it.readBytes() }
            appendLog("Streaming ${clip.size} B clip in ${AudioConfig.CHUNK_SIZE_BYTES}-byte chunks…")
            var offset = 0
            while (offset < clip.size && isActive) {
                val end = minOf(offset + AudioConfig.CHUNK_SIZE_BYTES, clip.size)
                client.sendAudioChunk(clip.copyOfRange(offset, end))
                offset = end
                delay(AudioConfig.CHUNK_DURATION_MS.toLong())
            }
            appendLog("Finished streaming ${clip.size} B. Signaling end-of-turn…")
            client.endAudioStream()
            appendLog("Waiting for model transcription + verdict…")
        }
    }

    /** Waits for a terminal setup outcome; logs and returns false unless Ready. */
    private suspend fun awaitReady(): Boolean {
        val outcome = withTimeoutOrNull(20_000) {
            client.state.first { s ->
                s.status == "Ready" ||
                    s.status.startsWith("Error") ||
                    s.status == "Disconnected" ||
                    s.status == "Missing GEMINI_API_KEY"
            }
        }
        if (outcome?.status != "Ready") {
            appendLog(
                "⚠️ Setup did not reach Ready within 20s (status=${client.state.value.status}). " +
                    "If the WebSocket opened but no setupComplete arrived, the AQ. ephemeral " +
                    "token is likely locked to a different model than GEMINI_LIVE_MODEL.",
            )
            return false
        }
        return true
    }

    private suspend fun streamMicrophone() {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, AudioConfig.CHUNK_SIZE_BYTES)
        @Suppress("MissingPermission") // permission is checked in runMicTest() before we get here
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            appendLog("⚠️ Mic unavailable (AudioRecord not initialized). On an emulator, enable " +
                "\"Virtual microphone uses host audio input\" in Extended controls → Microphone.")
            recorder.release()
            return
        }
        val buffer = ByteArray(AudioConfig.CHUNK_SIZE_BYTES)
        recorder.startRecording()
        var micChunks = 0L
        var maxPeak = 0
        try {
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) break
                val peak = peakAmplitude(buffer, read)
                if (peak > maxPeak) maxPeak = peak
                micChunks++
                // Surface the actual signal level so a dead mic (all silence) is
                // obvious on screen, independent of whether bytes are being sent.
                if (micChunks % 15L == 1L) {
                    val pct = peak * 100 / Short.MAX_VALUE
                    val diag = if (maxPeak < SILENCE_THRESHOLD) {
                        "SILENT — no sound reaching the mic (emulator host-audio off, or muted)"
                    } else {
                        "capturing audio ✓"
                    }
                    appendLog("🎙️ mic level: peak=$peak/${Short.MAX_VALUE} (${pct}%) — $diag")
                }
                client.sendAudioChunk(if (read == buffer.size) buffer else buffer.copyOf(read))
            }
        } finally {
            recorder.stop()
            recorder.release()
            appendLog("🎙️ mic stopped. Loudest sample this session: $maxPeak/${Short.MAX_VALUE}" +
                if (maxPeak < SILENCE_THRESHOLD) " → the mic never heard anything." else "")
        }
    }

    /** Loudest 16-bit sample magnitude in the little-endian PCM buffer. */
    private fun peakAmplitude(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
            val amp = kotlin.math.abs(sample.toInt())
            if (amp > peak) peak = amp
            i += 2
        }
        return peak
    }

    private fun stopTest() {
        testJob?.cancel()
        // Flush the current turn so the model emits a final verdict for what was
        // said, but keep the socket open so that verdict can still arrive.
        client.endAudioStream()
        appendLog("=== Stopped recording — flushing for final verdict ===")
    }

    override fun onDestroy() {
        testJob?.cancel()
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        // 16-bit peak below this (~1% of full scale) counts as effective silence.
        const val SILENCE_THRESHOLD = 300
    }
}

@Composable
private fun GeminiTestScreen(
    status: String,
    heard: String,
    verdict: String?,
    lines: List<String>,
    onRunMic: () -> Unit,
    onRunClip: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Gemini API Test", style = MaterialTheme.typography.headlineSmall)
        Text("Status: $status", style = MaterialTheme.typography.bodyMedium)

        Text("You said:", style = MaterialTheme.typography.labelLarge)
        Text(
            text = heard.ifBlank { "— speak after tapping Run test (mic) —" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            "Verdict: ${verdict ?: "—"}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRunMic) { Text("🎤 Run test (mic)") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRunClip) { Text("Test clip") }
            OutlinedButton(onClick = onClear) { Text("Clear log") }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when {
                        line.contains("ERROR") || line.contains("FAILURE") || line.contains("⚠️") ->
                            MaterialTheme.colorScheme.error
                        line.contains("VERDICT") || line.contains("Setup complete") || line.contains("🚨") ->
                            MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
