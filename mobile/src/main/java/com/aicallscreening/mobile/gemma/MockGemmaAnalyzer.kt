package com.aicallscreening.mobile.gemma

import com.aicallscreening.common.ScamRisk
import com.aicallscreening.common.ScamVerdict
import com.aicallscreening.mobile.analysis.ScamAudioAnalyzer

/**
 * Stand-in analyzer for emulator/CI builds (USE_MOCK_INFERENCE) where the real
 * LiteRT-LM native runtime and multi-gigabyte model are unavailable. It performs
 * no real inference and simply reports a benign verdict so the offline routing
 * and UI can be exercised without a device.
 */
class MockGemmaAnalyzer : ScamAudioAnalyzer {
    override val isReady: Boolean = true

    override suspend fun prepare(): Boolean = true

    override suspend fun analyze(pcm: ByteArray): ScamVerdict =
        ScamVerdict(ScamRisk.LOW, "on-device mock: no inference performed")

    override fun close() = Unit
}
