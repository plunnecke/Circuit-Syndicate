package com.circuitsyndicate.findingtheway

import android.content.Context

/**
 * Controls whether the app should speak "No objects detected" summaries.
 *
 * This setting does not affect speech for real detections. Those remain gated
 * only by app-wide audio feedback and assistive runtime mode.
 */
object NoObjectsSpeechSettings {

    private const val PREF_NAME = "no_objects_speech_settings"
    private const val KEY_ENABLED = "no_objects_speech_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
