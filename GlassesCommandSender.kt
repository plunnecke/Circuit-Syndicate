package com.circuitsyndicate.findingtheway

/**
 * Typed API for sending commands to the glasses ESP32-CAM via BLE.
 * All calls route through GlassesBleManager which holds the GATT connection.
 */
object GlassesCommandSender {

    private const val POLICY_DELAY_MIN_MS = 20L
    private const val POLICY_DELAY_MAX_MS = 300L
    private const val POLICY_MAX_STEP_MS = 10L
    private const val POLICY_COOLDOWN_MS = 2_000L
    private const val POLICY_REJECTED_BUMP_MS = 10L
    private const val POLICY_CLAMPED_BUMP_MS = 5L
    private const val POLICY_MISSING_ACK_BUMP_MS = 5L

    enum class BurstCadencePolicyReason {
        BASELINE,
        COOLDOWN,
        ACK_APPLIED,
        ACK_CLAMPED,
        ACK_REJECTED,
        ACK_MISSING
    }

    data class BurstCadenceHandoff(
        val recommendedInterFrameDelayMs: Long,
        val cadenceHealth: GlassesImagePipeline.BurstCadenceHealth,
        val burstsInWindow: Int,
        val policyReason: BurstCadencePolicyReason,
        val policyCooldownUntilMs: Long?
    )

    data class BurstCadencePolicyState(
        val effectiveDelayMs: Long,
        val policyReason: BurstCadencePolicyReason,
        val cooldownUntilMs: Long?
    )

    @Volatile private var lastHintSentAtMs: Long = 0L
    @Volatile private var lastHintDelayMs: Long? = null
    @Volatile private var lastAckReceivedAtMs: Long = 0L
    @Volatile private var lastAckAppliedDelayMs: Long? = null
    @Volatile private var lastPolicyState: BurstCadencePolicyState? = null

    private fun canSendCaptureCommand(command: String): Boolean {
        if (isCaptureEnabled()) return true
        InteractionLogger.log("COMMAND_BLOCKED", "APP→GLASSES", "$command blocked by capture gate")
        return false
    }

    /** Request a single photo + object detection. */
    fun takePhoto(): Boolean {
        if (!canSendCaptureCommand("CMD_SINGLE_PHOTO")) return false
        InteractionLogger.logCommand("CMD_SINGLE_PHOTO", "APP→GLASSES")
        return GlassesBleManager.takePhoto()
    }

    /** Request burst capture + motion-aware object detection. */
    fun takeBurst(interruptInFlight: Boolean = false): Boolean {
        val commandName = if (interruptInFlight) {
            "CMD_BURST_RESTART"
        } else {
            "CMD_BURST"
        }
        if (!canSendCaptureCommand(commandName)) return false

        val handoff = getBurstCadenceHandoff()
        val logSuffix = if (handoff == null) {
            ""
        } else {
            " hint(delay=${handoff.recommendedInterFrameDelayMs}ms,health=${handoff.cadenceHealth.name},n=${handoff.burstsInWindow},reason=${handoff.policyReason.name})"
        }
        InteractionLogger.logCommand("$commandName$logSuffix", "APP→GLASSES")

        val sent = if (interruptInFlight) {
            GlassesBleManager.interruptAndTakeBurst(
                recommendedInterFrameDelayMs = handoff?.recommendedInterFrameDelayMs,
                cadenceHealth = handoff?.cadenceHealth
            )
        } else {
            GlassesBleManager.takeBurst(
                recommendedInterFrameDelayMs = handoff?.recommendedInterFrameDelayMs,
                cadenceHealth = handoff?.cadenceHealth
            )
        }

        if (sent && handoff != null) {
            lastHintSentAtMs = System.currentTimeMillis()
            lastHintDelayMs = handoff.recommendedInterFrameDelayMs
            lastPolicyState = BurstCadencePolicyState(
                effectiveDelayMs = handoff.recommendedInterFrameDelayMs,
                policyReason = handoff.policyReason,
                cooldownUntilMs = handoff.policyCooldownUntilMs
            )
        }
        return sent
    }

    /**
     * Latest rolling cadence recommendation to hand off to firmware, if available.
     */
    fun getBurstCadenceHandoff(): BurstCadenceHandoff? {
        val aggregate = runCatching {
            GlassesBleManager.getPipeline().getLastBurstTimingAggregateStats()
        }.getOrNull() ?: return null

        val feedback = runCatching {
            GlassesBleManager.getPipeline().getLastBurstCadenceHandoffFeedback()
        }.getOrNull()

        if (feedback != null && feedback.receivedAtMs > lastAckReceivedAtMs) {
            lastAckReceivedAtMs = feedback.receivedAtMs
            lastAckAppliedDelayMs = feedback.appliedInterFrameDelayMs
        }

        val recommendedDelay = aggregate.recommendedInterFrameDelayMs ?: return null
        val now = System.currentTimeMillis()
        val effective = computePolicyDelay(
            baseDelayMs = recommendedDelay,
            nowMs = now,
            latestFeedback = feedback
        )
        val finalDelay = applyStepLimit(
            previousDelayMs = lastHintDelayMs,
            targetDelayMs = effective.effectiveDelayMs
        )

        return BurstCadenceHandoff(
            recommendedInterFrameDelayMs = finalDelay,
            cadenceHealth = aggregate.cadenceHealth,
            burstsInWindow = aggregate.burstsInWindow,
            policyReason = effective.policyReason,
            policyCooldownUntilMs = effective.cooldownUntilMs
        )
    }

    fun getBurstCadencePolicyState(): BurstCadencePolicyState? = lastPolicyState

    private fun computePolicyDelay(
        baseDelayMs: Long,
        nowMs: Long,
        latestFeedback: GlassesImagePipeline.BurstCadenceHandoffFeedback?
    ): BurstCadencePolicyState {
        val feedback = latestFeedback
        val hasAck = feedback != null && feedback.receivedAtMs >= lastHintSentAtMs

        val fallbackFromAck = lastAckAppliedDelayMs ?: lastHintDelayMs ?: baseDelayMs
        if (lastHintSentAtMs > 0L && !hasAck && nowMs < lastHintSentAtMs + POLICY_COOLDOWN_MS) {
            return BurstCadencePolicyState(
                effectiveDelayMs = fallbackFromAck.coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS),
                policyReason = BurstCadencePolicyReason.COOLDOWN,
                cooldownUntilMs = lastHintSentAtMs + POLICY_COOLDOWN_MS
            )
        }

        if (lastHintSentAtMs > 0L && !hasAck) {
            val bumped = (fallbackFromAck + POLICY_MISSING_ACK_BUMP_MS)
                .coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS)
            return BurstCadencePolicyState(
                effectiveDelayMs = bumped,
                policyReason = BurstCadencePolicyReason.ACK_MISSING,
                cooldownUntilMs = null
            )
        }

        val fromAck = when (feedback?.ackLabel) {
            "APPLIED" -> BurstCadencePolicyState(
                effectiveDelayMs = (feedback.appliedInterFrameDelayMs ?: baseDelayMs)
                    .coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS),
                policyReason = BurstCadencePolicyReason.ACK_APPLIED,
                cooldownUntilMs = null
            )
            "CLAMPED" -> BurstCadencePolicyState(
                effectiveDelayMs = ((feedback.appliedInterFrameDelayMs ?: baseDelayMs) + POLICY_CLAMPED_BUMP_MS)
                    .coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS),
                policyReason = BurstCadencePolicyReason.ACK_CLAMPED,
                cooldownUntilMs = null
            )
            "REJECTED" -> BurstCadencePolicyState(
                effectiveDelayMs = ((feedback.appliedInterFrameDelayMs ?: fallbackFromAck) + POLICY_REJECTED_BUMP_MS)
                    .coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS),
                policyReason = BurstCadencePolicyReason.ACK_REJECTED,
                cooldownUntilMs = null
            )
            else -> null
        }

        return fromAck ?: BurstCadencePolicyState(
            effectiveDelayMs = baseDelayMs.coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS),
            policyReason = BurstCadencePolicyReason.BASELINE,
            cooldownUntilMs = null
        )
    }

    private fun applyStepLimit(previousDelayMs: Long?, targetDelayMs: Long): Long {
        val previous = previousDelayMs ?: return targetDelayMs.coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS)
        val delta = targetDelayMs - previous
        val limited = when {
            delta > POLICY_MAX_STEP_MS -> previous + POLICY_MAX_STEP_MS
            delta < -POLICY_MAX_STEP_MS -> previous - POLICY_MAX_STEP_MS
            else -> targetDelayMs
        }
        return limited.coerceIn(POLICY_DELAY_MIN_MS, POLICY_DELAY_MAX_MS)
    }

    fun resetBurstCadencePolicyState() {
        lastHintSentAtMs = 0L
        lastHintDelayMs = null
        lastAckReceivedAtMs = 0L
        lastAckAppliedDelayMs = null
        lastPolicyState = null
    }

    /** Send a raw BLE payload to the glasses control characteristic. */
    fun sendPayload(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        InteractionLogger.logCommand("CMD_PAYLOAD_${payload.size}B", "APP→GLASSES")
        return GlassesBleManager.writePayload(payload)
    }

    /** Check whether the glasses BLE link is live. */
    fun isConnected(): Boolean = GlassesBleManager.isConnected()

    /** Check whether capture/inference commands are currently allowed. */
    fun isCaptureEnabled(): Boolean = GlassesBleManager.isCaptureEnabled()
}
