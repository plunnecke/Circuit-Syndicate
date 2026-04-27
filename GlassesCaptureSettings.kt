package com.circuitsyndicate.findingtheway

import android.content.Context

/**
 * App-local capture policy for glasses photo/burst commands.
 *
 * This gate controls whether the app may request picture capture/inference
 * while keeping BLE connection state unchanged.
 */
object GlassesCaptureSettings {

    private const val PREF_NAME = "glasses_capture_settings"
    private const val KEY_CAPTURE_ENABLED = "capture_enabled"

    fun isCaptureEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAPTURE_ENABLED, true)
    }

    fun setCaptureEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CAPTURE_ENABLED, enabled)
            .apply()
    }
}
