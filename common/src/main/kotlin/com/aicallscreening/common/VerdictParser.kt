package com.aicallscreening.common

object VerdictParser {
    private val verdictRegex =
        Regex("""SCAM_RISK:\s*(low|medium|high)\s*\|\s*(.+)""", RegexOption.IGNORE_CASE)

    // Lenient fallback for spoken transcriptions (AUDIO output), e.g.
    // "Scam risk high, caller demands gift card payment." — no colon/pipe.
    private val spokenRegex =
        Regex("""scam\s+risk\b[^a-z]*?(low|medium|high)\b[\s:.,;-]*(.*)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ScamVerdict? {
        // Strict text format: SCAM_RISK: <level> | <reason>
        text.lines().map { it.trim() }.firstOrNull { it.contains(ScamConfig.VERDICT_PREFIX) }
            ?.let { line -> verdictRegex.find(line)?.let { return it.toVerdict() } }

        // Spoken/transcribed fallback, scanned across the whole (accumulated) text.
        spokenRegex.find(text)?.let { return it.toVerdict() }

        return null
    }

    private fun MatchResult.toVerdict(): ScamVerdict? {
        val risk = when (groupValues[1].lowercase()) {
            "low" -> ScamRisk.LOW
            "medium" -> ScamRisk.MEDIUM
            "high" -> ScamRisk.HIGH
            else -> return null
        }
        return ScamVerdict(risk = risk, reason = groupValues[2].trim().ifBlank { "no reason given" })
    }

    fun isHighRisk(verdict: ScamVerdict): Boolean =
        verdict.risk.ordinal >= ScamConfig.HIGH_RISK_THRESHOLD.ordinal
}
