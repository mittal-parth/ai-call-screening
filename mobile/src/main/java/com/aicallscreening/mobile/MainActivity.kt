package com.aicallscreening.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.aicallscreening.mobile.call.CallDetectionManager
import com.aicallscreening.mobile.gemini.GeminiTestActivity
import com.aicallscreening.mobile.monitor.MonitorService
import com.aicallscreening.mobile.monitor.MonitorState

class MainActivity : ComponentActivity() {
    private lateinit var callDetectionManager: CallDetectionManager

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callDetectionManager = CallDetectionManager(this)

        requestPermissionsIfNeeded()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by MonitorService.state.collectAsState()
                    MonitorScreen(
                        state = state,
                        onStart = { startMonitoring() },
                        onStop = { MonitorService.stop(this) },
                        onOpenGeminiTest = {
                            startActivity(Intent(this, GeminiTestActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasPhonePermission()) {
            callDetectionManager.start()
        }
    }

    override fun onStop() {
        callDetectionManager.stop()
        super.onStop()
    }

    private fun startMonitoring() {
        requestPermissionsIfNeeded()
        MonitorService.start(this)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
}

private data class RiskColors(val bg: Color, val fg: Color, val label: String)

private fun riskColorsFor(verdict: String?): RiskColors = when (verdict?.lowercase()) {
    "high" -> RiskColors(Color(0xFFB3261E), Color.White, "HIGH RISK")
    "medium" -> RiskColors(Color(0xFFF29900), Color.Black, "MEDIUM RISK")
    "low" -> RiskColors(Color(0xFF1E8E3E), Color.White, "LOW RISK")
    else -> RiskColors(Color(0xFF444746), Color.White, "LISTENING…")
}

@Composable
private fun MonitorScreen(
    state: MonitorState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenGeminiTest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scam Call Detector", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${state.connectionStatus} · ${if (state.isMonitoring) "monitoring" else "idle"} · ${state.bytesReceived / 1024} KB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Gemini verdict + reasoning
        val risk = riskColorsFor(state.latestVerdict)
        Card(colors = CardDefaults.cardColors(containerColor = risk.bg)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(risk.label, color = risk.fg, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(
                    "Gemini: ${state.latestReason ?: "waiting for audio…"}",
                    color = risk.fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Live call transcript (what's being said on the line)
        Text("Live call transcript", style = MaterialTheme.typography.titleSmall)
        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val scroll = rememberScrollState()
            LaunchedEffect(state.callTranscript) { scroll.animateScrollTo(scroll.maxValue) }
            Text(
                text = state.callTranscript.ifBlank { "Waiting for call audio…" },
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !state.isMonitoring, modifier = Modifier.weight(1f)) {
                Text("Start")
            }
            OutlinedButton(onClick = onStop, enabled = state.isMonitoring, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
            OutlinedButton(onClick = onOpenGeminiTest, modifier = Modifier.weight(1f)) {
                Text("API Test")
            }
        }
    }
}
