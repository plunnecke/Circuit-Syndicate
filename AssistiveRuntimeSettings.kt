package com.circuitsyndicate.findingtheway

import android.content.Context
import android.content.Intent

object AssistiveRuntimeSettings {

    const val ACTION_MODE_CHANGED =
        "com.circuitsyndicate.findingtheway.action.ASSISTIVE_MODE_CHANGED"
    const val ACTION_BACKGROUND_RUNTIME_CHANGED =
        "com.circuitsyndicate.findingtheway.action.BACKGROUND_RUNTIME_CHANGED"
    const val EXTRA_MODE = "mode"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_BACKGROUND_RUNTIME_ENABLED = "background_runtime_enabled"

    private const val PREF_NAME = "assistive_runtime_settings"
    private const val KEY_MODE = "assistive_runtime_mode"
    private const val KEY_BACKGROUND_RUNTIME_ENABLED = "background_runtime_enabled"

    fun getMode(context: Context): AssistiveRuntimeMode {
        val appContext = context.applicationContext
        val stored = appContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, AssistiveRuntimeMode.ASSISTIVE_ACTIVE.name)
        return AssistiveRuntimeMode.fromStoredValue(stored)
    }

    fun setMode(
        context: Context,
        mode: AssistiveRuntimeMode,
        source: String = "APP"
    ): Boolean {
        val appContext = context.applicationContext
        val previous = getMode(appContext)

        if (previous != mode) {
            appContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name)
                .apply()
        }

        appContext.sendBroadcast(Intent(ACTION_MODE_CHANGED).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_SOURCE, source)
        })

        return previous != mode
    }

    fun isBackgroundRuntimeEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        return appContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BACKGROUND_RUNTIME_ENABLED, true)
    }

    fun setBackgroundRuntimeEnabled(
        context: Context,
        enabled: Boolean,
        source: String = "APP"
    ): Boolean {
        val appContext = context.applicationContext
        val previous = isBackgroundRuntimeEnabled(appContext)

        if (previous != enabled) {
            appContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BACKGROUND_RUNTIME_ENABLED, enabled)
                .apply()
        }

        appContext.sendBroadcast(Intent(ACTION_BACKGROUND_RUNTIME_CHANGED).apply {
            putExtra(EXTRA_BACKGROUND_RUNTIME_ENABLED, enabled)
            putExtra(EXTRA_SOURCE, source)
        })

        return previous != enabled
    }

    fun isAssistiveSpeechAllowed(context: Context): Boolean {
        val appContext = context.applicationContext
        return AudioSettings.isAudioEnabled(appContext) &&
            getMode(appContext) == AssistiveRuntimeMode.ASSISTIVE_ACTIVE
    }
}
