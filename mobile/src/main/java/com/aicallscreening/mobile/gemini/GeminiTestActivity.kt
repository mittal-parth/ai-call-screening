package com.aicallscreening.mobile.gemini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.aicallscreening.common.AudioConfig
import com.aicallscreening.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Standalone Gemini Live API test harness. Bypasses the watch + call pipeline:
 * connects directly, streams the bundled demo PCM clip, and shows every event
 * (connection, setup, raw server frames, audio sent, verdicts, errors) on screen.
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
                        verdict = status.latestVerdict?.let { "${it.risk} — ${it.reason}" },
                        lines = lines,
                        onRun = { runTest() },
                        onStop = { stopTest() },
                        onClear = { _log.value = emptyList() },
                    )
                }
            }
        }
    }

    private fun runTest() {
        testJob?.cancel()
        _log.value = emptyList()
        appendLog("=== Gemini standalone test started ===")
        client.connect()
        testJob = scope.launch {
            // Wait for a terminal setup outcome so we can report exactly what happened.
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
                return@launch
            }

            val clip = resources.openRawResource(R.raw.scam_demo_clip).use { it.readBytes() }
            appendLog("Streaming ${clip.size} B clip in ${AudioConfig.CHUNK_SIZE_BYTES}-byte chunks (~${AudioConfig.CHUNK_DURATION_MS}ms each)…")
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

    private fun stopTest() {
        testJob?.cancel()
        client.disconnect()
        appendLog("=== Test stopped ===")
    }

    override fun onDestroy() {
        testJob?.cancel()
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }
}

@Composable
private fun GeminiTestScreen(
    status: String,
    verdict: String?,
    lines: List<String>,
    onRun: () -> Unit,
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
        Text(
            "Verdict: ${verdict ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun) { Text("Run test") }
            OutlinedButton(onClick = onStop) { Text("Stop") }
            OutlinedButton(onClick = onClear) { Text("Clear") }
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
