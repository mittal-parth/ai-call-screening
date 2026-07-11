package com.aicallscreening.common

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps raw little-endian PCM16 samples in a canonical 44-byte WAV header.
 *
 * Some on-device inference paths expect a self-describing WAV container rather
 * than headerless PCM, so this is used when handing audio windows to the
 * offline analyzer.
 */
object WavUtils {
    const val HEADER_SIZE = 44

    fun pcm16leToWav(
        pcm: ByteArray,
        sampleRate: Int = AudioConfig.SAMPLE_RATE_HZ,
        channels: Int = AudioConfig.CHANNEL_COUNT,
    ): ByteArray {
        val bitsPerSample = AudioConfig.ENCODING_BITS
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcm.size
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        return header.array() + pcm
    }
}
