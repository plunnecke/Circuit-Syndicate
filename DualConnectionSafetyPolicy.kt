package com.circuitsyndicate.findingtheway

/**
 * Pure guards for dual-connected safety prompts.
 */
object DualConnectionSafetyPolicy {

    fun isDualConnected(
        vestConnected: Boolean,
        glassesConnected: Boolean
    ): Boolean = vestConnected && glassesConnected

    fun shouldPromptForVestDisable(
        vestConnected: Boolean,
        glassesConnected: Boolean,
        requestedVestStateOn: Boolean
    ): Boolean = !requestedVestStateOn && isDualConnected(vestConnected, glassesConnected)

    fun shouldPromptForGlassesCaptureDisable(
        vestConnected: Boolean,
        glassesConnected: Boolean,
        requestedCaptureEnabled: Boolean
    ): Boolean = !requestedCaptureEnabled && isDualConnected(vestConnected, glassesConnected)

    /**
     * Validate user confirmation before allowing vest safeguards to be disabled.
     * Glasses must still be connected and picture-taking must still be enabled.
     */
    fun isGlassesCaptureValidatedForVestDisable(
        glassesConnected: Boolean,
        glassesCaptureEnabled: Boolean
    ): Boolean = glassesConnected && glassesCaptureEnabled

    /**
     * Validate user confirmation before allowing glasses capture to be disabled.
     * Vest must still be connected and all safeguard subsystems must be ON.
     */
    fun isVestSafeguardsValidatedForGlassesDisable(
        vestConnected: Boolean,
        vestSystemOn: Boolean,
        hapticsOn: Boolean,
        sensorsOn: Boolean
    ): Boolean = vestConnected && vestSystemOn && hapticsOn && sensorsOn
}
