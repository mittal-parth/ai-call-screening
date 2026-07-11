package com.aicallscreening.wear

import android.Manifest
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
        if (intent.getBooleanExtra(CallListenerService.EXTRA_AUTO_START, false)) {
            startCapture()
        }

        setContent {
            MaterialTheme {
                val state by AudioCaptureService.state.collectAsState()
                WatchMonitorScreen(
                    state = state,
                    onStart = { startCapture() },
                    onStop = { AudioCaptureService.stop(this) },
                )
            }
        }
    }

    private fun startCapture() {
        requestPermissionsIfNeeded()
        AudioCaptureService.start(this)
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
}

@Composable
private fun WatchMonitorScreen(
    state: CaptureState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
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
            text = "Bytes sent: ${state.bytesSent}",
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onStart,
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
