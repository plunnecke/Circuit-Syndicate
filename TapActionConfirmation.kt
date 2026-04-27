package com.circuitsyndicate.findingtheway

import android.os.SystemClock

/**
 * Requires two taps for a destructive or state-changing action:
 * first tap announces intent, second tap (within the window) confirms.
 */
class TapActionConfirmation(
    private val confirmationWindowMs: Long = 4_000L
) {

    private var pendingKey: String? = null
    private var pendingUntilElapsedMs: Long = 0L

    fun confirmOrAnnounce(
        key: String,
        announcement: String,
        interruptSpeech: () -> Unit,
        speak: (String) -> Unit,
        onConfirmed: () -> Unit
    ) {
        val now = SystemClock.elapsedRealtime()
        val isConfirmed = pendingKey == key && pendingUntilElapsedMs > now

        interruptSpeech()

        if (isConfirmed) {
            clear()
            onConfirmed()
            return
        }

        pendingKey = key
        pendingUntilElapsedMs = now + confirmationWindowMs
        speak(announcement)
    }

    fun clear() {
        pendingKey = null
        pendingUntilElapsedMs = 0L
    }
}
