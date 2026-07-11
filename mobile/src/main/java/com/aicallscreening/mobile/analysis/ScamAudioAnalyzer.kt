package com.aicallscreening.mobile.analysis

import com.aicallscreening.common.ScamVerdict

/**
 * An engine that consumes a window of raw PCM16 (16 kHz mono) call audio and
 * returns a scam verdict, or null when it cannot produce one for that window.
 *
 * Implementations run entirely on-device (offline fallback). They are created
 * lazily and must be [close]d to release native resources.
 */
interface ScamAudioAnalyzer : AutoCloseable {
    /** True once the underlying model/engine is loaded and can analyze audio. */
    val isReady: Boolean

    /**
     * Loads the model/engine. Safe to call multiple times; returns whether the
     * analyzer is ready afterwards. May be slow (model load) so callers should
     * invoke it off the main thread.
     */
    suspend fun prepare(): Boolean

    /** Analyzes one audio window, returning a parsed verdict when available. */
    suspend fun analyze(pcm: ByteArray): ScamVerdict?
}
