package com.aicallscreening.common

import java.util.Base64

object PcmUtils {
    fun shortsToBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * AudioConfig.BYTES_PER_SAMPLE)
        for (i in samples.indices) {
            val sample = samples[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun bytesToShorts(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0) { "PCM byte array length must be even" }
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt() and 0xFF
            samples[i] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    fun bytesToBase64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(data)
}
