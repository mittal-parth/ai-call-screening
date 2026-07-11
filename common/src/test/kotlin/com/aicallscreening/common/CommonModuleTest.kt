package com.aicallscreening.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeminiJsonTest {
    @Test
    fun buildSetupMessage_containsModelAndSystemInstruction() {
        val json = GeminiJson.buildSetupMessage()
        assertTrue(json.contains(""""model":"models/gemini-2.5-flash-native-audio-preview-12-2025""""))
        assertTrue(json.contains("responseModalities"))
        assertTrue(json.contains("SCAM_RISK"))
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

    @Test
    fun parse_worksOnGemmaStyleOutputWithLeadingText() {
        val verdict = VerdictParser.parse(
            "Here is my assessment.\nSCAM_RISK: medium | asks for one-time passcode",
        )
        assertNotNull(verdict)
        assertEquals(ScamRisk.MEDIUM, verdict!!.risk)
        assertEquals("asks for one-time passcode", verdict.reason)
    }
}

class ScamPromptTest {
    @Test
    fun sharedInstructionDrivesBothEngines() {
        assertTrue(ScamPrompt.SYSTEM_INSTRUCTION.contains("SCAM_RISK"))
        assertTrue(GeminiJson.buildSetupMessage().contains("SCAM_RISK"))
        assertTrue(ScamPrompt.ON_DEVICE_TURN_PROMPT.contains("SCAM_RISK"))
    }
}

class WavUtilsTest {
    @Test
    fun pcm16leToWav_writesValidHeader() {
        val pcm = PcmUtils.shortsToBytes(shortArrayOf(0, 100, -100, 32767))
        val wav = WavUtils.pcm16leToWav(pcm)

        assertEquals(WavUtils.HEADER_SIZE + pcm.size, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(wav.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(wav.copyOfRange(36, 40), Charsets.US_ASCII))

        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + pcm.size, buffer.getInt(4))
        assertEquals(1.toShort(), buffer.getShort(20)) // PCM format
        assertEquals(AudioConfig.CHANNEL_COUNT.toShort(), buffer.getShort(22))
        assertEquals(AudioConfig.SAMPLE_RATE_HZ, buffer.getInt(24))
        assertEquals(AudioConfig.ENCODING_BITS.toShort(), buffer.getShort(34))
        assertEquals(pcm.size, buffer.getInt(40))
    }
}

class AudioWindowBufferTest {
    @Test
    fun append_emitsExactlySizedWindow() {
        val buffer = AudioWindowBuffer(windowBytes = 10)
        assertNull(buffer.append(ByteArray(4)))
        assertNull(buffer.append(ByteArray(4)))
        val window = buffer.append(ByteArray(4))
        assertNotNull(window)
        assertEquals(10, window!!.size)
        assertEquals(2, buffer.pendingBytes)
    }

    @Test
    fun append_carriesOverRemainderForNextWindow() {
        val buffer = AudioWindowBuffer(windowBytes = 8)
        val first = buffer.append(ByteArray(20) { it.toByte() })
        assertNotNull(first)
        assertEquals(8, first!!.size)
        assertEquals(12, buffer.pendingBytes)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7), first)
    }

    @Test
    fun drain_returnsRemainderThenNull() {
        val buffer = AudioWindowBuffer(windowBytes = 100)
        buffer.append(ByteArray(30))
        val drained = buffer.drain()
        assertNotNull(drained)
        assertEquals(30, drained!!.size)
        assertNull(buffer.drain())
    }
}

class EngineSelectorTest {
    @Test
    fun startsOnCloudWhenOnline() {
        assertEquals(AnalyzerEngine.CLOUD, EngineSelector(startOnline = true).engine)
    }

    @Test
    fun startsOnDeviceWhenOffline() {
        assertEquals(AnalyzerEngine.ON_DEVICE, EngineSelector(startOnline = false).engine)
    }

    @Test
    fun onlineToOfflineToOnlineRoundTrip() {
        val selector = EngineSelector(startOnline = true)
        assertEquals(AnalyzerEngine.ON_DEVICE, selector.onConnectivityChanged(false))
        assertEquals(AnalyzerEngine.CLOUD, selector.onConnectivityChanged(true))
    }

    @Test
    fun cloudErrorFallsBackToOnDevice() {
        val selector = EngineSelector(startOnline = true)
        assertEquals(AnalyzerEngine.ON_DEVICE, selector.onCloudError())
    }
}
