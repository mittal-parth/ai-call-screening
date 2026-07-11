package com.aicallscreening.common

import java.io.ByteArrayOutputStream

/**
 * Accumulates streamed PCM chunks into fixed-size windows for the offline
 * analyzer. Cloud streaming forwards every ~100 ms chunk individually, but the
 * on-device Gemma model runs one request/response per audio window, so chunks
 * are coalesced here until a full window is available.
 *
 * Not thread-safe; callers must serialize access (the monitor forwards chunks
 * from a single reader coroutine).
 */
class AudioWindowBuffer(
    private val windowBytes: Int = ScamConfig.OFFLINE_WINDOW_BYTES,
) {
    init {
        require(windowBytes > 0) { "windowBytes must be positive" }
    }

    private val buffer = ByteArrayOutputStream()

    val pendingBytes: Int
        get() = buffer.size()

    /**
     * Appends a chunk and returns a completed window when the threshold is
     * reached, otherwise null. If a single append overshoots the window size,
     * only one window is emitted; the remainder is retained for the next call.
     */
    fun append(chunk: ByteArray): ByteArray? {
        buffer.write(chunk)
        if (buffer.size() < windowBytes) {
            return null
        }
        val all = buffer.toByteArray()
        buffer.reset()
        val window = all.copyOfRange(0, windowBytes)
        if (all.size > windowBytes) {
            buffer.write(all, windowBytes, all.size - windowBytes)
        }
        return window
    }

    /** Returns any remaining buffered audio (e.g. at end of call) and clears it. */
    fun drain(): ByteArray? {
        if (buffer.size() == 0) {
            return null
        }
        val remainder = buffer.toByteArray()
        buffer.reset()
        return remainder
    }

    fun clear() {
        buffer.reset()
    }
}
