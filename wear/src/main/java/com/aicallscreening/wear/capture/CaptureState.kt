package com.aicallscreening.wear.capture

data class CaptureState(
    val isCapturing: Boolean = false,
    val status: String = "Idle",
    val bytesSent: Long = 0,
    val isScamAlert: Boolean = false,
    /** Normalized input level (0..100) from the live mic, for UI feedback. */
    val inputLevel: Int = 0,
    /** True while capturing the bundled demo clip instead of the live mic. */
    val usingDebugClip: Boolean = false,
)
