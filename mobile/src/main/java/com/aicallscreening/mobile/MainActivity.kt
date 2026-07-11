package com.aicallscreening.mobile

import android.Manifest
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aicallscreening.mobile.analysis.OfflineFallbackSettings
import com.aicallscreening.mobile.call.CallDetectionManager
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

        val fallbackSettings = OfflineFallbackSettings(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by MonitorService.state.collectAsState()
                    var offlineEnabled by remember { mutableStateOf(fallbackSettings.enabled) }
                    var forceOnDevice by remember { mutableStateOf(fallbackSettings.forceOnDevice) }
                    MonitorScreen(
                        state = state,
                        offlineEnabled = offlineEnabled,
                        forceOnDevice = forceOnDevice,
                        onStart = { startMonitoring() },
                        onStop = { MonitorService.stop(this) },
                        onDownloadModel = { MonitorService.downloadOfflineModel(this) },
                        onToggleOffline = {
                            offlineEnabled = it
                            fallbackSettings.enabled = it
                        },
                        onToggleForceOnDevice = {
                            forceOnDevice = it
                            fallbackSettings.forceOnDevice = it
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

@Composable
private fun MonitorScreen(
    state: MonitorState,
    offlineEnabled: Boolean,
    forceOnDevice: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDownloadModel: () -> Unit,
    onToggleOffline: (Boolean) -> Unit,
    onToggleForceOnDevice: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scam Call Detector", style = MaterialTheme.typography.headlineMedium)
        Text("Engine: ${if (state.engine == "on-device") "On-device (offline)" else "Cloud (Gemini Live)"}")
        Text("Connection: ${state.connectionStatus}")
        Text("Monitoring: ${if (state.isMonitoring) "yes" else "no"}")
        Text("Bytes received: ${state.bytesReceived}")
        Text("Live verdict: ${state.latestVerdict ?: "—"}")
        Text("Reason: ${state.latestReason ?: "—"}")
        state.modelStatus?.let { Text("Offline model: $it") }
        if (state.alertSent) {
            Text("ALERT SENT", color = MaterialTheme.colorScheme.error)
        }

        ToggleRow(
            label = "Enable offline fallback (beta)",
            checked = offlineEnabled,
            enabled = !state.isMonitoring,
            onCheckedChange = onToggleOffline,
        )
        ToggleRow(
            label = "Force on-device engine (testing)",
            checked = forceOnDevice,
            enabled = offlineEnabled && !state.isMonitoring,
            onCheckedChange = onToggleForceOnDevice,
        )
        Text(
            "Toggles apply when you next start monitoring.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isMonitoring,
        ) {
            Text("Start monitoring")
        }
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.isMonitoring,
        ) {
            Text("Stop monitoring")
        }
        Button(
            onClick = onDownloadModel,
            modifier = Modifier.fillMaxWidth(),
            enabled = offlineEnabled,
        ) {
            Text("Download offline model")
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
