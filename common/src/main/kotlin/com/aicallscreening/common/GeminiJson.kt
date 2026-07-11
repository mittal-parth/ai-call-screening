package com.aicallscreening.common

object GeminiJson {
    private const val SYSTEM_INSTRUCTION = """
You are a real-time phone scam call monitor. Listen to the caller audio and assess scam risk.
After each conversational turn, emit exactly one compact verdict line in this format:
SCAM_RISK: <low|medium|high> | <brief reason>
Flag high risk for urgency, gift cards, wire transfers, OTP requests, impersonation, or threats.
Keep reasons under 120 characters.
"""

    fun buildSetupMessage(
        model: String = ScamConfig.GEMINI_LIVE_MODEL,
        systemInstruction: String = SYSTEM_INSTRUCTION.trim(),
    ): String {
        val escapedInstruction = escapeJson(systemInstruction)
        return """
            {
              "setup": {
                "model": "$model",
                "generationConfig": {
                  "responseModalities": ["TEXT"]
                },
                "systemInstruction": {
                  "parts": [
                    { "text": "$escapedInstruction" }
                  ]
                }
              }
            }
        """.trimIndent().replace("\n", "").replace(" ", "")
    }

    fun buildAudioMessage(base64Pcm: String): String =
        """{"realtimeInput":{"audio":{"data":"$base64Pcm","mimeType":"${AudioConfig.MIME_TYPE}"}}}"""

    fun extractTextFromServerMessage(json: String): String? {
        val textPattern = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return textPattern.findAll(json)
            .map { unescapeJson(it.groupValues[1]) }
            .joinToString("\n")
            .ifBlank { null }
    }

    fun isSetupComplete(json: String): Boolean = json.contains("setupComplete")

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun unescapeJson(value: String): String =
        value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}
