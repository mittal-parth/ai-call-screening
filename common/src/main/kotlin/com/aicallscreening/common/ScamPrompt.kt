package com.aicallscreening.common

/**
 * Shared scam-detection instruction used by both the cloud Gemini Live path and
 * the offline on-device Gemma 4 E2B path so both engines produce the same
 * `SCAM_RISK:` verdict format that [VerdictParser] understands.
 */
object ScamPrompt {
    val SYSTEM_INSTRUCTION = """
You are a real-time phone scam call monitor. Listen to the caller audio and assess scam risk.
After each conversational turn, emit exactly one compact verdict line in this format:
SCAM_RISK: <low|medium|high> | <brief reason>
Flag high risk for urgency, gift cards, wire transfers, OTP requests, impersonation, or threats.
Keep reasons under 120 characters.
""".trim()

    /**
     * Per-window request sent alongside an audio clip when using the on-device
     * model. It restates the required output contract because a fresh, stateless
     * conversation is used for each audio window.
     */
    val ON_DEVICE_TURN_PROMPT = """
Analyze the attached call audio for scam indicators and reply with exactly one line:
SCAM_RISK: <low|medium|high> | <brief reason>
""".trim()
}
