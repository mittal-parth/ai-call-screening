package com.aicallscreening.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiJsonTest {
    @Test
    fun buildSetupMessage_containsModelAndSystemInstruction() {
        val json = GeminiJson.buildSetupMessage()
        assertTrue(json.contains(ScamConfig.GEMINI_LIVE_MODEL))
        assertTrue(json.contains("responseModalities"))
        assertTrue(json.contains("systemInstruction"))
    }

    @Test
    fun buildAudioMessage_encodesMimeTypeAndData() {
        val message = GeminiJson.buildAudioMessage("YWJj")
        assertEquals(
            """{"realtimeInput":{"audio":{"data":"YWJj","mimeType":"audio/pcm;rate=16000"}}}""",
            message,
        )
    }

    @Test
    fun extractTextFromServerMessage_parsesModelTurn() {
        val json = """
            {"serverContent":{"modelTurn":{"parts":[{"text":"SCAM_RISK: high | urgent wire transfer"}]}}}
        """.trimIndent()
        assertEquals("SCAM_RISK: high | urgent wire transfer", GeminiJson.extractTextFromServerMessage(json))
    }

    @Test
    fun isSetupComplete_detectsFlag() {
        assertTrue(GeminiJson.isSetupComplete("""{"setupComplete":{}}"""))
        assertFalse(GeminiJson.isSetupComplete("""{"serverContent":{}}"""))
    }
}

class PcmUtilsTest {
    @Test
    fun shortsToBytes_roundTrips() {
        val samples = shortArrayOf(0, 1, -1, 32767, -32768)
        val bytes = PcmUtils.shortsToBytes(samples)
        assertArrayEquals(samples, PcmUtils.bytesToShorts(bytes))
    }

    @Test
    fun bytesToBase64_encodesChunk() {
        val encoded = PcmUtils.bytesToBase64(byteArrayOf(0x00, 0x01, 0x02))
        assertEquals("AAEC", encoded)
    }
}

class VerdictParserTest {
    @Test
    fun parse_extractsRiskAndReason() {
        val verdict = VerdictParser.parse("SCAM_RISK: high | Caller demands gift cards immediately")
        assertNotNull(verdict)
        assertEquals(ScamRisk.HIGH, verdict!!.risk)
        assertEquals("Caller demands gift cards immediately", verdict.reason)
    }

    @Test
    fun parse_ignoresNonVerdictLines() {
        assertNull(VerdictParser.parse("Everything looks normal so far."))
    }

    @Test
    fun isHighRisk_onlyForHigh() {
        val high = ScamVerdict(ScamRisk.HIGH, "test")
        val medium = ScamVerdict(ScamRisk.MEDIUM, "test")
        assertTrue(VerdictParser.isHighRisk(high))
        assertFalse(VerdictParser.isHighRisk(medium))
    }
}
