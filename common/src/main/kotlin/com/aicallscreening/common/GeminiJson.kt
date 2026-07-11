package com.aicallscreening.common

object GeminiJson {
    private const val SYSTEM_INSTRUCTION = """
You are a real-time phone scam call monitor. Listen to the caller audio and assess scam risk.
After each caller turn, speak exactly one short sentence stating the risk, beginning with the
words "Scam risk" followed by the level (low, medium, or high) and then a brief reason.
For example: "Scam risk high, caller demands gift card payment."
Flag high risk for urgency, gift cards, wire transfers, OTP requests, impersonation, or threats.
Keep the sentence under 20 words. Do not say anything else.
"""

    fun buildSetupMessage(
        model: String = ScamConfig.GEMINI_LIVE_MODEL,
        systemInstruction: String = SYSTEM_INSTRUCTION.trim(),
    ): String {
        val escapedInstruction = escapeJson(systemInstruction)
        // Native-audio Live models (e.g. gemini-3.1-flash-live-preview) only support
        // AUDIO output; we enable outputAudioTranscription to get the spoken verdict
        // back as text. Only newlines are stripped — stripping spaces would corrupt
        // the system instruction text.
        return """
            {
              "setup": {
                "model": "$model",
                "generationConfig": {
                  "responseModalities": ["AUDIO"]
                },
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
                "systemInstruction": {
                  "parts": [
                    { "text": "$escapedInstruction" }
                  ]
                }
              }
            }
        """.trimIndent().replace("\n", "")
    }

    fun buildAudioMessage(base64Pcm: String): String =
        """{"realtimeInput":{"audio":{"data":"$base64Pcm","mimeType":"${AudioConfig.MIME_TYPE}"}}}"""

    /** Flushes cached audio and signals end-of-turn so the model responds (automatic VAD). */
    fun buildAudioStreamEndMessage(): String =
        """{"realtimeInput":{"audioStreamEnd":true}}"""

    fun extractTextFromServerMessage(json: String): String? {
        val textPattern = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return textPattern.findAll(json)
            .map { unescapeJson(it.groupValues[1]) }
            .joinToString("\n")
            .ifBlank { null }
    }

    /**
     * Extracts the model's spoken-response transcription fragment from
     * serverContent.outputTranscription.text (present when responseModalities is
     * AUDIO and outputAudioTranscription is enabled). Fragments stream incrementally.
     */
    fun extractOutputTranscription(json: String): String? {
        val pattern = Regex(""""outputTranscription"\s*:\s*\{[^{}]*?"text"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return pattern.find(json)?.groupValues?.get(1)?.let { unescapeJson(it) }?.ifBlank { null }
    }

    /**
     * Extracts the transcription of the incoming call audio (what's being said on
     * the call) from serverContent.inputTranscription.text. Requires
     * inputAudioTranscription enabled in setup. Fragments stream incrementally.
     */
    fun extractInputTranscription(json: String): String? {
        val pattern = Regex(""""inputTranscription"\s*:\s*\{[^{}]*?"text"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return pattern.find(json)?.groupValues?.get(1)?.let { unescapeJson(it) }?.ifBlank { null }
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
