package com.circuitsyndicate.findingtheway

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Quick motion tracker that compares YOLO detections across burst frames.
 *
 * Notes:
 * - Tries to chain matches across every neighboring frame pair.
 * - Falls back to centroid distance when IoU is too weak.
 * - Uses velocity per second (not per frame), so timing changes matter less.
 * - Uses hysteresis so one noisy burst does not instantly flip state.
 * - If camera compensation evidence is weak, directional state goes UNKNOWN.
 */
class MotionTracker {

    companion object {
        private const val TAG = "MotionTracker"

        // Velocity thresholds (per-second, normalized 0..1 image coords).
        // At VGA 640 px, 0.06/s ≈ 38 px/s ≈ ~19 px in a 500 ms burst gap.
        private const val VELOCITY_THRESHOLD = 0.06f          // base speed cutoff for motion
        // Depth velocity (blended area + height log-rate) thresholds.
        // Retreat is softer because shrink signals are usually noisier.
        private const val SIZE_VELOCITY_APPROACH = 0.22f
        private const val SIZE_VELOCITY_RETREAT = -0.14f
        private const val SIZE_VELOCITY_APPROACH_SOFT = 0.10f
        private const val SIZE_VELOCITY_RETREAT_SOFT = -0.07f
        private const val DIRECTION_DOMINANCE_RATIO = 1.20f     // needs clear left/right dominance
        private const val MIN_LATERAL_COMPONENT_RATIO = 0.60f   // ignore weak lateral sign flips
        private const val MIN_CONFIDENCE_FOR_DIRECTIONAL = 0.45f

        // Size-change stabilization to reduce jitter fake-outs.
        private const val SIZE_SIGNAL_MIN_AREA = 0.008f
        private const val SIZE_SIGNAL_FULL_AREA = 0.080f
        private const val SIZE_SIGNAL_ASPECT_HALF = 0.35f
        private const val SIZE_SIGNAL_LOG_CLAMP = 0.40f
        private const val SIZE_SIGNAL_LOW_CONFIDENCE_SUPPRESS = 0.35f
        private const val SIZE_SIGNAL_MAX_VELOCITY = 1.20f
        private const val HEIGHT_SIGNAL_MIN_HEIGHT = 0.05f
        private const val HEIGHT_SIGNAL_FULL_HEIGHT = 0.45f
        private const val HEIGHT_SIGNAL_MAX_VELOCITY = 1.20f
        private const val DEPTH_BLEND_SIZE_WEIGHT = 0.40f
        private const val DEPTH_BLEND_HEIGHT_WEIGHT = 0.60f

        // Reference delta used for normalization (seconds).
        private const val REFERENCE_DT_S = 0.1f   // 100 ms

        // IoU threshold for primary box matching.
        private const val MATCH_IOU_THRESHOLD = 0.3f

        // Centroid-distance fallback (fraction of frame diagonal).
        private const val CENTROID_MATCH_THRESHOLD = 0.20f

        // Intra-burst matching robustness weights/penalties.
        private const val MATCH_SIZE_CHANGE_WEIGHT = 0.25f
        private const val MATCH_ASPECT_CHANGE_WEIGHT = 0.15f
        private const val MATCH_CONFIDENCE_PENALTY_WEIGHT = 0.20f
        private const val MATCH_CLASS_MISMATCH_PENALTY = 0.45f
        private const val MATCH_CLASS_HIERARCHY_PENALTY = 0.18f
        private const val MATCH_CLASS_FLICKER_IOU_THRESHOLD = 0.55f
        private const val MATCH_CLASS_FLICKER_CENTER_THRESHOLD = 0.08f
        private const val MATCH_HIERARCHY_IOU_THRESHOLD = MATCH_IOU_THRESHOLD * 0.80f
        private const val MATCH_HIERARCHY_CENTROID_THRESHOLD = CENTROID_MATCH_THRESHOLD * 1.25f
        private const val MATCH_MOTION_TRUST_BIAS_WEIGHT = 0.12f
        private const val MATCH_MAX_ACCEPT_COST = 1.45f

        // Max total burst span (ms).
        const val MAX_FRAME_GAP_MS = 5000L

        // Hysteresis: how many bursts in a row before confirming a new state.
        private const val HYSTERESIS_COUNT = 1

        // Persistent registry timeout (ms).
        // 3 burst intervals (3 x 30 s = 90 s) gives some tolerance for misses.
        private const val REGISTRY_STALE_MS = 90_000L

        // Cross-burst matching thresholds (looser than intra-burst).
        private const val CROSS_BURST_IOU_THRESHOLD = 0.15f
        private const val CROSS_BURST_CENTROID_THRESHOLD = 0.30f
        private const val CROSS_BURST_SIZE_CHANGE_WEIGHT = 0.30f
        private const val CROSS_BURST_ASPECT_CHANGE_WEIGHT = 0.20f
        private const val CROSS_BURST_CONFIDENCE_PENALTY_WEIGHT = 0.25f
        private const val CROSS_BURST_CLASS_MISMATCH_PENALTY = 0.70f
        private const val CROSS_BURST_CLASS_HIERARCHY_PENALTY = 0.30f
        private const val CROSS_BURST_CLASS_FLICKER_IOU_THRESHOLD = 0.60f
        private const val CROSS_BURST_CLASS_FLICKER_CENTER_THRESHOLD = 0.07f
        private const val CROSS_BURST_HIERARCHY_IOU_THRESHOLD = CROSS_BURST_IOU_THRESHOLD * 0.80f
        private const val CROSS_BURST_HIERARCHY_CENTROID_THRESHOLD = CROSS_BURST_CENTROID_THRESHOLD * 1.20f
        private const val CROSS_BURST_MOTION_TRUST_BIAS_WEIGHT = 0.16f
        private const val CROSS_BURST_AGE_PENALTY_WEIGHT = 0.25f
        private const val CROSS_BURST_MAX_ACCEPT_COST = 1.65f
        private const val MOTION_TRUST_MIN = 0.45f
        private const val MOTION_TRUST_MAX = 1.20f

        // Kalman tuning constants.
        private const val KALMAN_ACCEL_NOISE = 0.5f        // acceleration noise spectral density
        private const val KALMAN_MEAS_NOISE_POS = 0.002f   // position measurement noise variance
        private const val KALMAN_MEAS_NOISE_AREA = 0.005f  // area measurement noise variance

        // Distance thresholds (feet) for threat-level logic.
        // Close range uses softer velocity gates, far range uses stricter ones.
        private const val CLOSE_RANGE_FT  =  5f
        private const val MEDIUM_RANGE_FT = 15f
        // Velocity threshold multipliers per distance band.
        // < 1 lowers the bar; > 1 raises it.
        private const val CLOSE_RANGE_VEL_SCALE  = 0.5f   // half the normal threshold at close range
        private const val FAR_RANGE_VEL_SCALE    = 1.5f   // 50% harder to trigger at far range

        // Camera compensation confidence rules.
        private const val EXTERNAL_MIN_CONFIDENCE = 0.30f
        private const val EXTERNAL_STRONG_CONFIDENCE = 0.70f
        private const val INTERNAL_MIN_CONFIDENCE = 0.30f
        private const val INTERNAL_STRONG_CONFIDENCE = 0.60f
        private const val INTERNAL_ALL_MATCH_PENALTY = 0.35f
        private const val INTERNAL_MIN_ALL_MATCH_SUPPORT = 3

        // Rules for degrading under-constrained evidence.
        private const val MIN_CHAIN_OBSERVATIONS_FOR_MOTION = 2
        private const val MIN_CHAIN_PAIR_SUPPORT = 1
        private const val MIN_COMPENSATION_RELIABILITY_FOR_DIRECTIONAL = 0.35f
        private const val NO_COMP_STRONG_MOTION_FACTOR = 2.0f
        // Let depth-direction survive one dropped chunk in a short burst.
        private const val MIN_CHAIN_OBSERVATIONS_FOR_DEPTH_DIRECTIONAL = 2
        private const val MIN_CHAIN_PAIR_SUPPORT_FOR_DEPTH_DIRECTIONAL = 1
        private const val HYSTERESIS_DEPTH_FLIP_COUNT = 2

        // Distance-rate fusion thresholds (feet/second, positive = approaching).
        private const val DISTANCE_RATE_STALE_MS = 60_000L
        private const val DISTANCE_RATE_MIN_DT_S = 0.20f
        private const val DISTANCE_RATE_MIN_DELTA_FT = 0.15f
        private const val DISTANCE_RATE_MAX_ABS_FTPS = 12.0f
        private const val DISTANCE_RATE_APPROACH_NEAR_FTPS = 0.25f
        private const val DISTANCE_RATE_APPROACH_MID_FTPS = 0.40f
        private const val DISTANCE_RATE_APPROACH_FAR_FTPS = 0.60f
        private const val DISTANCE_RATE_AWAY_NEAR_FTPS = 0.20f
        private const val DISTANCE_RATE_AWAY_MID_FTPS = 0.35f
        private const val DISTANCE_RATE_AWAY_FAR_FTPS = 0.50f
        private const val DISTANCE_RATE_EMA_ALPHA = 0.45f
        private const val DISTANCE_RATE_SIGN_STREAK_MIN_FTPS = 0.10f
        private const val DISTANCE_RATE_CONFIRM_STREAK = 2

        private fun blendDepthRates(areaRate: Float, heightRate: Float): Float {
            val boundedAreaRate = areaRate.coerceIn(-SIZE_SIGNAL_MAX_VELOCITY, SIZE_SIGNAL_MAX_VELOCITY)
            val boundedHeightRate = heightRate.coerceIn(-HEIGHT_SIGNAL_MAX_VELOCITY, HEIGHT_SIGNAL_MAX_VELOCITY)
            val blended = boundedAreaRate * DEPTH_BLEND_SIZE_WEIGHT +
                boundedHeightRate * DEPTH_BLEND_HEIGHT_WEIGHT
            val maxDepthVel = maxOf(SIZE_SIGNAL_MAX_VELOCITY, HEIGHT_SIGNAL_MAX_VELOCITY)
            return blended.coerceIn(-maxDepthVel, maxDepthVel)
        }
    }

    enum class MotionState {
        STATIONARY,
        APPROACHING,
        MOVING_AWAY,
        CROSSING_LEFT,
        CROSSING_RIGHT,
        UNKNOWN
    }

    /**
     * Threat level from distance + motion.
     * Main point is to separate "close and approaching" from "far and slow".
     */
    enum class ThreatLevel {
        /** Approaching/crossing and very close (< 5 ft). */
        CRITICAL,
        /** Approaching at medium range, or notable motion nearby. */
        HIGH,
        /** Nearby but not clearly critical yet. */
        MODERATE,
        /** Lower concern: far, stationary, moving away, or unclear. */
        LOW,
        /** Not enough info to score. */
        UNKNOWN
    }

    data class TrackedDetection(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF,
        val classIndex: Int,
        val motionState: MotionState,
        val motionMagnitude: Float,
        val trackId: Int = -1,
        /** Estimated distance in feet, null if not available. */
        val distanceFeet: Float? = null,
        /** Fused threat level from motion + distance. */
        val threatLevel: ThreatLevel = ThreatLevel.UNKNOWN
    )

    // ------------------------------------------------------------------
    // Persistent object registry (kept across bursts)
    // ------------------------------------------------------------------
    // Each entry is a known object that we keep between burst intervals,
    // using a growing persistent ID.
    private data class PersistentEntry(
        val classIndex: Int,
        val label: String,
        val boundingBox: RectF,
        val lastSeenMs: Long
    )

    private var nextPersistentId = 0
    private val persistentRegistry = mutableMapOf<Int, PersistentEntry>()

    // ------------------------------------------------------------------
    // Temporal hysteresis state (also kept across bursts)
    // ------------------------------------------------------------------
    // Key format: "persistent:$persistentId".
    private data class StateHistory(var lastState: MotionState, var streak: Int)
    private val stateHistory = mutableMapOf<String, StateHistory>()

    // Kalman state per persistent object.
    private val kalmanStates = mutableMapOf<Int, KalmanObjectState>()

    // Tracking fields for diagnostics.
    private var diagnosticsBurstCounter = 0L
    private val lastConfirmedStateByPersistent = mutableMapOf<Int, MotionState>()
    private data class DistanceTrendEntry(
        var distanceFeet: Float,
        var timestampMs: Long,
        var closingRateEmaFtPerSec: Float = 0f,
        var hasClosingRateEma: Boolean = false,
        var approachStreak: Int = 0,
        var awayStreak: Int = 0
    )
    private val distanceTrendByPersistent = mutableMapOf<Int, DistanceTrendEntry>()

    // ------------------------------------------------------------------
    // Accumulated motion for one object across frame pairs.
    // ------------------------------------------------------------------
    private data class AccumulatedMotion(
        var velocitySumX: Float = 0f,
        var velocitySumY: Float = 0f,
        var sizeVelocitySum: Float = 0f,
        var heightVelocitySum: Float = 0f,
        var pairCount: Int = 0
    ) {
        val avgVelX get() = if (pairCount > 0) velocitySumX / pairCount else 0f
        val avgVelY get() = if (pairCount > 0) velocitySumY / pairCount else 0f
        val avgSizeVel get() = if (pairCount > 0) sizeVelocitySum / pairCount else 0f
        val avgHeightVel get() = if (pairCount > 0) heightVelocitySum / pairCount else 0f
        val avgDepthVel get() = blendDepthRates(avgSizeVel, avgHeightVel)
    }

    private data class PairCameraMotion(
        val velocity: PointF,
        val confidence: Float,
        val usedStationaryAnchors: Boolean
    )

    private data class WeightedVelocity(
        val x: Float,
        val y: Float,
        val weight: Float
    )

    private data class MotionStateHistogram(
        var unknown: Int = 0,
        var stationary: Int = 0,
        var approaching: Int = 0,
        var movingAway: Int = 0,
        var crossingLeft: Int = 0,
        var crossingRight: Int = 0
    ) {
        fun record(state: MotionState) {
            when (state) {
                MotionState.UNKNOWN -> unknown++
                MotionState.STATIONARY -> stationary++
                MotionState.APPROACHING -> approaching++
                MotionState.MOVING_AWAY -> movingAway++
                MotionState.CROSSING_LEFT -> crossingLeft++
                MotionState.CROSSING_RIGHT -> crossingRight++
            }
        }

        fun compact(): String {
            return "UNK=$unknown,ST=$stationary,APP=$approaching,AWAY=$movingAway,L=$crossingLeft,R=$crossingRight"
        }
    }

    private data class MotionDiagnosticsAccumulator(
        val raw: MotionStateHistogram = MotionStateHistogram(),
        val evidence: MotionStateHistogram = MotionStateHistogram(),
        val confirmed: MotionStateHistogram = MotionStateHistogram(),
        var supportSamples: Int = 0,
        var pairSupportSum: Int = 0,
        var pairSupportMin: Int = Int.MAX_VALUE,
        var pairSupportMax: Int = 0,
        var observationSum: Int = 0,
        var observationMin: Int = Int.MAX_VALUE,
        var observationMax: Int = 0,
        var noMotionSupportCount: Int = 0,
        var degradedToUnknownCount: Int = 0,
        var hysteresisPendingCount: Int = 0,
        var flipGuardSuppressedCount: Int = 0,
        var transitionCount: Int = 0,
        var approachToAwayCount: Int = 0,
        var awayToApproachCount: Int = 0
    ) {
        fun recordSupport(pairSupport: Int, observationCount: Int) {
            supportSamples++
            pairSupportSum += pairSupport
            pairSupportMin = minOf(pairSupportMin, pairSupport)
            pairSupportMax = maxOf(pairSupportMax, pairSupport)
            observationSum += observationCount
            observationMin = minOf(observationMin, observationCount)
            observationMax = maxOf(observationMax, observationCount)
        }

        fun pairSupportAvg(): Float = if (supportSamples > 0) pairSupportSum.toFloat() / supportSamples else 0f
        fun observationAvg(): Float = if (supportSamples > 0) observationSum.toFloat() / supportSamples else 0f
        fun pairSupportMinSafe(): Int = if (supportSamples > 0) pairSupportMin else 0
        fun observationMinSafe(): Int = if (supportSamples > 0) observationMin else 0

        fun recordTransition(prev: MotionState?, curr: MotionState) {
            if (prev == null || prev == curr) return
            transitionCount++
            if (prev == MotionState.APPROACHING && curr == MotionState.MOVING_AWAY) {
                approachToAwayCount++
            }
            if (prev == MotionState.MOVING_AWAY && curr == MotionState.APPROACHING) {
                awayToApproachCount++
            }
        }
    }

    // ------------------------------------------------------------------
    // Kalman filter bits for smoother velocity + prediction.
    // ------------------------------------------------------------------

    /**
     * Simple 1D constant-velocity Kalman filter with state [position, velocity].
     * Process noise scales with dt, so long gaps naturally add uncertainty.
     */
    private class Kalman1D {
        var pos: Float = 0f
        var vel: Float = 0f
        private var pPos: Float = 0f   // P[0,0]  position variance
        private var pVel: Float = 0f   // P[1,1]  velocity variance
        private var pCross: Float = 0f // P[0,1]  cross-covariance

        fun initialize(
            position: Float, velocity: Float = 0f,
            posVar: Float = 0.01f, velVar: Float = 1.0f
        ) {
            pos = position; vel = velocity
            pPos = posVar; pVel = velVar; pCross = 0f
        }

        /** Predict state forward by [dt] seconds. */
        fun predict(dt: Float, q: Float = KALMAN_ACCEL_NOISE) {
            pos += vel * dt
            val dt2 = dt * dt; val dt3 = dt2 * dt
            pPos  += 2f * dt * pCross + dt2 * pVel + q * dt3 / 3f
            pCross += dt * pVel + q * dt2 / 2f
            pVel  += q * dt
        }

        /** Update from a position measurement. */
        fun updatePosition(measurement: Float, r: Float = KALMAN_MEAS_NOISE_POS) {
            val y = measurement - pos
            val s = pPos + r
            if (s < 1e-12f) return
            val kP = pPos / s
            val kV = pCross / s
            pos += kP * y; vel += kV * y
            pPos   = pPos * r / s
            pCross = pCross * r / s
            pVel  -= kV * kV * s
            if (pVel < 1e-8f) pVel = 1e-8f
        }

        /** Update from a direct velocity measurement (from burst analysis). */
        fun updateVelocity(measuredVel: Float, r: Float = 0.01f) {
            val y = measuredVel - vel
            val s = pVel + r
            if (s < 1e-12f) return
            val kP = pCross / s
            val kV = pVel / s
            pos += kP * y; vel += kV * y
            pPos  -= pCross * pCross / s
            pCross -= pCross * pVel / s
            pVel   = pVel * r / s
            if (pPos < 1e-8f) pPos = 1e-8f
            if (pVel < 1e-8f) pVel = 1e-8f
        }

          /** Velocity standard deviation (sqrt of velocity variance). */
        val velStdDev: Float get() = sqrt(maxOf(pVel, 1e-8f))
    }

    /**
      * Per-object Kalman wrapper with separate filters for x, y, and area.
      * Gives smoothed velocity, forward position prediction, and confidence.
     */
    private class KalmanObjectState {
        val kx = Kalman1D()
        val ky = Kalman1D()
        val kArea = Kalman1D()
        var lastUpdateMs: Long = 0L
        var initialized: Boolean = false

        fun initialize(cx: Float, cy: Float, area: Float, timestampMs: Long) {
            kx.initialize(cx, posVar = 0.01f, velVar = 1.0f)
            ky.initialize(cy, posVar = 0.01f, velVar = 1.0f)
            kArea.initialize(area, posVar = 0.01f, velVar = 0.5f)
            lastUpdateMs = timestampMs
            initialized = true
        }

        /** Standard predict-then-update step with a new observation. */
        fun update(cx: Float, cy: Float, area: Float, timestampMs: Long) {
            if (!initialized) { initialize(cx, cy, area, timestampMs); return }
            val dt = maxOf((timestampMs - lastUpdateMs) / 1000f, 0.010f)
            kx.predict(dt); ky.predict(dt); kArea.predict(dt, q = 0.1f)
            kx.updatePosition(cx)
            ky.updatePosition(cy)
            kArea.updatePosition(area, r = KALMAN_MEAS_NOISE_AREA)
            lastUpdateMs = timestampMs
        }

        /** Inject velocity from frame-pair analysis inside the burst. */
        fun updateWithVelocity(velX: Float, velY: Float, areaRate: Float) {
            kx.updateVelocity(velX)
            ky.updateVelocity(velY)
            kArea.updateVelocity(areaRate, r = 0.05f)
        }

        /** Predict centroid at [futureMs] without mutating internal state. */
        fun predictPosition(futureMs: Long): PointF {
            val dt = maxOf((futureMs - lastUpdateMs) / 1000f, 0f)
            return PointF(kx.pos + kx.vel * dt, ky.pos + ky.vel * dt)
        }

        val smoothedVelX: Float get() = kx.vel
        val smoothedVelY: Float get() = ky.vel
        val smoothedAreaRate: Float get() = kArea.vel

        /**
         * Confidence in velocity estimate (0 = uncertain, 1 = confident).
         * Derived from velocity variance in the Kalman state.
         */
        val velocityConfidence: Float get() {
            val s = sqrt(kx.velStdDev * kx.velStdDev + ky.velStdDev * ky.velStdDev)
            return 1f / (1f + s * 2f)
        }
    }

    /**
     * Analyze motion across one burst of detection frames.
     */
    fun analyzeMotion(
        frameDetections: List<List<ObjectDetector.Detection>>,
        frameTimestamps: List<Long>,
        externalCameraVelocity: PointF? = null,
        externalCameraConfidence: Float = 0f
    ): List<TrackedDetection> {
        if (frameDetections.isEmpty()) return emptyList()
        val burstDiagId = ++diagnosticsBurstCounter
        val fallbackTimestamp = frameTimestamps.lastOrNull() ?: System.currentTimeMillis()

        if (frameDetections.size == 1) {
            return approximateMotionFromHistory(
                burstDiagId = burstDiagId,
                detections = frameDetections[0],
                burstTimestamp = fallbackTimestamp,
                reason = "single_frame"
            )
        }

        // Skip motion calc if the burst spans too much time.
        if (frameTimestamps.size >= 2) {
            val totalGap = frameTimestamps.last() - frameTimestamps.first()
            if (totalGap > MAX_FRAME_GAP_MS) {
                Log.w(TAG, "Frame gap ${totalGap}ms exceeds max, suppressing motion")
                return approximateMotionFromHistory(
                    burstDiagId = burstDiagId,
                    detections = frameDetections.last(),
                    burstTimestamp = fallbackTimestamp,
                    reason = "frame_gap_${totalGap}ms"
                )
            }
        }

        // ---- Multi-frame chaining ----
        val numFrames = frameDetections.size
        var nextId = 0
        val chainIds = Array(numFrames) { IntArray(0) }
        chainIds[0] = IntArray(frameDetections[0].size) { nextId++ }

        val accumulated = mutableMapOf<Int, AccumulatedMotion>()
        val perPairCameraMotions = mutableListOf<PairCameraMotion>()

        for (f in 1 until numFrames) {
            val prevDets = frameDetections[f - 1]
            val currDets = frameDetections[f]
            val prevIds = chainIds[f - 1]
            val currIds = IntArray(currDets.size) { -1 }

            // Time delta for this pair (seconds), clamped to avoid divide-by-zero.
            val dtMs = (frameTimestamps.getOrElse(f) { frameTimestamps.last() }
                      - frameTimestamps.getOrElse(f - 1) { frameTimestamps.first() })
            val dtSec = maxOf(dtMs / 1000f, 0.010f)  // min 10 ms

            val matches = matchDetections(prevDets, currDets)

            val allPairVelocities = mutableListOf<PointF>()
            val stationaryPairVelocities = mutableListOf<WeightedVelocity>()

            for ((prevDet, currDet, currIdx, prevIdx) in matches) {
                val id = prevIds[prevIdx]
                currIds[currIdx] = id

                val shift = centroidShift(prevDet.boundingBox, currDet.boundingBox)
                val velX = shift.x / dtSec
                val velY = shift.y / dtSec
                val vel = PointF(velX, velY)
                allPairVelocities.add(vel)
                // Keep stationary-anchor velocities separately for better ego-motion.
                if (LabelSemantics.isStationaryAnchor(currDet.label)) {
                    val trust = LabelSemantics.motionTrust(currDet.label)
                        .coerceIn(MOTION_TRUST_MIN, MOTION_TRUST_MAX)
                    stationaryPairVelocities.add(
                        WeightedVelocity(
                            x = velX,
                            y = velY,
                            weight = trust
                        )
                    )
                }

                val sizeChange = stabilizedSizeChange(
                    prev = prevDet.boundingBox,
                    curr = currDet.boundingBox,
                    prevConfidence = prevDet.confidence,
                    currConfidence = currDet.confidence
                )
                val sizeVel = (sizeChange / dtSec)
                    .coerceIn(-SIZE_SIGNAL_MAX_VELOCITY, SIZE_SIGNAL_MAX_VELOCITY)
                val heightChange = stabilizedHeightChange(
                    prev = prevDet.boundingBox,
                    curr = currDet.boundingBox,
                    prevConfidence = prevDet.confidence,
                    currConfidence = currDet.confidence
                )
                val heightVel = (heightChange / dtSec)
                    .coerceIn(-HEIGHT_SIGNAL_MAX_VELOCITY, HEIGHT_SIGNAL_MAX_VELOCITY)

                accumulated.getOrPut(id) { AccumulatedMotion() }.apply {
                    velocitySumX += velX
                    velocitySumY += velY
                    sizeVelocitySum += sizeVel
                    heightVelocitySum += heightVel
                    pairCount++
                }
            }

            for (i in currIds.indices) {
                if (currIds[i] < 0) currIds[i] = nextId++
            }
            chainIds[f] = currIds

            // Estimate camera ego-motion for this pair.
            // Stationary anchors are preferred; all-object fallback is penalized.
            if (stationaryPairVelocities.size >= 2) {
                val stationaryWeightSum = stationaryPairVelocities
                    .sumOf { it.weight.toDouble() }
                    .toFloat()
                    .coerceAtLeast(0f)
                val mx = weightedMedian(
                    stationaryPairVelocities.map { it.x to it.weight }
                )
                val my = weightedMedian(
                    stationaryPairVelocities.map { it.y to it.weight }
                )
                val support = (stationaryWeightSum / 6f).coerceIn(0f, 1f)
                val purity = if (allPairVelocities.isNotEmpty()) {
                    (stationaryWeightSum / allPairVelocities.size.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val avgTrust = if (stationaryPairVelocities.isNotEmpty()) {
                    (stationaryWeightSum / stationaryPairVelocities.size.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val pairConfidence = (0.55f * support + 0.30f * purity + 0.15f * avgTrust)
                    .coerceIn(0f, 1f)
                perPairCameraMotions.add(
                    PairCameraMotion(
                        velocity = PointF(mx, my),
                        confidence = pairConfidence,
                        usedStationaryAnchors = true
                    )
                )
                Log.d(
                    TAG,
                    "Frame pair $f: stationary ego-motion support=${stationaryPairVelocities.size} " +
                        "weightedSupport=$stationaryWeightSum totalMatches=${allPairVelocities.size} conf=$pairConfidence"
                )
            } else if (allPairVelocities.size >= INTERNAL_MIN_ALL_MATCH_SUPPORT) {
                val mx = allPairVelocities.map { it.x }.sorted()[allPairVelocities.size / 2]
                val my = allPairVelocities.map { it.y }.sorted()[allPairVelocities.size / 2]
                val support = (allPairVelocities.size / 10f).coerceIn(0f, 1f)
                val pairConfidence = (support * INTERNAL_ALL_MATCH_PENALTY).coerceIn(0f, 1f)
                perPairCameraMotions.add(
                    PairCameraMotion(
                        velocity = PointF(mx, my),
                        confidence = pairConfidence,
                        usedStationaryAnchors = false
                    )
                )
                Log.d(
                    TAG,
                    "Frame pair $f: fallback ego-motion from all matches=${allPairVelocities.size} " +
                            "conf=$pairConfidence"
                )
            } else {
                Log.d(
                    TAG,
                    "Frame pair $f: insufficient compensation evidence " +
                            "(all=${allPairVelocities.size}, stationary=${stationaryPairVelocities.size})"
                )
            }
        }

        // Collapse pair-wise estimates into one internal camera velocity.
        val internalGlobalCamVel = if (perPairCameraMotions.isNotEmpty()) {
            PointF(
                perPairCameraMotions.map { it.velocity.x }.sorted()[perPairCameraMotions.size / 2],
                perPairCameraMotions.map { it.velocity.y }.sorted()[perPairCameraMotions.size / 2]
            )
        } else {
            PointF(0f, 0f)
        }

        val pairSlots = (numFrames - 1).coerceAtLeast(1)
        val pairCoverage = perPairCameraMotions.size.toFloat() / pairSlots.toFloat()
        val avgPairConfidence = if (perPairCameraMotions.isNotEmpty()) {
            perPairCameraMotions.map { it.confidence }.average().toFloat()
        } else {
            0f
        }
        val stationaryPairCount = perPairCameraMotions.count { it.usedStationaryAnchors }
        val stationaryRatio = if (perPairCameraMotions.isNotEmpty()) {
            stationaryPairCount.toFloat() / perPairCameraMotions.size.toFloat()
        } else {
            0f
        }
        val internalConfidence = (
            avgPairConfidence * pairCoverage * (0.5f + 0.5f * stationaryRatio)
        ).coerceIn(0f, 1f)

        // Blend optional external estimate with internal detection-based estimate.
        // Priority: strong external > blended > strong internal > none.
        val externalConf = externalCameraConfidence.coerceIn(0f, 1f)
        val externalVel = externalCameraVelocity ?: PointF(0f, 0f)
        val externalUsable = externalCameraVelocity != null && externalConf >= EXTERNAL_MIN_CONFIDENCE
        val internalHasStationary = stationaryPairCount > 0
        val internalUsable = internalConfidence >= INTERNAL_MIN_CONFIDENCE &&
            (internalHasStationary || internalConfidence >= INTERNAL_STRONG_CONFIDENCE)

        val compensationSource: String
        val globalCamVel = when {
            externalUsable && externalConf >= EXTERNAL_STRONG_CONFIDENCE -> {
                compensationSource = "external-strong"
                externalVel
            }

            externalUsable && internalUsable -> {
                val totalConf = (externalConf + internalConfidence).coerceAtLeast(1e-6f)
                val wExternal = externalConf / totalConf
                val wInternal = 1f - wExternal
                compensationSource = "blended"
                PointF(
                    internalGlobalCamVel.x * wInternal + externalVel.x * wExternal,
                    internalGlobalCamVel.y * wInternal + externalVel.y * wExternal
                )
            }

            externalUsable -> {
                compensationSource = "external-only"
                externalVel
            }

            internalUsable -> {
                compensationSource = if (internalHasStationary) "internal-stationary" else "internal-fallback"
                internalGlobalCamVel
            }

            else -> {
                compensationSource = "none"
                PointF(0f, 0f)
            }
        }

        val compensationReliability = when (compensationSource) {
            "external-strong" -> externalConf
            "blended" -> maxOf(externalConf, internalConfidence)
            "external-only" -> externalConf * 0.90f
            "internal-stationary" -> internalConfidence * 0.85f
            "internal-fallback" -> internalConfidence * 0.60f
            else -> 0f
        }.coerceIn(0f, 1f)
        val compensationReliable = compensationReliability >= MIN_COMPENSATION_RELIABILITY_FOR_DIRECTIONAL

        Log.d(
            TAG,
            "Camera velocity: internal=(${internalGlobalCamVel.x}, ${internalGlobalCamVel.y}) " +
                    "internalConf=$internalConfidence pairCoverage=$pairCoverage " +
                    "stationaryPairs=$stationaryPairCount/${perPairCameraMotions.size} " +
                    "external=${externalCameraVelocity?.let { "(${it.x}, ${it.y})" } ?: "none"} " +
                    "conf=$externalConf source=$compensationSource compReliability=$compensationReliability " +
                    "compReliable=$compensationReliable final=(${globalCamVel.x}, ${globalCamVel.y})"
        )

        // ---- Assign persistent IDs using cross-burst registry ----
        val lastDets = frameDetections[numFrames - 1]
        val lastIds = chainIds[numFrames - 1]
        val burstTimestamp = frameTimestamps.last()

        // Drop stale registry entries and related state.
        val expiredPids = persistentRegistry.entries
            .filter { burstTimestamp - it.value.lastSeenMs > REGISTRY_STALE_MS }
            .map { it.key }
        expiredPids.forEach { pid ->
            persistentRegistry.remove(pid)
            kalmanStates.remove(pid)
            stateHistory.remove("persistent:$pid")
            lastConfirmedStateByPersistent.remove(pid)
            distanceTrendByPersistent.remove(pid)
        }

        val registryBeforeKeys = persistentRegistry.keys.toSet()
        val registryBeforeLabels = persistentRegistry.values.map { it.label }.toSet()

        // Map each final-frame detection to a persistent ID.
        // First pass tries matching against existing registry entries.
        val persistentIds = matchToPersistentRegistry(lastDets, burstTimestamp)
        val registryNewCount = persistentIds.count { it !in registryBeforeKeys }
        val registryMatchedCount = persistentIds.size - registryNewCount
        val labelChurnLikeCount = lastDets.indices.count { idx ->
            val pid = persistentIds[idx]
            (pid !in registryBeforeKeys) && (lastDets[idx].label in registryBeforeLabels)
        }

        data class ChainObservation(
            val timestampMs: Long,
            val cx: Float,
            val cy: Float,
            val area: Float,
            val height: Float
        )

        val chainObservations = mutableMapOf<Int, MutableList<ChainObservation>>()
        for (f in 0 until numFrames) {
            val ts = frameTimestamps.getOrElse(f) { burstTimestamp }
            val frameDets = frameDetections[f]
            val frameIds = chainIds[f]
            for (di in frameDets.indices) {
                val cid = frameIds.getOrElse(di) { -1 }
                if (cid < 0) continue
                val bb = frameDets[di].boundingBox
                chainObservations.getOrPut(cid) { mutableListOf() }.add(
                    ChainObservation(
                        timestampMs = ts,
                        cx = (bb.left + bb.right) / 2f,
                        cy = (bb.top + bb.bottom) / 2f,
                        area = areaOf(bb),
                        height = heightOf(bb)
                    )
                )
            }
        }

        // ---- Update Kalman filters for each tracked object ----
        for (idx in lastDets.indices) {
            val pid = persistentIds[idx]
            val det = lastDets[idx]
            val chainId = lastIds[idx]
            val cx = (det.boundingBox.left + det.boundingBox.right) / 2f
            val cy = (det.boundingBox.top + det.boundingBox.bottom) / 2f
            val area = areaOf(det.boundingBox)
            val observations = chainObservations[chainId].orEmpty().sortedBy { it.timestampMs }

            val seed = observations.firstOrNull()

            val kState = kalmanStates.getOrPut(pid) {
                KalmanObjectState().also {
                    if (seed != null) {
                        it.initialize(seed.cx, seed.cy, seed.area, seed.timestampMs)
                    } else {
                        it.initialize(cx, cy, area, burstTimestamp)
                    }
                }
            }

            if (!kState.initialized) {
                if (seed != null) {
                    kState.initialize(seed.cx, seed.cy, seed.area, seed.timestampMs)
                } else {
                    kState.initialize(cx, cy, area, burstTimestamp)
                }
            } else {
                if (observations.isNotEmpty()) {
                    var prevObs: ChainObservation? = null
                    for (obs in observations) {
                        if (obs.timestampMs > kState.lastUpdateMs) {
                            kState.update(obs.cx, obs.cy, obs.area, obs.timestampMs)
                        }
                        if (prevObs != null) {
                            val dtSec = maxOf((obs.timestampMs - prevObs.timestampMs) / 1000f, 0.010f)
                            val velX = (obs.cx - prevObs.cx) / dtSec
                            val velY = (obs.cy - prevObs.cy) / dtSec
                            val logAreaRate = ln(
                                (obs.area.coerceAtLeast(1e-6f) / prevObs.area.coerceAtLeast(1e-6f)).toDouble()
                            ).toFloat() / dtSec
                            val logHeightRate = ln(
                                (obs.height.coerceAtLeast(1e-6f) / prevObs.height.coerceAtLeast(1e-6f)).toDouble()
                            ).toFloat() / dtSec
                            val depthRate = blendDepthRates(logAreaRate, logHeightRate)
                            kState.updateWithVelocity(
                                velX,
                                velY,
                                depthRate.coerceIn(-SIZE_SIGNAL_MAX_VELOCITY, SIZE_SIGNAL_MAX_VELOCITY)
                            )
                        }
                        prevObs = obs
                    }
                } else {
                    kState.update(cx, cy, area, burstTimestamp)
                }
            }

            // Fallback velocity update when trajectory support is thin.
            val motion = accumulated[chainId]
            if (observations.size < 2 && motion != null && motion.pairCount > 0) {
                kState.updateWithVelocity(motion.avgVelX, motion.avgVelY, motion.avgDepthVel)
            }
        }

        // ---- Classify motion for each detection in the final frame ----
        val diagnostics = MotionDiagnosticsAccumulator()
        val trackedDetections = mutableListOf<TrackedDetection>()

        for ((idx, det) in lastDets.withIndex()) {
            val chainId = lastIds[idx]
            val pid = persistentIds[idx]
            val motion = accumulated[chainId]
            val chainObsCount = chainObservations[chainId]?.size ?: 0

            val tracked: TrackedDetection

            if (motion != null && motion.pairCount > 0) {
                // Prefer Kalman-smoothed velocity; otherwise use raw burst averages.
                val residualVelX: Float
                val residualVelY: Float
                val sizeVel: Float
                val heightVel: Float
                val depthVel: Float
                val confidence: Float
                val kState = kalmanStates[pid]

                if (kState != null && kState.initialized) {
                    // Kalman is in raw image coords, so subtract camera velocity.
                    residualVelX = kState.smoothedVelX - globalCamVel.x
                    residualVelY = kState.smoothedVelY - globalCamVel.y
                    sizeVel = kState.smoothedAreaRate
                    heightVel = motion.avgHeightVel
                    depthVel = blendDepthRates(sizeVel, heightVel)
                    confidence = kState.velocityConfidence
                } else {
                    residualVelX = motion.avgVelX - globalCamVel.x
                    residualVelY = motion.avgVelY - globalCamVel.y
                    sizeVel = motion.avgSizeVel
                    heightVel = motion.avgHeightVel
                    depthVel = motion.avgDepthVel
                    confidence = 1.0f
                }

                val residualSpeed = sqrt(residualVelX * residualVelX + residualVelY * residualVelY)
                // Convert speed back to reference-frame magnitude for output.
                val residualMag = residualSpeed * REFERENCE_DT_S
                val motionTrust = motionTrust(det.label)

                val rawState = classifyMotion(
                    residualSpeed,
                    residualVelX,
                    residualVelY,
                    depthVel,
                    confidence,
                    motionTrust
                )
                val evidenceState = degradeMotionStateForEvidence(
                    rawState = rawState,
                    residualSpeed = residualSpeed,
                    pairSupport = motion.pairCount,
                    observationCount = chainObsCount,
                    compensationReliable = compensationReliable
                )
                diagnostics.raw.record(rawState)
                diagnostics.evidence.record(evidenceState)
                diagnostics.recordSupport(motion.pairCount, chainObsCount)
                if (rawState != MotionState.UNKNOWN && evidenceState == MotionState.UNKNOWN) {
                    diagnostics.degradedToUnknownCount++
                }

                Log.d(TAG, "${det.label}[pid=$pid, chain=$chainId]: residualSpeed=$residualSpeed, " +
                           "velX=$residualVelX, velY=$residualVelY, depthVel=$depthVel, " +
                           "sizeVel=$sizeVel, heightVel=$heightVel, " +
                           "confidence=$confidence trust=$motionTrust pairSupport=${motion.pairCount} obs=$chainObsCount " +
                           "compReliable=$compensationReliable → raw=$rawState final=$evidenceState")

                val hysteresis = applyHysteresis(det, pid, evidenceState, residualMag)
                tracked = hysteresis.tracked
                if (hysteresis.flipGuardSuppressed) {
                    diagnostics.flipGuardSuppressedCount++
                }
                if (evidenceState != MotionState.UNKNOWN && tracked.motionState == MotionState.UNKNOWN) {
                    diagnostics.hysteresisPendingCount++
                }
            } else {
                diagnostics.noMotionSupportCount++
                diagnostics.raw.record(MotionState.UNKNOWN)
                diagnostics.evidence.record(MotionState.UNKNOWN)
                val hysteresis = applyHysteresis(det, pid, MotionState.UNKNOWN, 0f)
                tracked = hysteresis.tracked
            }

            diagnostics.confirmed.record(tracked.motionState)
            diagnostics.recordTransition(lastConfirmedStateByPersistent[pid], tracked.motionState)
            lastConfirmedStateByPersistent[pid] = tracked.motionState
            trackedDetections.add(tracked)
        }

        Log.i(
            TAG,
            "MOTION_DIAG burst=$burstDiagId frames=$numFrames detections=${lastDets.size} " +
                "raw=[${diagnostics.raw.compact()}] " +
                "evidence=[${diagnostics.evidence.compact()}] " +
                "confirmed=[${diagnostics.confirmed.compact()}] " +
                "pairSupportAvg=${diagnostics.pairSupportAvg()} pairSupportMin=${diagnostics.pairSupportMinSafe()} pairSupportMax=${diagnostics.pairSupportMax} " +
                "obsAvg=${diagnostics.observationAvg()} obsMin=${diagnostics.observationMinSafe()} obsMax=${diagnostics.observationMax} " +
                "registryMatched=$registryMatchedCount registryNew=$registryNewCount labelChurnLike=$labelChurnLikeCount " +
                "degradedToUnknown=${diagnostics.degradedToUnknownCount} hysteresisPending=${diagnostics.hysteresisPendingCount} noMotionSupport=${diagnostics.noMotionSupportCount} " +
                "flipGuardSuppressed=${diagnostics.flipGuardSuppressedCount} " +
                "transitions=${diagnostics.transitionCount} flipAppToAway=${diagnostics.approachToAwayCount} flipAwayToApp=${diagnostics.awayToApproachCount} " +
                "compSource=$compensationSource compReliable=$compensationReliable"
        )

        return trackedDetections
    }

    private fun approximateMotionFromHistory(
        burstDiagId: Long,
        detections: List<ObjectDetector.Detection>,
        burstTimestamp: Long,
        reason: String
    ): List<TrackedDetection> {
        if (detections.isEmpty()) {
            Log.i(
                TAG,
                "MOTION_DIAG burst=$burstDiagId shortBurst=true reason=$reason detections=0"
            )
            return emptyList()
        }

        val expiredPids = persistentRegistry.entries
            .filter { burstTimestamp - it.value.lastSeenMs > REGISTRY_STALE_MS }
            .map { it.key }
        expiredPids.forEach { pid ->
            persistentRegistry.remove(pid)
            kalmanStates.remove(pid)
            stateHistory.remove("persistent:$pid")
            lastConfirmedStateByPersistent.remove(pid)
            distanceTrendByPersistent.remove(pid)
        }

        val persistentIds = matchToPersistentRegistry(detections, burstTimestamp)

        val tracked = detections.mapIndexed { index, det ->
            val pid = persistentIds[index]
            val history = stateHistory["persistent:$pid"]
            val approximatedState = when {
                lastConfirmedStateByPersistent.containsKey(pid) ->
                    lastConfirmedStateByPersistent[pid] ?: MotionState.UNKNOWN
                history?.lastState != null && history.lastState != MotionState.UNKNOWN ->
                    history.lastState
                else -> MotionState.UNKNOWN
            }

            val motionMagnitude = if (approximatedState == MotionState.UNKNOWN) {
                0f
            } else {
                VELOCITY_THRESHOLD * REFERENCE_DT_S
            }

            val centerX = (det.boundingBox.left + det.boundingBox.right) / 2f
            val centerY = (det.boundingBox.top + det.boundingBox.bottom) / 2f
            val area = areaOf(det.boundingBox)
            val kState = kalmanStates.getOrPut(pid) { KalmanObjectState() }
            if (!kState.initialized) {
                kState.initialize(centerX, centerY, area, burstTimestamp)
            } else {
                kState.update(centerX, centerY, area, burstTimestamp)
            }

            lastConfirmedStateByPersistent[pid] = approximatedState

            TrackedDetection(
                label = det.label,
                confidence = det.confidence,
                boundingBox = det.boundingBox,
                classIndex = det.classIndex,
                motionState = approximatedState,
                motionMagnitude = motionMagnitude,
                trackId = pid
            )
        }

        val approximatedKnown = tracked.count { it.motionState != MotionState.UNKNOWN }
        Log.i(
            TAG,
            "MOTION_DIAG burst=$burstDiagId shortBurst=true reason=$reason detections=${detections.size} " +
                "approximated_known=$approximatedKnown registry_size=${persistentRegistry.size}"
        )

        return tracked
    }

    // ------------------------------------------------------------------
    // Cross-burst persistent registry matching.
    // ------------------------------------------------------------------
    /**
     * Match final-frame detections against the persistent registry.
     * Uses Hungarian assignment with looser thresholds because objects can
     * move a lot in ~30s. Matched detections keep their persistent IDs,
     * unmatched detections get new ones.
     */
    private fun matchToPersistentRegistry(
        detections: List<ObjectDetector.Detection>,
        timestampMs: Long
    ): IntArray {
        val pids = IntArray(detections.size) { -1 }

        if (persistentRegistry.isEmpty()) {
            // First burst: just assign fresh persistent IDs.
            for (i in detections.indices) {
                val pid = nextPersistentId++
                pids[i] = pid
                persistentRegistry[pid] = PersistentEntry(
                    classIndex = detections[i].classIndex,
                    label = detections[i].label,
                    boundingBox = RectF(detections[i].boundingBox),
                    lastSeenMs = timestampMs
                )
            }
            Log.d(TAG, "Registry init: ${detections.size} objects, next pid=$nextPersistentId")
            return pids
        }

        // Build cost matrix [detIdx][registrySlot].
        val regEntries = persistentRegistry.entries.toList()  // stable order
        val nDet = detections.size
        val nReg = regEntries.size
        val n = maxOf(nDet, nReg)
        val INF = 1e9f
        val cost = Array(n) { FloatArray(n) { INF } }

        for (di in detections.indices) {
            for (ri in regEntries.indices) {
                val entry = regEntries[ri].value
                val compatibility = LabelSemantics.compatibility(
                    leftLabel = detections[di].label,
                    leftClassIndex = detections[di].classIndex,
                    rightLabel = entry.label,
                    rightClassIndex = entry.classIndex
                )

                // If Kalman is available, use predicted position for matching.
                val kState = kalmanStates[regEntries[ri].key]
                val matchBox = if (kState != null && kState.initialized) {
                    val predicted = kState.predictPosition(timestampMs)
                    val oldCx = (entry.boundingBox.left + entry.boundingBox.right) / 2f
                    val oldCy = (entry.boundingBox.top + entry.boundingBox.bottom) / 2f
                    val dx = predicted.x - oldCx
                    val dy = predicted.y - oldCy
                    RectF(
                        entry.boundingBox.left + dx, entry.boundingBox.top + dy,
                        entry.boundingBox.right + dx, entry.boundingBox.bottom + dy
                    )
                } else {
                    entry.boundingBox
                }

                val iou = computeIoU(detections[di].boundingBox, matchBox)
                val dist = centroidDistance(
                    centroidOf(detections[di].boundingBox),
                    centroidOf(matchBox)
                )

                if (compatibility == LabelSemantics.Compatibility.MISMATCH &&
                    iou < CROSS_BURST_CLASS_FLICKER_IOU_THRESHOLD &&
                    dist > CROSS_BURST_CLASS_FLICKER_CENTER_THRESHOLD
                ) {
                    continue
                }

                val iouThreshold = compatibilityIouThreshold(
                    compatibility = compatibility,
                    exactThreshold = CROSS_BURST_IOU_THRESHOLD,
                    hierarchyThreshold = CROSS_BURST_HIERARCHY_IOU_THRESHOLD
                )
                val centroidThreshold = compatibilityCentroidThreshold(
                    compatibility = compatibility,
                    exactThreshold = CROSS_BURST_CENTROID_THRESHOLD,
                    hierarchyThreshold = CROSS_BURST_HIERARCHY_CENTROID_THRESHOLD
                )

                val baseCost = when {
                    iou >= iouThreshold -> 1f - iou
                    dist < centroidThreshold -> 1f + dist
                    else -> continue
                }

                val sizePenalty = abs(relativeSizeChange(matchBox, detections[di].boundingBox))
                    .coerceAtMost(2f) * CROSS_BURST_SIZE_CHANGE_WEIGHT
                val aspectPenalty = relativeAspectChange(matchBox, detections[di].boundingBox)
                    .coerceAtMost(2f) * CROSS_BURST_ASPECT_CHANGE_WEIGHT
                val confPenalty = (1f - detections[di].confidence.coerceIn(0f, 1f)) *
                    CROSS_BURST_CONFIDENCE_PENALTY_WEIGHT
                val ageMs = (timestampMs - entry.lastSeenMs).coerceAtLeast(0L).toFloat()
                val agePenalty = (ageMs / REGISTRY_STALE_MS.toFloat())
                    .coerceIn(0f, 1f) * CROSS_BURST_AGE_PENALTY_WEIGHT
                val classPenalty = compatibilityPenalty(
                    compatibility = compatibility,
                    mismatchPenalty = CROSS_BURST_CLASS_MISMATCH_PENALTY,
                    hierarchyPenalty = CROSS_BURST_CLASS_HIERARCHY_PENALTY
                )
                val pairTrust = pairMotionTrust(detections[di].label, entry.label)
                val trustPenalty = trustBiasPenalty(pairTrust, CROSS_BURST_MOTION_TRUST_BIAS_WEIGHT)

                cost[di][ri] =
                    baseCost + sizePenalty + aspectPenalty + confPenalty + agePenalty + classPenalty + trustPenalty
            }
        }

        val assignment = hungarianAssignment(cost, n)

        // Assign persistent IDs from accepted matches.
        for (di in detections.indices) {
            val ri = assignment[di]
            if (ri < nReg && cost[di][ri] < INF && cost[di][ri] <= CROSS_BURST_MAX_ACCEPT_COST) {
                val pid = regEntries[ri].key
                pids[di] = pid
                // Update registry entry with current detection.
                persistentRegistry[pid] = PersistentEntry(
                    classIndex = detections[di].classIndex,
                    label = detections[di].label,
                    boundingBox = RectF(detections[di].boundingBox),
                    lastSeenMs = timestampMs
                )
                Log.d(TAG, "Registry match: ${detections[di].label} → pid=$pid " +
                           "(cost=${cost[di][ri]})")
            }
        }

        // Assign new persistent IDs to unmatched detections.
        for (di in detections.indices) {
            if (pids[di] < 0) {
                val pid = nextPersistentId++
                pids[di] = pid
                persistentRegistry[pid] = PersistentEntry(
                    classIndex = detections[di].classIndex,
                    label = detections[di].label,
                    boundingBox = RectF(detections[di].boundingBox),
                    lastSeenMs = timestampMs
                )
                Log.d(TAG, "Registry new: ${detections[di].label} → pid=$pid")
            }
        }

        return pids
    }

    // ------------------------------------------------------------------
    // Temporal hysteresis.
    // ------------------------------------------------------------------
    private data class HysteresisResult(
        val tracked: TrackedDetection,
        val flipGuardSuppressed: Boolean
    )

     /**
      * A new state only gets promoted after it appears for
      * [HYSTERESIS_COUNT] consecutive bursts. Until then, we keep
      * the previous confirmed state (or UNKNOWN).
      *
      * Keys use persistent IDs so the same object keeps its streak across bursts.
      */
    private fun applyHysteresis(
        det: ObjectDetector.Detection,
        persistentId: Int,
        rawState: MotionState,
        magnitude: Float
    ): HysteresisResult {
        val key = "persistent:$persistentId"
        val history = stateHistory.getOrPut(key) { StateHistory(MotionState.UNKNOWN, 0) }
        val previousConfirmed = lastConfirmedStateByPersistent[persistentId]
        val depthFlipRequested =
            (previousConfirmed == MotionState.APPROACHING && rawState == MotionState.MOVING_AWAY) ||
                (previousConfirmed == MotionState.MOVING_AWAY && rawState == MotionState.APPROACHING)
        val requiredStreak = if (depthFlipRequested) {
            HYSTERESIS_DEPTH_FLIP_COUNT
        } else {
            HYSTERESIS_COUNT
        }

        val confirmedState = if (rawState == history.lastState) {
            history.streak++
            if (history.streak >= requiredStreak) rawState else MotionState.UNKNOWN
        } else {
            history.lastState = rawState
            history.streak = 1
            if (requiredStreak <= 1) rawState else MotionState.UNKNOWN
        }

        if (depthFlipRequested && confirmedState == MotionState.UNKNOWN) {
            Log.d(
                TAG,
                "Depth flip guard: pid=$persistentId prev=$previousConfirmed raw=$rawState " +
                    "streak=${history.streak}/$requiredStreak"
            )
        }

        return HysteresisResult(
            tracked = TrackedDetection(
            label = det.label,
            confidence = det.confidence,
            boundingBox = det.boundingBox,
            classIndex = det.classIndex,
            motionState = confirmedState,
            motionMagnitude = magnitude,
            trackId = persistentId
            ),
            flipGuardSuppressed = depthFlipRequested && confirmedState == MotionState.UNKNOWN
        )
    }

    // ------------------------------------------------------------------
    // Motion classification (velocity-based thresholds).
    // ------------------------------------------------------------------
    /**
     * Classify object motion from residual (camera-compensated) velocity.
     *
     * @param confidence Kalman velocity confidence (0-1). Low confidence
     * raises the threshold so noisy estimates do not trigger false positives.
     */
    private fun classifyMotion(
        residualSpeed: Float,
        residualVelX: Float,
        residualVelY: Float,
        depthVelocity: Float,
        confidence: Float = 1.0f,
        motionTrust: Float = 1.0f
    ): MotionState {
        val trustedConfidence = (
            confidence.coerceIn(0f, 1f) * motionTrust.coerceIn(MOTION_TRUST_MIN, MOTION_TRUST_MAX)
            ).coerceIn(0.15f, 1.25f)

        // Lower confidence means we require stronger motion.
        val adjustedThreshold = VELOCITY_THRESHOLD / maxOf(trustedConfidence, 0.2f)
        if (residualSpeed < adjustedThreshold) return MotionState.STATIONARY

        // Weight depth velocity by confidence for approach/retreat.
        val effectiveDepthVel = depthVelocity * trustedConfidence
        val absX = abs(residualVelX)
        val absY = abs(residualVelY)

        // Strong depth cue can decide approach/retreat directly.
        if (effectiveDepthVel > SIZE_VELOCITY_APPROACH) return MotionState.APPROACHING
        if (effectiveDepthVel < SIZE_VELOCITY_RETREAT) return MotionState.MOVING_AWAY

        // With low confidence, require at least soft depth evidence.
        if (trustedConfidence < MIN_CONFIDENCE_FOR_DIRECTIONAL &&
            abs(effectiveDepthVel) < SIZE_VELOCITY_APPROACH_SOFT
        ) {
            return MotionState.UNKNOWN
        }

        val lateralDominant = absX >= absY * DIRECTION_DOMINANCE_RATIO
        val strongLateral = absX >= adjustedThreshold * MIN_LATERAL_COMPONENT_RATIO

        return when {
            lateralDominant && strongLateral -> {
                if (residualVelX < 0) MotionState.CROSSING_LEFT else MotionState.CROSSING_RIGHT
            }
            effectiveDepthVel > SIZE_VELOCITY_APPROACH_SOFT -> MotionState.APPROACHING
            effectiveDepthVel < SIZE_VELOCITY_RETREAT_SOFT -> MotionState.MOVING_AWAY
            else -> MotionState.UNKNOWN
        }
    }

    private fun degradeMotionStateForEvidence(
        rawState: MotionState,
        residualSpeed: Float,
        pairSupport: Int,
        observationCount: Int,
        compensationReliable: Boolean
    ): MotionState {
        val lowTrajectorySupport =
            observationCount < MIN_CHAIN_OBSERVATIONS_FOR_MOTION ||
                pairSupport < MIN_CHAIN_PAIR_SUPPORT
        if (lowTrajectorySupport) {
            return MotionState.UNKNOWN
        }

        val depthDirectional = rawState == MotionState.APPROACHING ||
            rawState == MotionState.MOVING_AWAY
        if (depthDirectional &&
            (observationCount < MIN_CHAIN_OBSERVATIONS_FOR_DEPTH_DIRECTIONAL ||
                pairSupport < MIN_CHAIN_PAIR_SUPPORT_FOR_DEPTH_DIRECTIONAL)
        ) {
            return MotionState.UNKNOWN
        }

        val lateralDirectional = rawState == MotionState.CROSSING_LEFT ||
            rawState == MotionState.CROSSING_RIGHT

        if (lateralDirectional && !compensationReliable &&
            residualSpeed < VELOCITY_THRESHOLD * NO_COMP_STRONG_MOTION_FACTOR
        ) {
            return MotionState.UNKNOWN
        }

        return rawState
    }

    // ------------------------------------------------------------------
    // Detection matching: IoU first, centroid fallback.
    // ------------------------------------------------------------------
    private data class MatchResult(
        val prev: ObjectDetector.Detection,
        val curr: ObjectDetector.Detection,
        val currIdx: Int,
        val prevIdx: Int
    )

     /**
      * Match detections between consecutive frames using global assignment
      * (Hungarian) and a combined cost of IoU, centroid distance, and
      * geometry consistency penalties.
      *
      * This avoids the order-dependent mistakes that greedy matching can make.
      */
    private fun matchDetections(
        prev: List<ObjectDetector.Detection>,
        curr: List<ObjectDetector.Detection>
    ): List<MatchResult> {
        if (prev.isEmpty() || curr.isEmpty()) return emptyList()

        // Build cost matrix [currIdx][prevIdx] with IoU-first matching,
        // centroid fallback, and extra penalties for weak consistency.
        val INF = 1e9f
        val nCurr = curr.size
        val nPrev = prev.size
        val n = maxOf(nCurr, nPrev)           // pad to square
        val cost = Array(n) { FloatArray(n) { INF } }

        for (ci in curr.indices) {
            for (pi in prev.indices) {
                val compatibility = classCompatibility(curr[ci], prev[pi])

                val iou = computeIoU(curr[ci].boundingBox, prev[pi].boundingBox)
                val dist = centroidDistance(
                    centroidOf(prev[pi].boundingBox),
                    centroidOf(curr[ci].boundingBox)
                )

                if (compatibility == LabelSemantics.Compatibility.MISMATCH &&
                    iou < MATCH_CLASS_FLICKER_IOU_THRESHOLD &&
                    dist > MATCH_CLASS_FLICKER_CENTER_THRESHOLD
                ) {
                    continue
                }

                val iouThreshold = compatibilityIouThreshold(
                    compatibility = compatibility,
                    exactThreshold = MATCH_IOU_THRESHOLD,
                    hierarchyThreshold = MATCH_HIERARCHY_IOU_THRESHOLD
                )
                val centroidThreshold = compatibilityCentroidThreshold(
                    compatibility = compatibility,
                    exactThreshold = CENTROID_MATCH_THRESHOLD,
                    hierarchyThreshold = MATCH_HIERARCHY_CENTROID_THRESHOLD
                )

                val baseCost = when {
                    iou >= iouThreshold -> 1f - iou
                    dist < centroidThreshold -> 1f + dist
                    else -> continue
                }

                val sizePenalty = abs(relativeSizeChange(prev[pi].boundingBox, curr[ci].boundingBox))
                    .coerceAtMost(2f) * MATCH_SIZE_CHANGE_WEIGHT
                val aspectPenalty = relativeAspectChange(prev[pi].boundingBox, curr[ci].boundingBox)
                    .coerceAtMost(2f) * MATCH_ASPECT_CHANGE_WEIGHT
                val avgConf = ((curr[ci].confidence + prev[pi].confidence) * 0.5f).coerceIn(0f, 1f)
                val confPenalty = (1f - avgConf) * MATCH_CONFIDENCE_PENALTY_WEIGHT
                val classPenalty = compatibilityPenalty(
                    compatibility = compatibility,
                    mismatchPenalty = MATCH_CLASS_MISMATCH_PENALTY,
                    hierarchyPenalty = MATCH_CLASS_HIERARCHY_PENALTY
                )
                val pairTrust = pairMotionTrust(curr[ci].label, prev[pi].label)
                val trustPenalty = trustBiasPenalty(pairTrust, MATCH_MOTION_TRUST_BIAS_WEIGHT)

                cost[ci][pi] = baseCost + sizePenalty + aspectPenalty + confPenalty + classPenalty + trustPenalty
            }
        }

        // Run Hungarian algorithm on the padded square matrix.
        val assignment = hungarianAssignment(cost, n)

        // Collect valid matches.
        val result = mutableListOf<MatchResult>()
        for (ci in curr.indices) {
            val pi = assignment[ci]
            if (pi < nPrev && cost[ci][pi] < INF && cost[ci][pi] <= MATCH_MAX_ACCEPT_COST) {
                result.add(MatchResult(prev[pi], curr[ci], ci, pi))
                if (cost[ci][pi] > 1f) {
                    Log.d(TAG, "Centroid fallback matched ${curr[ci].label} (cost=${cost[ci][pi]})")
                }
                val compatibility = classCompatibility(curr[ci], prev[pi])
                if (compatibility == LabelSemantics.Compatibility.HIERARCHY) {
                    Log.d(
                        TAG,
                        "Hierarchy-compatible match ${prev[pi].label}→${curr[ci].label} " +
                            "(cost=${cost[ci][pi]})"
                    )
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // Hungarian (Kuhn-Munkres) algorithm, O(n^3).
    // Returns assignment[row] = col for a square n x n cost matrix.
    // ------------------------------------------------------------------
    private fun hungarianAssignment(cost: Array<FloatArray>, n: Int): IntArray {
        // u/v are potentials; p/way track the augmenting path.
        val u = FloatArray(n + 1)
        val v = FloatArray(n + 1)
        val p = IntArray(n + 1)                   // p[j] = row assigned to col j
        val way = IntArray(n + 1)
        val INF = 1e18f

        for (i in 1..n) {
            val minV = FloatArray(n + 1) { INF }
            val used = BooleanArray(n + 1)
            p[0] = i
            var j0 = 0

            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = INF
                var j1 = 0

                for (j in 1..n) {
                    if (used[j]) continue
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minV[j]) {
                        minV[j] = cur
                        way[j] = j0
                    }
                    if (minV[j] < delta) {
                        delta = minV[j]
                        j1 = j
                    }
                }

                for (j in 0..n) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minV[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)

            // Unwind the augmenting path.
            while (j0 != 0) {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            }
        }

        // Build row-to-col assignment (0-indexed).
        val ans = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] > 0) ans[p[j] - 1] = j - 1
        }
        return ans
    }

    // ------------------------------------------------------------------
    // Geometry helpers.
    // ------------------------------------------------------------------
    private fun centroidOf(r: RectF) = PointF((r.left + r.right) / 2, (r.top + r.bottom) / 2)
    private fun areaOf(r: RectF) = (r.right - r.left) * (r.bottom - r.top)
    private fun heightOf(r: RectF) = (r.bottom - r.top).coerceAtLeast(1e-6f)

    private fun centroidShift(prev: RectF, curr: RectF): PointF {
        val pc = centroidOf(prev)
        val cc = centroidOf(curr)
        return PointF(cc.x - pc.x, cc.y - pc.y)
    }

    /** Normalized Euclidean distance between two centroids (0-1 scale). */
    private fun centroidDistance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun weightedMedian(values: List<Pair<Float, Float>>): Float {
        if (values.isEmpty()) return 0f

        val sorted = values
            .map { it.first to it.second.coerceAtLeast(0f) }
            .sortedBy { it.first }
        val totalWeight = sorted
            .sumOf { it.second.toDouble() }
            .toFloat()
            .coerceAtLeast(1e-6f)

        var accumulated = 0f
        val target = totalWeight * 0.5f
        for ((value, weight) in sorted) {
            accumulated += weight
            if (accumulated >= target) return value
        }
        return sorted.last().first
    }

    private fun classCompatibility(
        left: ObjectDetector.Detection,
        right: ObjectDetector.Detection
    ): LabelSemantics.Compatibility {
        return LabelSemantics.compatibility(
            leftLabel = left.label,
            leftClassIndex = left.classIndex,
            rightLabel = right.label,
            rightClassIndex = right.classIndex
        )
    }

    private fun compatibilityPenalty(
        compatibility: LabelSemantics.Compatibility,
        mismatchPenalty: Float,
        hierarchyPenalty: Float
    ): Float {
        return when (compatibility) {
            LabelSemantics.Compatibility.EXACT -> 0f
            LabelSemantics.Compatibility.HIERARCHY -> hierarchyPenalty
            LabelSemantics.Compatibility.MISMATCH -> mismatchPenalty
        }
    }

    private fun compatibilityIouThreshold(
        compatibility: LabelSemantics.Compatibility,
        exactThreshold: Float,
        hierarchyThreshold: Float
    ): Float {
        return when (compatibility) {
            LabelSemantics.Compatibility.HIERARCHY -> hierarchyThreshold
            else -> exactThreshold
        }
    }

    private fun compatibilityCentroidThreshold(
        compatibility: LabelSemantics.Compatibility,
        exactThreshold: Float,
        hierarchyThreshold: Float
    ): Float {
        return when (compatibility) {
            LabelSemantics.Compatibility.HIERARCHY -> hierarchyThreshold
            else -> exactThreshold
        }
    }

    private fun motionTrust(label: String): Float {
        return LabelSemantics.motionTrust(label).coerceIn(MOTION_TRUST_MIN, MOTION_TRUST_MAX)
    }

    private fun pairMotionTrust(leftLabel: String, rightLabel: String): Float {
        return ((motionTrust(leftLabel) + motionTrust(rightLabel)) * 0.5f)
            .coerceIn(MOTION_TRUST_MIN, MOTION_TRUST_MAX)
    }

    private fun trustBiasPenalty(pairTrust: Float, weight: Float): Float {
        val normalizedTrust = pairTrust.coerceIn(MOTION_TRUST_MIN, MOTION_TRUST_MAX)
        return ((1f - normalizedTrust) * weight)
    }

    private fun relativeSizeChange(prev: RectF, curr: RectF): Float {
        val pa = areaOf(prev)
        val ca = areaOf(curr)
        return if (pa > 0.0001f) (ca - pa) / pa else 0f
    }

    private fun stabilizedSizeChange(
        prev: RectF,
        curr: RectF,
        prevConfidence: Float,
        currConfidence: Float
    ): Float {
        val prevArea = areaOf(prev).coerceAtLeast(1e-6f)
        val currArea = areaOf(curr).coerceAtLeast(1e-6f)
        val meanArea = ((prevArea + currArea) * 0.5f).coerceAtMost(1f)
        if (meanArea < SIZE_SIGNAL_MIN_AREA) return 0f

        val meanConfidence = ((prevConfidence + currConfidence) * 0.5f).coerceIn(0f, 1f)
        if (meanConfidence < SIZE_SIGNAL_LOW_CONFIDENCE_SUPPRESS) return 0f

        val logAreaDelta = ln((currArea / prevArea).toDouble()).toFloat()
            .coerceIn(-SIZE_SIGNAL_LOG_CLAMP, SIZE_SIGNAL_LOG_CLAMP)

        val areaDenom = (SIZE_SIGNAL_FULL_AREA - SIZE_SIGNAL_MIN_AREA).coerceAtLeast(1e-4f)
        val areaReliability = ((meanArea - SIZE_SIGNAL_MIN_AREA) / areaDenom).coerceIn(0f, 1f)
        val aspectJitter = relativeAspectChange(prev, curr)
        val aspectReliability = (1f / (1f + aspectJitter / SIZE_SIGNAL_ASPECT_HALF)).coerceIn(0f, 1f)

        return logAreaDelta * areaReliability * aspectReliability * meanConfidence
    }

    private fun stabilizedHeightChange(
        prev: RectF,
        curr: RectF,
        prevConfidence: Float,
        currConfidence: Float
    ): Float {
        val prevHeight = heightOf(prev)
        val currHeight = heightOf(curr)
        val meanHeight = ((prevHeight + currHeight) * 0.5f).coerceAtMost(1f)
        if (meanHeight < HEIGHT_SIGNAL_MIN_HEIGHT) return 0f

        val meanConfidence = ((prevConfidence + currConfidence) * 0.5f).coerceIn(0f, 1f)
        if (meanConfidence < SIZE_SIGNAL_LOW_CONFIDENCE_SUPPRESS) return 0f

        val logHeightDelta = ln((currHeight / prevHeight).toDouble()).toFloat()
            .coerceIn(-SIZE_SIGNAL_LOG_CLAMP, SIZE_SIGNAL_LOG_CLAMP)

        val heightDenom = (HEIGHT_SIGNAL_FULL_HEIGHT - HEIGHT_SIGNAL_MIN_HEIGHT).coerceAtLeast(1e-4f)
        val heightReliability = ((meanHeight - HEIGHT_SIGNAL_MIN_HEIGHT) / heightDenom).coerceIn(0f, 1f)
        val aspectJitter = relativeAspectChange(prev, curr)
        val aspectReliability = (1f / (1f + aspectJitter / SIZE_SIGNAL_ASPECT_HALF)).coerceIn(0f, 1f)

        return logHeightDelta * heightReliability * aspectReliability * meanConfidence
    }

    private fun aspectRatio(r: RectF): Float {
        val w = (r.right - r.left).coerceAtLeast(1e-4f)
        val h = (r.bottom - r.top).coerceAtLeast(1e-4f)
        return w / h
    }

    private fun relativeAspectChange(prev: RectF, curr: RectF): Float {
        val pa = aspectRatio(prev)
        val ca = aspectRatio(curr)
        return abs(ca - pa) / maxOf(pa, 1e-4f)
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        return interArea / (areaOf(a) + areaOf(b) - interArea)
    }

    /**
     * Backward-compatible overload for callers that still pass frame
     * width/height in params 3 and 4.
     */
    fun analyzeMotion(
        frameDetections: List<List<ObjectDetector.Detection>>,
        frameTimestamps: List<Long>,
        frameWidth: Float,
        frameHeight: Float
    ): List<TrackedDetection> {
        if (frameWidth <= 0f || frameHeight <= 0f) {
            Log.w(TAG, "Ignoring non-positive frame dimensions in compatibility overload")
        }
        return analyzeMotion(
            frameDetections = frameDetections,
            frameTimestamps = frameTimestamps,
            externalCameraVelocity = null,
            externalCameraConfidence = 0f
        )
    }

    // ------------------------------------------------------------------
    // Distance-aware motion fusion.
    // ------------------------------------------------------------------

     /**
      * Fuse distance estimates into tracked detections, then optionally
      * refine [MotionState] and compute [ThreatLevel].
      *
      * Call this after [analyzeMotion] and distance estimation.
      *
      * @param detections Output of [analyzeMotion]
      * @param distances Distance results keyed by detection index
      * @return New list with [distanceFeet]/[threatLevel], and maybe refined motion state.
      */
    fun fuseDistance(
        detections: List<TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): List<TrackedDetection> {
        val fusionTimestampMs = System.currentTimeMillis()
        var withDistance = 0
        var withClosingRate = 0
        var withConfirmedRate = 0
        var ratePromoteApproach = 0
        var ratePromoteAway = 0
        var ratePendingConfirm = 0
        var closeRangePromotions = 0
        var farRangeDemotions = 0

        val fused = detections.mapIndexed { idx, det ->
            val distResult = distances[idx]
            val feet = distResult?.distanceFeet
            val distConfidence = distResult?.confidenceLevel
            val closingRateSample = if (feet != null) {
                estimateClosingRateSample(det.trackId, feet, fusionTimestampMs)
            } else {
                null
            }
            val closingRateFtPerSec = closingRateSample?.rateFtPerSec

            if (feet != null) withDistance++
            if (closingRateFtPerSec != null) withClosingRate++
            if ((closingRateSample?.approachStreak ?: 0) >= DISTANCE_RATE_CONFIRM_STREAK ||
                (closingRateSample?.awayStreak ?: 0) >= DISTANCE_RATE_CONFIRM_STREAK
            ) {
                withConfirmedRate++
            }

            // Distance-aware motion reclassification.
            val decision = if (feet != null) {
                refineMotionWithDistance(
                    state = det.motionState,
                    magnitude = det.motionMagnitude,
                    distanceFeet = feet,
                    closingRateSample = closingRateSample,
                    distanceConfidence = distConfidence
                )
            } else {
                DistanceRefineDecision(det.motionState, DistanceRefineReason.NONE)
            }

            when (decision.reason) {
                DistanceRefineReason.RATE_PROMOTE_APPROACH -> ratePromoteApproach++
                DistanceRefineReason.RATE_PROMOTE_AWAY -> ratePromoteAway++
                DistanceRefineReason.RATE_PENDING_CONFIRM -> ratePendingConfirm++
                DistanceRefineReason.CLOSE_RANGE_PROMOTION -> closeRangePromotions++
                DistanceRefineReason.FAR_RANGE_DEMOTION -> farRangeDemotions++
                DistanceRefineReason.NONE -> Unit
            }

            val threat = computeThreatLevel(decision.refinedState, feet)

            det.copy(
                motionState = decision.refinedState,
                distanceFeet = feet,
                threatLevel = threat
            )
        }

        Log.i(
            TAG,
            "DIST_FUSE detections=${detections.size} withDistance=$withDistance withClosingRate=$withClosingRate withConfirmedRate=$withConfirmedRate " +
                "ratePromoteApp=$ratePromoteApproach ratePromoteAway=$ratePromoteAway " +
                "ratePendingConfirm=$ratePendingConfirm " +
                "closePromotions=$closeRangePromotions farDemotions=$farRangeDemotions"
        )

        return fused
    }

    private enum class DistanceRefineReason {
        NONE,
        RATE_PROMOTE_APPROACH,
        RATE_PROMOTE_AWAY,
        RATE_PENDING_CONFIRM,
        CLOSE_RANGE_PROMOTION,
        FAR_RANGE_DEMOTION
    }

    private data class DistanceRefineDecision(
        val refinedState: MotionState,
        val reason: DistanceRefineReason
    )

    private data class ClosingRateSample(
        val rateFtPerSec: Float,
        val approachStreak: Int,
        val awayStreak: Int
    )

     /**
      * Refine motion state using absolute distance + distance rate.
      *
      * Positive [closingRateFtPerSec] means distance is shrinking (approaching).
      * Negative means distance is growing.
      *
      * Crossing states are preserved because distance-rate does not reliably
      * encode left/right direction.
      */
    private fun refineMotionWithDistance(
        state: MotionState,
        magnitude: Float,
        distanceFeet: Float,
        closingRateSample: ClosingRateSample?,
        distanceConfidence: String?
    ): DistanceRefineDecision {
        val nonLateral = state != MotionState.CROSSING_LEFT && state != MotionState.CROSSING_RIGHT
        val canUseRate = closingRateSample != null && distanceConfidence != "low"

        if (nonLateral && canUseRate) {
            val sample = closingRateSample ?: return DistanceRefineDecision(state, DistanceRefineReason.NONE)
            val closingRate = sample.rateFtPerSec
            val approachRateThreshold = when {
                distanceFeet < CLOSE_RANGE_FT -> DISTANCE_RATE_APPROACH_NEAR_FTPS
                distanceFeet <= MEDIUM_RANGE_FT -> DISTANCE_RATE_APPROACH_MID_FTPS
                else -> DISTANCE_RATE_APPROACH_FAR_FTPS
            }
            val awayRateThreshold = when {
                distanceFeet < CLOSE_RANGE_FT -> DISTANCE_RATE_AWAY_NEAR_FTPS
                distanceFeet <= MEDIUM_RANGE_FT -> DISTANCE_RATE_AWAY_MID_FTPS
                else -> DISTANCE_RATE_AWAY_FAR_FTPS
            }

            if (closingRate >= approachRateThreshold && state != MotionState.APPROACHING) {
                if (sample.approachStreak < DISTANCE_RATE_CONFIRM_STREAK) {
                    Log.d(
                        TAG,
                        "Distance-rate pending APPROACHING at ${distanceFeet}ft " +
                            "rate=${closingRate}ft/s streak=${sample.approachStreak}/$DISTANCE_RATE_CONFIRM_STREAK"
                    )
                    return DistanceRefineDecision(
                        refinedState = state,
                        reason = DistanceRefineReason.RATE_PENDING_CONFIRM
                    )
                }
                Log.d(
                    TAG,
                    "Distance-rate promotion: $state→APPROACHING at ${distanceFeet}ft " +
                        "rate=${closingRate}ft/s conf=${distanceConfidence ?: "?"}"
                )
                return DistanceRefineDecision(
                    refinedState = MotionState.APPROACHING,
                    reason = DistanceRefineReason.RATE_PROMOTE_APPROACH
                )
            }

            if (closingRate <= -awayRateThreshold && state != MotionState.MOVING_AWAY) {
                if (sample.awayStreak < DISTANCE_RATE_CONFIRM_STREAK) {
                    Log.d(
                        TAG,
                        "Distance-rate pending MOVING_AWAY at ${distanceFeet}ft " +
                            "rate=${closingRate}ft/s streak=${sample.awayStreak}/$DISTANCE_RATE_CONFIRM_STREAK"
                    )
                    return DistanceRefineDecision(
                        refinedState = state,
                        reason = DistanceRefineReason.RATE_PENDING_CONFIRM
                    )
                }
                Log.d(
                    TAG,
                    "Distance-rate promotion: $state→MOVING_AWAY at ${distanceFeet}ft " +
                        "rate=${closingRate}ft/s conf=${distanceConfidence ?: "?"}"
                )
                return DistanceRefineDecision(
                    refinedState = MotionState.MOVING_AWAY,
                    reason = DistanceRefineReason.RATE_PROMOTE_AWAY
                )
            }
        }

        return when {
            // Close range: only promote STATIONARY to APPROACHING with evidence.
            // UNKNOWN stays UNKNOWN so uncertainty is not force-promoted.
            distanceFeet < CLOSE_RANGE_FT &&
            state == MotionState.STATIONARY &&
            magnitude > VELOCITY_THRESHOLD * CLOSE_RANGE_VEL_SCALE * REFERENCE_DT_S -> {
                Log.d(TAG, "Close-range promotion: ${state}→APPROACHING at ${distanceFeet}ft")
                DistanceRefineDecision(
                    refinedState = MotionState.APPROACHING,
                    reason = DistanceRefineReason.CLOSE_RANGE_PROMOTION
                )
            }

            // Far range: demote weak APPROACHING to STATIONARY to cut false alarms.
            distanceFeet > MEDIUM_RANGE_FT &&
            state == MotionState.APPROACHING &&
            magnitude < VELOCITY_THRESHOLD * FAR_RANGE_VEL_SCALE * REFERENCE_DT_S -> {
                Log.d(TAG, "Far-range demotion: APPROACHING→STATIONARY at ${distanceFeet}ft")
                DistanceRefineDecision(
                    refinedState = MotionState.STATIONARY,
                    reason = DistanceRefineReason.FAR_RANGE_DEMOTION
                )
            }

            else -> DistanceRefineDecision(state, DistanceRefineReason.NONE)
        }
    }

    private fun estimateClosingRateSample(
        trackId: Int,
        currentDistanceFeet: Float,
        nowMs: Long
    ): ClosingRateSample? {
        if (trackId < 0) return null

        val entry = distanceTrendByPersistent.getOrPut(trackId) {
            DistanceTrendEntry(currentDistanceFeet, nowMs)
        }

        val previousDistance = entry.distanceFeet
        val previousTimestamp = entry.timestampMs
        val dtMs = (nowMs - previousTimestamp).coerceAtLeast(0L)

        entry.distanceFeet = currentDistanceFeet
        entry.timestampMs = nowMs

        if (dtMs == 0L || dtMs > DISTANCE_RATE_STALE_MS) {
            entry.hasClosingRateEma = false
            entry.approachStreak = 0
            entry.awayStreak = 0
            return null
        }

        val dtSec = dtMs / 1000f
        if (dtSec < DISTANCE_RATE_MIN_DT_S) return null

        val deltaFeet = currentDistanceFeet - previousDistance
        val sanitizedDelta = if (abs(deltaFeet) < DISTANCE_RATE_MIN_DELTA_FT) 0f else deltaFeet
        val rawClosingRate = (-sanitizedDelta / dtSec)
            .coerceIn(-DISTANCE_RATE_MAX_ABS_FTPS, DISTANCE_RATE_MAX_ABS_FTPS)

        val emaClosingRate = if (entry.hasClosingRateEma) {
            DISTANCE_RATE_EMA_ALPHA * rawClosingRate +
                (1f - DISTANCE_RATE_EMA_ALPHA) * entry.closingRateEmaFtPerSec
        } else {
            rawClosingRate
        }
        entry.closingRateEmaFtPerSec = emaClosingRate
        entry.hasClosingRateEma = true

        if (emaClosingRate >= DISTANCE_RATE_SIGN_STREAK_MIN_FTPS) {
            entry.approachStreak++
            entry.awayStreak = 0
        } else if (emaClosingRate <= -DISTANCE_RATE_SIGN_STREAK_MIN_FTPS) {
            entry.awayStreak++
            entry.approachStreak = 0
        } else {
            entry.approachStreak = 0
            entry.awayStreak = 0
        }

        return ClosingRateSample(
            rateFtPerSec = emaClosingRate,
            approachStreak = entry.approachStreak,
            awayStreak = entry.awayStreak
        )
    }

    /**
     * Compute [ThreatLevel] from (possibly refined) motion state + distance.
     */
    private fun computeThreatLevel(
        state: MotionState,
        distanceFeet: Float?
    ): ThreatLevel {
        if (distanceFeet == null) {
            // No distance data, so use motion-only fallback.
            return when (state) {
                MotionState.APPROACHING    -> ThreatLevel.HIGH
                MotionState.CROSSING_LEFT,
                MotionState.CROSSING_RIGHT -> ThreatLevel.MODERATE
                MotionState.MOVING_AWAY    -> ThreatLevel.LOW
                MotionState.STATIONARY     -> ThreatLevel.LOW
                MotionState.UNKNOWN        -> ThreatLevel.UNKNOWN
            }
        }

        val isClose  = distanceFeet < CLOSE_RANGE_FT
        val isMedium = distanceFeet in CLOSE_RANGE_FT..MEDIUM_RANGE_FT

        return when (state) {
            MotionState.APPROACHING -> when {
                isClose  -> ThreatLevel.CRITICAL   // approaching and very near
                isMedium -> ThreatLevel.HIGH       // approaching at mid-range
                else     -> ThreatLevel.MODERATE   // approaching but farther away
            }
            MotionState.CROSSING_LEFT,
            MotionState.CROSSING_RIGHT -> when {
                isClose  -> ThreatLevel.CRITICAL   // crossing path and very near
                isMedium -> ThreatLevel.HIGH
                else     -> ThreatLevel.MODERATE
            }
            MotionState.MOVING_AWAY -> when {
                isClose  -> ThreatLevel.MODERATE   // retreating but still close
                else     -> ThreatLevel.LOW
            }
            MotionState.STATIONARY -> when {
                isClose  -> ThreatLevel.MODERATE   // stationary but still close
                else     -> ThreatLevel.LOW
            }
            MotionState.UNKNOWN -> when {
                isClose  -> ThreatLevel.MODERATE
                else     -> ThreatLevel.UNKNOWN
            }
        }
    }

    // Convenience helper: convert raw Detection to TrackedDetection.
    private fun ObjectDetector.Detection.toTracked(
        state: MotionState,
        magnitude: Float
    ) = TrackedDetection(label, confidence, boundingBox, classIndex, state, magnitude, trackId = -1)
}

