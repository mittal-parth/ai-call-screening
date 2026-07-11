package com.aicallscreening.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aicallscreening.wear.capture.AudioCaptureService
import com.aicallscreening.wear.capture.CaptureState

class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        maybeAutoStartCapture(intent)

        setContent {
            MaterialTheme {
                val state by AudioCaptureService.state.collectAsState()
                WatchMonitorScreen(
                    state = state,
                    onStart = { useDebugClip -> startCapture(useDebugClip) },
                    onStop = { AudioCaptureService.stop(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutoStartCapture(intent)
    }

    private fun maybeAutoStartCapture(intent: Intent?) {
        if (intent?.getBooleanExtra(CallListenerService.EXTRA_AUTO_START, false) == true) {
            // Auto-start (incoming call) uses the build-time default source.
            startCapture(useDebugClip = null)
        }
    }

    private fun startCapture(useDebugClip: Boolean?) {
        if (useDebugClip != true && !hasRecordAudioPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        AudioCaptureService.start(this, useDebugClip)
    }

    private fun requestPermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
private fun WatchMonitorScreen(
    state: CaptureState,
    onStart: (useDebugClip: Boolean) -> Unit,
    onStop: () -> Unit,
) {
    var useDebugClip by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                state.isScamAlert -> "SCAM RISK"
                state.isCapturing -> "Monitoring"
                else -> "Waiting"
            },
            textAlign = TextAlign.Center,
        )
        Text(
            text = state.status,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (state.usingDebugClip) {
                "Source: demo clip"
            } else {
                "Mic level: ${state.inputLevel}"
            },
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Bytes sent: ${state.bytesSent}",
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { useDebugClip = !useDebugClip },
            modifier = Modifier.fillMaxWidth(0.8f),
            enabled = !state.isCapturing,
        ) {
            Text(if (useDebugClip) "Source: Demo" else "Source: Mic")
        }
        Button(
            onClick = { onStart(useDebugClip) },
            modifier = Modifier.fillMaxWidth(0.8f),
            enabled = !state.isCapturing,
        ) {
            Text("Start")
        }
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(0.8f),
            enabled = state.isCapturing,
        ) {
            Text("Stop")
        }
    }
}
