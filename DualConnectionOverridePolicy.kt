package com.circuitsyndicate.findingtheway

/**
 * Pure state machine for one-time disconnect overrides inside a dual-connected epoch.
 */
object DualConnectionOverridePolicy {

    data class State(
        val hasSnapshot: Boolean = false,
        val prevVestConnected: Boolean = false,
        val prevGlassesConnected: Boolean = false,
        val dualConnectedEpochActive: Boolean = false,
        val initialDisconnectOverrideApplied: Boolean = false
    )

    data class Decision(
        val nextState: State,
        val applyVestForceOnOverride: Boolean,
        val applyGlassesCaptureForceOnOverride: Boolean
    )

    fun initialize(
        vestConnected: Boolean,
        glassesConnected: Boolean
    ): State {
        val dualConnected = vestConnected && glassesConnected
        return State(
            hasSnapshot = true,
            prevVestConnected = vestConnected,
            prevGlassesConnected = glassesConnected,
            dualConnectedEpochActive = dualConnected,
            initialDisconnectOverrideApplied = false
        )
    }

    fun transition(
        state: State,
        vestConnected: Boolean,
        glassesConnected: Boolean
    ): Decision {
        if (!state.hasSnapshot) {
            return Decision(
                nextState = initialize(vestConnected, glassesConnected),
                applyVestForceOnOverride = false,
                applyGlassesCaptureForceOnOverride = false
            )
        }

        var dualConnectedEpochActive = state.dualConnectedEpochActive
        var initialDisconnectOverrideApplied = state.initialDisconnectOverrideApplied

        if (vestConnected && glassesConnected &&
            (!state.prevVestConnected || !state.prevGlassesConnected || !dualConnectedEpochActive)
        ) {
            dualConnectedEpochActive = true
            initialDisconnectOverrideApplied = false
        }

        val wasBothConnected = state.prevVestConnected && state.prevGlassesConnected
        val glassesDropped = state.prevGlassesConnected && !glassesConnected
        val vestDropped = state.prevVestConnected && !vestConnected
        val exactlyOneSideDropped = glassesDropped.xor(vestDropped)

        var applyVestForceOnOverride = false
        var applyGlassesCaptureForceOnOverride = false

        if (dualConnectedEpochActive && !initialDisconnectOverrideApplied &&
            wasBothConnected && exactlyOneSideDropped
        ) {
            applyVestForceOnOverride = glassesDropped
            applyGlassesCaptureForceOnOverride = vestDropped
            initialDisconnectOverrideApplied = true
        }

        return Decision(
            nextState = State(
                hasSnapshot = true,
                prevVestConnected = vestConnected,
                prevGlassesConnected = glassesConnected,
                dualConnectedEpochActive = dualConnectedEpochActive,
                initialDisconnectOverrideApplied = initialDisconnectOverrideApplied
            ),
            applyVestForceOnOverride = applyVestForceOnOverride,
            applyGlassesCaptureForceOnOverride = applyGlassesCaptureForceOnOverride
        )
    }
}