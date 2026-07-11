package com.aicallscreening.mobile.analysis

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.aicallscreening.common.AnalyzerEngine
import com.aicallscreening.common.AudioWindowBuffer
import com.aicallscreening.common.EngineSelector
import com.aicallscreening.common.ScamVerdict
import com.aicallscreening.common.VerdictParser
import com.aicallscreening.mobile.BuildConfig
import com.aicallscreening.mobile.gemini.GeminiLiveClient
import com.aicallscreening.mobile.gemma.GemmaModelManager
import com.aicallscreening.mobile.gemma.LiteRtGemmaAnalyzer
import com.aicallscreening.mobile.gemma.MockGemmaAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class AnalyzerRouterState(
    val engine: AnalyzerEngine = AnalyzerEngine.CLOUD,
    val status: String = "Idle",
    val latestVerdict: ScamVerdict? = null,
    val transcript: String? = null,
    val alertTriggered: Boolean = false,
    val modelStatus: String? = null,
)

/**
 * Routes call audio to the cloud Gemini Live engine when online, and falls back
 * to the on-device Gemma 4 E2B engine when the network is unavailable or the
 * cloud connection fails. Recovers to cloud when connectivity returns.
 */
class AnalyzerRouter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onHighRisk: (ScamVerdict) -> Unit,
) {
    private val _state = MutableStateFlow(AnalyzerRouterState())
    val state: StateFlow<AnalyzerRouterState> = _state.asStateFlow()

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val modelManager = GemmaModelManager(context)
    private val geminiClient = GeminiLiveClient(onHighRisk = ::handleHighRisk)

    private lateinit var selector: EngineSelector
    private val windowBuffer = AudioWindowBuffer()
    private val onDeviceAnalyzer: ScamAudioAnalyzer =
        if (BuildConfig.USE_MOCK_INFERENCE) MockGemmaAnalyzer()
        else LiteRtGemmaAnalyzer(context, modelManager)

    private val alertSent = AtomicBoolean(false)
    private val inferenceInFlight = AtomicBoolean(false)
    private var started = false

    private var cloudObserverJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { onConnectivity(true) }
        }

        override fun onLost(network: Network) {
            scope.launch { onConnectivity(false) }
        }
    }

    fun start() {
        if (started) return
        started = true
        alertSent.set(false)
        val online = isOnlineNow()
        selector = EngineSelector(startOnline = online)
        observeCloud()
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { Log.w(TAG, "Failed to register network callback", it) }

        if (selector.engine == AnalyzerEngine.CLOUD) {
            activateCloud()
        } else {
            activateOnDevice("No internet; using on-device model")
        }
    }

    fun onAudioChunk(chunk: ByteArray) {
        when (currentEngine()) {
            AnalyzerEngine.CLOUD -> geminiClient.sendAudioChunk(chunk)
            AnalyzerEngine.ON_DEVICE -> {
                val window = windowBuffer.append(chunk) ?: return
                analyzeOnDevice(window)
            }
        }
    }

    fun downloadModel() {
        scope.launch {
            _state.update { it.copy(modelStatus = "Downloading model 0%") }
            modelManager.downloadModel().collect { downloadState ->
                when (downloadState) {
                    is GemmaModelManager.DownloadState.InProgress ->
                        _state.update {
                            it.copy(modelStatus = "Downloading model ${(downloadState.fraction * 100).toInt()}%")
                        }

                    is GemmaModelManager.DownloadState.Completed -> {
                        _state.update { it.copy(modelStatus = "Model ready") }
                        if (currentEngine() == AnalyzerEngine.ON_DEVICE) {
                            prepareOnDevice()
                        }
                    }

                    is GemmaModelManager.DownloadState.Error ->
                        _state.update { it.copy(modelStatus = "Model download failed: ${downloadState.throwable.message}") }
                }
            }
        }
    }

    fun stop() {
        started = false
        runCatching {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }.onFailure { Log.w(TAG, "Failed to unregister network callback", it) }
        cloudObserverJob?.cancel()
        cloudObserverJob = null
        geminiClient.disconnect()
        onDeviceAnalyzer.close()
        windowBuffer.clear()
        _state.value = AnalyzerRouterState()
    }

    private fun observeCloud() {
        cloudObserverJob?.cancel()
        cloudObserverJob = scope.launch {
            geminiClient.state.collect { cloudState ->
                if (currentEngine() != AnalyzerEngine.CLOUD) {
                    return@collect
                }
                _state.update {
                    it.copy(
                        status = cloudState.status,
                        latestVerdict = cloudState.latestVerdict ?: it.latestVerdict,
                        transcript = cloudState.latestVerdict?.reason ?: cloudState.transcript ?: it.transcript,
                        alertTriggered = cloudState.alertTriggered || it.alertTriggered,
                    )
                }
                if (isCloudFailure(cloudState.status)) {
                    Log.w(TAG, "Cloud engine failed (${cloudState.status}); falling back on-device")
                    selector.onCloudError()
                    activateOnDevice("Cloud unavailable; using on-device model")
                }
            }
        }
    }

    private suspend fun onConnectivity(online: Boolean) {
        if (!started) return
        val previous = currentEngine()
        val next = selector.onConnectivityChanged(online)
        if (next == previous) return
        when (next) {
            AnalyzerEngine.CLOUD -> activateCloud()
            AnalyzerEngine.ON_DEVICE -> activateOnDevice("No internet; using on-device model")
        }
    }

    private fun activateCloud() {
        _state.update { it.copy(engine = AnalyzerEngine.CLOUD, status = "Connecting") }
        windowBuffer.clear()
        geminiClient.connect()
    }

    private fun activateOnDevice(reason: String) {
        geminiClient.disconnect()
        windowBuffer.clear()
        _state.update { it.copy(engine = AnalyzerEngine.ON_DEVICE, status = reason) }
        prepareOnDevice()
    }

    private fun prepareOnDevice() {
        if (!BuildConfig.USE_MOCK_INFERENCE && !modelManager.isModelAvailable) {
            _state.update {
                it.copy(
                    status = "On-device model not downloaded",
                    modelStatus = it.modelStatus ?: "Tap to download the offline model",
                )
            }
            return
        }
        scope.launch {
            val ready = onDeviceAnalyzer.prepare()
            _state.update {
                it.copy(status = if (ready) "On-device ready" else "On-device unavailable")
            }
        }
    }

    private fun analyzeOnDevice(window: ByteArray) {
        if (!onDeviceAnalyzer.isReady) return
        if (!inferenceInFlight.compareAndSet(false, true)) {
            // Drop windows while a previous inference is still running to stay near real-time.
            return
        }
        scope.launch {
            try {
                val verdict = onDeviceAnalyzer.analyze(window) ?: return@launch
                _state.update {
                    it.copy(
                        status = "On-device analyzing",
                        latestVerdict = verdict,
                        transcript = verdict.reason,
                    )
                }
                if (VerdictParser.isHighRisk(verdict)) {
                    handleHighRisk(verdict)
                }
            } finally {
                inferenceInFlight.set(false)
            }
        }
    }

    private fun handleHighRisk(verdict: ScamVerdict) {
        if (alertSent.compareAndSet(false, true)) {
            _state.update { it.copy(alertTriggered = true) }
            onHighRisk(verdict)
        }
    }

    private fun currentEngine(): AnalyzerEngine =
        if (::selector.isInitialized) selector.engine else AnalyzerEngine.CLOUD

    private fun isOnlineNow(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isCloudFailure(status: String): Boolean =
        status.startsWith("Error") || status == "Missing GEMINI_API_KEY"

    private companion object {
        const val TAG = "AnalyzerRouter"
    }
}
