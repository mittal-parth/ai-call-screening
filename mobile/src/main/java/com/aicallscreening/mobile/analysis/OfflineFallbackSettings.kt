package com.aicallscreening.mobile.analysis

import android.content.Context

/**
 * Persisted, runtime-toggleable feature flag for the offline on-device fallback.
 *
 * Defaults to disabled so the app behaves exactly like the cloud-only path until
 * the feature is explicitly turned on. A separate [forceOnDevice] toggle exists
 * for testing (e.g. in an emulator) so the on-device engine can be exercised
 * without actually dropping network connectivity.
 */
class OfflineFallbackSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val enabled: Boolean,
        val forceOnDevice: Boolean,
    )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var forceOnDevice: Boolean
        get() = prefs.getBoolean(KEY_FORCE_ON_DEVICE, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_ON_DEVICE, value).apply()

    fun snapshot(): Snapshot = Snapshot(enabled = enabled, forceOnDevice = forceOnDevice)

    private companion object {
        const val PREFS_NAME = "offline_fallback"
        const val KEY_ENABLED = "enabled"
        const val KEY_FORCE_ON_DEVICE = "force_on_device"
    }
}
