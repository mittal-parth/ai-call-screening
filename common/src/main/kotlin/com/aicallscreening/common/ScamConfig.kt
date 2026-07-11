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

    /**
     * On-device (offline) fallback: Gemma 4 E2B multimodal model served via
     * LiteRT-LM. The model file is Apache-2.0 and downloaded on demand.
     */
    const val GEMMA_MODEL_FILE = "gemma4_e2b.litertlm"
    const val GEMMA_MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    /** Length of each buffered audio window analyzed on-device (seconds). */
    const val OFFLINE_WINDOW_SECONDS = 12

    /** Gemma 4 E2B accepts audio clips up to 30 seconds per inference. */
    const val OFFLINE_MAX_CLIP_SECONDS = 30

    /** Practical RAM floor for running Gemma 4 E2B on-device (~8 GB). */
    const val OFFLINE_MIN_RAM_BYTES = 8L * 1024 * 1024 * 1024

    /** A partial `.litertlm` download smaller than this is treated as invalid (~2.58 GB model). */
    const val GEMMA_MODEL_MIN_VALID_BYTES = 2L * 1024 * 1024 * 1024

    /** Byte length of one on-device audio window at 16 kHz / 16-bit / mono. */
    val OFFLINE_WINDOW_BYTES: Int =
        AudioConfig.SAMPLE_RATE_HZ *
            AudioConfig.BYTES_PER_SAMPLE *
            AudioConfig.CHANNEL_COUNT *
            OFFLINE_WINDOW_SECONDS
}
