package com.aicallscreening.common

enum class AnalyzerEngine {
    CLOUD,
    ON_DEVICE,
}

/**
 * Pure decision logic for choosing between the cloud Gemini Live engine and the
 * offline on-device Gemma engine. Kept free of Android dependencies so the
 * fallback transitions can be unit tested directly.
 *
 * Rules:
 * - Start on cloud when online, on-device when offline.
 * - Any cloud error (connection failure, missing key, unexpected disconnect)
 *   falls back to on-device.
 * - Losing connectivity falls back to on-device.
 * - Regaining connectivity recovers to cloud so we prefer the higher-quality
 *   engine whenever the network allows.
 */
class EngineSelector(startOnline: Boolean) {
    var engine: AnalyzerEngine = if (startOnline) AnalyzerEngine.CLOUD else AnalyzerEngine.ON_DEVICE
        private set

    var online: Boolean = startOnline
        private set

    fun onConnectivityChanged(isOnline: Boolean): AnalyzerEngine {
        online = isOnline
        engine = if (isOnline) AnalyzerEngine.CLOUD else AnalyzerEngine.ON_DEVICE
        return engine
    }

    fun onCloudError(): AnalyzerEngine {
        engine = AnalyzerEngine.ON_DEVICE
        return engine
    }
}
