package com.aicallscreening.common

object VerdictParser {
    private val verdictRegex =
        Regex("""SCAM_RISK:\s*(low|medium|high)\s*\|\s*(.+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ScamVerdict? {
        val normalized = text.lines().map { it.trim() }.firstOrNull { it.contains(ScamConfig.VERDICT_PREFIX) }
            ?: return null

        val match = verdictRegex.find(normalized) ?: return null
        val risk = when (match.groupValues[1].lowercase()) {
            "low" -> ScamRisk.LOW
            "medium" -> ScamRisk.MEDIUM
            "high" -> ScamRisk.HIGH
            else -> return null
        }
        return ScamVerdict(risk = risk, reason = match.groupValues[2].trim())
    }

    fun isHighRisk(verdict: ScamVerdict): Boolean =
        verdict.risk.ordinal >= ScamConfig.HIGH_RISK_THRESHOLD.ordinal
}
