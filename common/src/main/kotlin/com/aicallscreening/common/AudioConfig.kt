package com.aicallscreening.common

object AudioConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val ENCODING_BITS = 16
    const val BYTES_PER_SAMPLE = 2
    const val MIME_TYPE = "audio/pcm;rate=16000"
    const val CHUNK_DURATION_MS = 100

    val CHUNK_SIZE_BYTES: Int =
        SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * CHANNEL_COUNT * CHUNK_DURATION_MS / 1000
}
