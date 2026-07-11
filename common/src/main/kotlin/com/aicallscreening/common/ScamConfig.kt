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
    const val GEMINI_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    const val VERDICT_PREFIX = "SCAM_RISK:"
}
