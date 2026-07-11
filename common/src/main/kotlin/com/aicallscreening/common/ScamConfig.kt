package com.aicallscreening.common

enum class ScamRisk {
    LOW,
    MEDIUM,
    HIGH,
}

data class ScamVerdict(
    val risk: ScamRisk,
    val reason: String,
)

object ScamConfig {
    val HIGH_RISK_THRESHOLD = ScamRisk.HIGH
    // Live model supporting audio input AND text output. Native-audio models
    // (…-native-audio-…) only emit audio and reject responseModalities:[TEXT],
    // which this app relies on to parse SCAM_RISK verdicts.
    const val GEMINI_LIVE_MODEL = "models/gemini-3.1-flash-live-preview"
    const val VERDICT_PREFIX = "SCAM_RISK:"
}
