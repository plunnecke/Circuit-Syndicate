package com.circuitsyndicate.findingtheway

import android.content.Context

object AudioSettings {

    private const val PREF_NAME = "audio_settings"
    private const val KEY_AUDIO_ENABLED = "audio_enabled"

    fun isAudioEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ENABLED, true)

    fun setAudioEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUDIO_ENABLED, enabled).apply()
    }
}
