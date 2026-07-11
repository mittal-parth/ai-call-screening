package com.aicallscreening.wear.capture

data class CaptureState(
    val isCapturing: Boolean = false,
    val status: String = "Idle",
    val bytesSent: Long = 0,
    val isScamAlert: Boolean = false,
)
