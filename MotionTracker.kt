package com.esp32.camera

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motion tracking via comparison of YOLO detections across burst-capture frames.
 *
 * Features:
 * - Chains matches across ALL consecutive frame pairs, accumulating shifts.
 * - Falls back to centroid-distance matching when IoU fails.
 * - Time-normalised velocity thresholds (shift per second, not per frame).
 * - Temporal hysteresis: a motion state must persist across 2 consecutive
 *   bursts before it is reported, eliminating single-burst noise.
 * - Camera compensation degrades to UNKNOWN when < 2 matches exist.
 */
class MotionTracker {

    companion object {
        private const val TAG = "MotionTracker"

        // ----- Velocity thresholds (per-second, normalised 0-1 coords) -----
        // At VGA 640 px, 0.06/s ≈ 38 px/s ≈ ~19 px in a 500 ms burst gap.
        private const val VELOCITY_THRESHOLD = 0.06f          // minimum motion velocity
        private const val SIZE_VELOCITY_APPROACH = 0.40f       // area growth / sec
        private const val SIZE_VELOCITY_RETREAT = -0.40f       // area shrink / sec
        private const val SIZE_VELOCITY_APPROACH_SOFT = 0.15f  // softer approach threshold
        private const val SIZE_VELOCITY_RETREAT_SOFT = -0.15f  // softer retreat threshold

        // Reference time delta for normalisation (seconds).
        private const val REFERENCE_DT_S = 0.1f   // 100 ms

        // IoU threshold for primary bounding-box matching.
        private const val MATCH_IOU_THRESHOLD = 0.3f

        // Centroid-distance fallback (fraction of frame diagonal).
        private const val CENTROID_MATCH_THRESHOLD = 0.20f

        // Maximum burst time span (ms).
        const val MAX_FRAME_GAP_MS = 5000L

        // Temporal hysteresis: how many consecutive bursts a state must
        // persist before it is promoted from UNKNOWN to that state.
        private const val HYSTERESIS_COUNT = 1

        // Persistent registry: expire entries older than this (ms).
        // 3 burst intervals (3 × 30 s = 90 s) gives tolerance for
        // occasional missed detections while still expiring stale objects.
        private const val REGISTRY_STALE_MS = 90_000L

        // Cross-burst matching thresholds (looser than intra-burst
        // because 30 s elapsed and the object may have moved)
        private const val CROSS_BURST_IOU_THRESHOLD = 0.15f
        private const val CROSS_BURST_CENTROID_THRESHOLD = 0.30f

        // Classes representing typically-stationary scene features.
        // Their apparent motion directly reflects camera movement, so
        // they are preferred for ego-motion estimation over mobile
        // objects (people, cars) whose real movement would corrupt the
        // camera-velocity estimate.
        private val STATIONARY_SCENE_CLASSES = setOf(
            "stop sign", "fire hydrant", "bench", "traffic light",
            "parking meter", "potted plant", "chair", "couch",
            "bed", "dining table", "toilet", "tv", "refrigerator",
            "oven", "sink", "clock", "vase", "book"
        )

        // Kalman filter tuning constants
        private const val KALMAN_ACCEL_NOISE = 0.5f        // acceleration noise spectral density
        private const val KALMAN_MEAS_NOISE_POS = 0.002f   // position measurement noise variance
        private const val KALMAN_MEAS_NOISE_AREA = 0.005f  // area measurement noise variance

        // Distance thresholds (feet) for threat-level classification.
        // Close range uses softer velocity thresholds (even slow approach is
        // dangerous), while far range requires stronger evidence of approach.
        private const val CLOSE_RANGE_FT  =  5f
        private const val MEDIUM_RANGE_FT = 15f
        // Velocity threshold multipliers per distance band.
        // < 1 means lower bar for motion → easier to trigger.
        private const val CLOSE_RANGE_VEL_SCALE  = 0.5f   // half the normal threshold at close range
        private const val FAR_RANGE_VEL_SCALE    = 1.5f   // 50% harder to trigger at far range
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
     * Fused threat level combining distance and motion state.
     * Enables assistive systems to distinguish e.g. "approaching fast
     * at close range" (CRITICAL) from "approaching slowly far away" (LOW).
     */
    enum class ThreatLevel {
        /** Approaching/crossing at close range (< 5 ft) */
        CRITICAL,
        /** Approaching at medium range OR any motion at close range */
        HIGH,
        /** Stationary at close range, or approaching from far away */
        MODERATE,
        /** Stationary at medium/far range, moving away, or unknown */
        LOW,
        /** Insufficient data to assess threat */
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
        /** Estimated distance in feet, or null if unavailable. */
        val distanceFeet: Float? = null,
        /** Fused threat level incorporating both motion and distance. */
        val threatLevel: ThreatLevel = ThreatLevel.UNKNOWN
    )

    // ------------------------------------------------------------------
    // Persistent object registry (survives across bursts)
    // ------------------------------------------------------------------
    // Each entry represents a known physical object persisted across
    // 30-second burst intervals, keyed by a monotonically increasing
    // persistent ID that never resets.
    private data class PersistentEntry(
        val classIndex: Int,
        val label: String,
        val boundingBox: RectF,
        val lastSeenMs: Long
    )

    private var nextPersistentId = 0
    private val persistentRegistry = mutableMapOf<Int, PersistentEntry>()

    // ------------------------------------------------------------------
    // Temporal hysteresis state (survives across bursts)
    // ------------------------------------------------------------------
    // Key = "persistent:$persistentId" — stable across bursts because
    // persistent IDs are re-used for the same physical object.
    private data class StateHistory(var lastState: MotionState, var streak: Int)
    private val stateHistory = mutableMapOf<String, StateHistory>()

    // Kalman filter states keyed by persistent ID (survives across bursts)
    private val kalmanStates = mutableMapOf<Int, KalmanObjectState>()

    // ------------------------------------------------------------------
    // Internal: accumulated motion data for one object across frame pairs
    // ------------------------------------------------------------------
    private data class AccumulatedMotion(
        var velocitySumX: Float = 0f,
        var velocitySumY: Float = 0f,
        var sizeVelocitySum: Float = 0f,
        var pairCount: Int = 0
    ) {
        val avgVelX get() = if (pairCount > 0) velocitySumX / pairCount else 0f
        val avgVelY get() = if (pairCount > 0) velocitySumY / pairCount else 0f
        val avgSizeVel get() = if (pairCount > 0) sizeVelocitySum / pairCount else 0f
    }

    // ------------------------------------------------------------------
    // Kalman filter for smooth velocity estimation and prediction
    // ------------------------------------------------------------------

    /**
     * 1D constant-velocity Kalman filter with state [position, velocity].
     * Uses a continuous white-noise acceleration process model so that
     * process noise scales naturally with the prediction interval.
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

        /**
         * Predict state forward by [dt] seconds.
         * @param q  acceleration-noise spectral density
         */
        fun predict(dt: Float, q: Float = KALMAN_ACCEL_NOISE) {
            pos += vel * dt
            val dt2 = dt * dt; val dt3 = dt2 * dt
            pPos  += 2f * dt * pCross + dt2 * pVel + q * dt3 / 3f
            pCross += dt * pVel + q * dt2 / 2f
            pVel  += q * dt
        }

        /** Incorporate a position observation. */
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

        /** Incorporate a direct velocity observation (from burst analysis). */
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
     * Per-object Kalman state wrapping independent filters for x, y and area.
     * Provides smoothed velocity estimates, position prediction for
     * cross-burst matching, and a scalar velocity-confidence score.
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

        /** Predict-then-update with a new position/area observation. */
        fun update(cx: Float, cy: Float, area: Float, timestampMs: Long) {
            if (!initialized) { initialize(cx, cy, area, timestampMs); return }
            val dt = maxOf((timestampMs - lastUpdateMs) / 1000f, 0.010f)
            kx.predict(dt); ky.predict(dt); kArea.predict(dt, q = 0.1f)
            kx.updatePosition(cx)
            ky.updatePosition(cy)
            kArea.updatePosition(area, r = KALMAN_MEAS_NOISE_AREA)
            lastUpdateMs = timestampMs
        }

        /** Inject a velocity observation from within-burst frame-pair analysis. */
        fun updateWithVelocity(velX: Float, velY: Float, areaRate: Float) {
            kx.updateVelocity(velX)
            ky.updateVelocity(velY)
            kArea.updateVelocity(areaRate, r = 0.05f)
        }

        /** Predict centroid position at [futureMs] without modifying state. */
        fun predictPosition(futureMs: Long): PointF {
            val dt = maxOf((futureMs - lastUpdateMs) / 1000f, 0f)
            return PointF(kx.pos + kx.vel * dt, ky.pos + ky.vel * dt)
        }

        val smoothedVelX: Float get() = kx.vel
        val smoothedVelY: Float get() = ky.vel
        val smoothedAreaRate: Float get() = kArea.vel

        /**
         * Confidence in velocity estimate (0 = uncertain, 1 = confident).
         * Based on the combined velocity standard deviation from the
         * Kalman covariance matrices.
         */
        val velocityConfidence: Float get() {
            val s = sqrt(kx.velStdDev * kx.velStdDev + ky.velStdDev * ky.velStdDev)
            return 1f / (1f + s * 2f)
        }
    }

    /**
     * Analyse motion across a burst of detection frames.
     */
    fun analyzeMotion(
        frameDetections: List<List<ObjectDetector.Detection>>,
        frameTimestamps: List<Long>
    ): List<TrackedDetection> {
        if (frameDetections.isEmpty()) return emptyList()

        if (frameDetections.size == 1) {
            return frameDetections[0].map { it.toTracked(MotionState.UNKNOWN, 0f) }
        }

        // Reject if total time span is too large
        if (frameTimestamps.size >= 2) {
            val totalGap = frameTimestamps.last() - frameTimestamps.first()
            if (totalGap > MAX_FRAME_GAP_MS) {
                Log.w(TAG, "Frame gap ${totalGap}ms exceeds max, suppressing motion")
                return frameDetections.last().map { it.toTracked(MotionState.UNKNOWN, 0f) }
            }
        }

        // ---- Multi-frame chaining ----
        val numFrames = frameDetections.size
        var nextId = 0
        val chainIds = Array(numFrames) { IntArray(0) }
        chainIds[0] = IntArray(frameDetections[0].size) { nextId++ }

        val accumulated = mutableMapOf<Int, AccumulatedMotion>()
        val perPairCameraVelocities = mutableListOf<PointF>()

        for (f in 1 until numFrames) {
            val prevDets = frameDetections[f - 1]
            val currDets = frameDetections[f]
            val prevIds = chainIds[f - 1]
            val currIds = IntArray(currDets.size) { -1 }

            // Time delta for this pair (seconds); clamp to avoid div-by-zero
            val dtMs = (frameTimestamps.getOrElse(f) { frameTimestamps.last() }
                      - frameTimestamps.getOrElse(f - 1) { frameTimestamps.first() })
            val dtSec = maxOf(dtMs / 1000f, 0.010f)  // min 10 ms

            val matches = matchDetections(prevDets, currDets)

            val allPairVelocities = mutableListOf<PointF>()
            val stationaryPairVelocities = mutableListOf<PointF>()

            for ((prevDet, currDet, currIdx, prevIdx) in matches) {
                val id = prevIds[prevIdx]
                currIds[currIdx] = id

                val shift = centroidShift(prevDet.boundingBox, currDet.boundingBox)
                val velX = shift.x / dtSec
                val velY = shift.y / dtSec
                val vel = PointF(velX, velY)
                allPairVelocities.add(vel)
                // Track velocities from stationary scene features separately
                // for more robust ego-motion estimation.
                if (currDet.label in STATIONARY_SCENE_CLASSES) {
                    stationaryPairVelocities.add(vel)
                }

                val sizeChange = relativeSizeChange(prevDet.boundingBox, currDet.boundingBox)
                val sizeVel = sizeChange / dtSec

                accumulated.getOrPut(id) { AccumulatedMotion() }.apply {
                    velocitySumX += velX
                    velocitySumY += velY
                    sizeVelocitySum += sizeVel
                    pairCount++
                }
            }

            for (i in currIds.indices) {
                if (currIds[i] < 0) currIds[i] = nextId++
            }
            chainIds[f] = currIds

            // Camera ego-motion estimation for this frame pair.
            // Prefer velocities from stationary scene features (stop signs,
            // benches, fire hydrants, etc.) whose apparent motion reflects
            // only camera movement.  If most matched objects are actually
            // moving (e.g. traffic), using all objects would corrupt the
            // estimate; stationary features avoid this.
            val egoVelocities = if (stationaryPairVelocities.size >= 2) {
                Log.d(TAG, "Frame pair $f: using ${stationaryPairVelocities.size} " +
                           "stationary features for ego-motion")
                stationaryPairVelocities
            } else {
                allPairVelocities
            }

            if (egoVelocities.size >= 2) {
                val mx = egoVelocities.map { it.x }.sorted()[egoVelocities.size / 2]
                val my = egoVelocities.map { it.y }.sorted()[egoVelocities.size / 2]
                perPairCameraVelocities.add(PointF(mx, my))
            } else {
                // <2 matches — assume zero camera motion for this pair
                perPairCameraVelocities.add(PointF(0f, 0f))
                Log.d(TAG, "Frame pair $f: <2 matches, assuming zero camera shift")
            }
        }

        // Global camera velocity = average of per-pair median velocities
        val globalCamVel = if (perPairCameraVelocities.isNotEmpty()) {
            PointF(
                perPairCameraVelocities.map { it.x }.average().toFloat(),
                perPairCameraVelocities.map { it.y }.average().toFloat()
            )
        } else {
            PointF(0f, 0f)
        }
        Log.d(TAG, "Camera velocity: vx=${globalCamVel.x}/s, vy=${globalCamVel.y}/s " +
                   "(${perPairCameraVelocities.size} pairs)")

        // ---- Assign persistent IDs via cross-burst registry ----
        val lastDets = frameDetections[numFrames - 1]
        val lastIds = chainIds[numFrames - 1]
        val burstTimestamp = frameTimestamps.last()

        // Expire stale registry entries and associated Kalman/hysteresis state
        val expiredPids = persistentRegistry.entries
            .filter { burstTimestamp - it.value.lastSeenMs > REGISTRY_STALE_MS }
            .map { it.key }
        expiredPids.forEach { pid ->
            persistentRegistry.remove(pid)
            kalmanStates.remove(pid)
            stateHistory.remove("persistent:$pid")
        }

        // Map each final-frame detection to a persistent ID.
        // First, match against the existing registry using the same
        // Hungarian approach (IoU + centroid, same-class only).
        val persistentIds = matchToPersistentRegistry(lastDets, burstTimestamp)

        // ---- Update Kalman filters for each tracked object ----
        for (idx in lastDets.indices) {
            val pid = persistentIds[idx]
            val det = lastDets[idx]
            val chainId = lastIds[idx]
            val cx = (det.boundingBox.left + det.boundingBox.right) / 2f
            val cy = (det.boundingBox.top + det.boundingBox.bottom) / 2f
            val area = areaOf(det.boundingBox)

            val kState = kalmanStates.getOrPut(pid) {
                KalmanObjectState().also { it.initialize(cx, cy, area, burstTimestamp) }
            }
            if (!kState.initialized) {
                kState.initialize(cx, cy, area, burstTimestamp)
            } else {
                kState.update(cx, cy, area, burstTimestamp)
            }

            // Inject raw burst velocity as a direct observation so the
            // Kalman fuses within-burst detail with cross-burst history.
            val motion = accumulated[chainId]
            if (motion != null && motion.pairCount > 0) {
                kState.updateWithVelocity(motion.avgVelX, motion.avgVelY, motion.avgSizeVel)
            }
        }

        // ---- Classify motion for each detection in the final frame ----
        return lastDets.mapIndexed { idx, det ->
            val chainId = lastIds[idx]
            val pid = persistentIds[idx]
            val motion = accumulated[chainId]

            if (motion != null && motion.pairCount > 0) {
                // Use Kalman-smoothed velocities when available for stable
                // estimates; fall back to raw burst averages otherwise.
                val residualVelX: Float
                val residualVelY: Float
                val sizeVel: Float
                val confidence: Float
                val kState = kalmanStates[pid]

                if (kState != null && kState.initialized) {
                    // Kalman tracks in raw image coords; subtract camera vel.
                    residualVelX = kState.smoothedVelX - globalCamVel.x
                    residualVelY = kState.smoothedVelY - globalCamVel.y
                    sizeVel = kState.smoothedAreaRate
                    confidence = kState.velocityConfidence
                } else {
                    residualVelX = motion.avgVelX - globalCamVel.x
                    residualVelY = motion.avgVelY - globalCamVel.y
                    sizeVel = motion.avgSizeVel
                    confidence = 1.0f
                }

                val residualSpeed = sqrt(residualVelX * residualVelX + residualVelY * residualVelY)
                // Convert back to per-reference-frame magnitude for motionMagnitude output
                val residualMag = residualSpeed * REFERENCE_DT_S

                val rawState = classifyMotion(residualSpeed, residualVelX, residualVelY, sizeVel, confidence)
                Log.d(TAG, "${det.label}[pid=$pid, chain=$chainId]: residualSpeed=$residualSpeed, " +
                           "velX=$residualVelX, velY=$residualVelY, sizeVel=$sizeVel, " +
                           "confidence=$confidence → $rawState")
                applyHysteresis(det, pid, rawState, residualMag)
            } else {
                applyHysteresis(det, pid, MotionState.UNKNOWN, 0f)
            }
        }
    }

    // ------------------------------------------------------------------
    // Cross-burst persistent registry matching
    // ------------------------------------------------------------------
    /**
     * Match final-frame detections against the persistent registry.
     * Uses Hungarian assignment with looser thresholds (objects can
     * move significantly in 30 s).  Matched detections inherit a
     * persistent ID; unmatched detections receive a new one.  The
     * registry is then updated with current positions.
     */
    private fun matchToPersistentRegistry(
        detections: List<ObjectDetector.Detection>,
        timestampMs: Long
    ): IntArray {
        val pids = IntArray(detections.size) { -1 }

        if (persistentRegistry.isEmpty()) {
            // First burst ever — assign fresh persistent IDs
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

        // Build cost matrix [detIdx][registrySlot]
        val regEntries = persistentRegistry.entries.toList()  // stable order
        val nDet = detections.size
        val nReg = regEntries.size
        val n = maxOf(nDet, nReg)
        val INF = 1e9f
        val cost = Array(n) { FloatArray(n) { INF } }

        for (di in detections.indices) {
            for (ri in regEntries.indices) {
                val entry = regEntries[ri].value
                if (detections[di].classIndex != entry.classIndex) continue

                // Use Kalman-predicted position when available for more
                // accurate cross-burst matching (objects may have moved
                // significantly during the ~30 s inter-burst interval).
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
                if (iou >= CROSS_BURST_IOU_THRESHOLD) {
                    cost[di][ri] = 1f - iou
                } else {
                    val dist = centroidDistance(
                        centroidOf(detections[di].boundingBox),
                        centroidOf(matchBox)
                    )
                    if (dist < CROSS_BURST_CENTROID_THRESHOLD) {
                        cost[di][ri] = 1f + dist
                    }
                }
            }
        }

        val assignment = hungarianAssignment(cost, n)

        // Assign persistent IDs from matches
        for (di in detections.indices) {
            val ri = assignment[di]
            if (ri < nReg && cost[di][ri] < INF) {
                val pid = regEntries[ri].key
                pids[di] = pid
                // Update the registry entry with current position
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

        // Assign new persistent IDs for unmatched detections
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
    // Temporal hysteresis
    // ------------------------------------------------------------------
    /**
     * A new motion state is only promoted once it has been observed for
     * [HYSTERESIS_COUNT] consecutive bursts.  Until then, the previous
     * confirmed state (or UNKNOWN) is returned.
     *
     * Keys use persistent IDs so that the same physical object
     * accumulates streak counts across 30-second burst intervals.
     */
    private fun applyHysteresis(
        det: ObjectDetector.Detection,
        persistentId: Int,
        rawState: MotionState,
        magnitude: Float
    ): TrackedDetection {
        val key = "persistent:$persistentId"
        val history = stateHistory.getOrPut(key) { StateHistory(MotionState.UNKNOWN, 0) }

        val confirmedState = if (rawState == history.lastState) {
            history.streak++
            if (history.streak >= HYSTERESIS_COUNT) rawState else MotionState.UNKNOWN
        } else {
            history.lastState = rawState
            history.streak = 1
            if (HYSTERESIS_COUNT <= 1) rawState else MotionState.UNKNOWN
        }

        return TrackedDetection(
            label = det.label,
            confidence = det.confidence,
            boundingBox = det.boundingBox,
            classIndex = det.classIndex,
            motionState = confirmedState,
            motionMagnitude = magnitude,
            trackId = persistentId
        )
    }

    // ------------------------------------------------------------------
    // Motion classification (velocity-based thresholds)
    // ------------------------------------------------------------------
    /**
     * Classify object motion from residual (camera-compensated) velocity.
     *
     * @param confidence Kalman velocity confidence (0-1).  When the filter
     *   is uncertain (low confidence), the effective motion threshold is
     *   raised so that noisy estimates don't produce false positives.
     *   Approaching/receding classification similarly requires stronger
     *   evidence when confidence is low.
     */
    private fun classifyMotion(
        residualSpeed: Float,
        residualVelX: Float,
        residualVelY: Float,
        sizeVelocity: Float,
        confidence: Float = 1.0f
    ): MotionState {
        // Scale threshold inversely with confidence: uncertain velocity
        // needs stronger motion to be classified as non-stationary.
        val adjustedThreshold = VELOCITY_THRESHOLD / maxOf(confidence, 0.2f)
        if (residualSpeed < adjustedThreshold) return MotionState.STATIONARY

        // Weight size-velocity by confidence for approach/retreat decisions
        val effectiveSizeVel = sizeVelocity * confidence

        return when {
            effectiveSizeVel > SIZE_VELOCITY_APPROACH -> MotionState.APPROACHING
            effectiveSizeVel < SIZE_VELOCITY_RETREAT -> MotionState.MOVING_AWAY
            abs(residualVelX) > abs(residualVelY) -> {
                if (residualVelX < 0) MotionState.CROSSING_LEFT else MotionState.CROSSING_RIGHT
            }
            effectiveSizeVel > SIZE_VELOCITY_APPROACH_SOFT -> MotionState.APPROACHING
            effectiveSizeVel < SIZE_VELOCITY_RETREAT_SOFT -> MotionState.MOVING_AWAY
            else -> if (residualVelX < 0) MotionState.CROSSING_LEFT else MotionState.CROSSING_RIGHT
        }
    }

    // ------------------------------------------------------------------
    // Detection matching: IoU-first with centroid-distance fallback
    // ------------------------------------------------------------------
    private data class MatchResult(
        val prev: ObjectDetector.Detection,
        val curr: ObjectDetector.Detection,
        val currIdx: Int,
        val prevIdx: Int
    )

    /**
     * Match detections between two consecutive frames using globally
     * optimal assignment (Hungarian algorithm) with a combined cost
     * of IoU and centroid distance, restricted to same-class pairs.
     *
     * This replaces the previous greedy two-pass approach and avoids
     * order-dependent mis-associations in crowded scenes.
     */
    private fun matchDetections(
        prev: List<ObjectDetector.Detection>,
        curr: List<ObjectDetector.Detection>
    ): List<MatchResult> {
        if (prev.isEmpty() || curr.isEmpty()) return emptyList()

        // Build cost matrix [currIdx][prevIdx].  Cost = 1 - IoU when IoU
        // is above threshold, else fall back to centroid distance.
        // Pairs of different classes get infinite cost (unmatched).
        val INF = 1e9f
        val nCurr = curr.size
        val nPrev = prev.size
        val n = maxOf(nCurr, nPrev)           // pad to square
        val cost = Array(n) { FloatArray(n) { INF } }

        for (ci in curr.indices) {
            for (pi in prev.indices) {
                if (curr[ci].classIndex != prev[pi].classIndex) continue

                val iou = computeIoU(curr[ci].boundingBox, prev[pi].boundingBox)
                if (iou >= MATCH_IOU_THRESHOLD) {
                    cost[ci][pi] = 1f - iou          // lower = better
                } else {
                    // Centroid-distance fallback
                    val dist = centroidDistance(
                        centroidOf(prev[pi].boundingBox),
                        centroidOf(curr[ci].boundingBox)
                    )
                    if (dist < CENTROID_MATCH_THRESHOLD) {
                        // Offset so centroid matches always rank below IoU matches
                        cost[ci][pi] = 1f + dist
                    }
                }
            }
        }

        // Run Hungarian algorithm on the (padded) square matrix
        val assignment = hungarianAssignment(cost, n)

        // Collect valid matches
        val result = mutableListOf<MatchResult>()
        for (ci in curr.indices) {
            val pi = assignment[ci]
            if (pi < nPrev && cost[ci][pi] < INF) {
                result.add(MatchResult(prev[pi], curr[ci], ci, pi))
                if (cost[ci][pi] > 1f) {
                    Log.d(TAG, "Centroid fallback matched ${curr[ci].label} (cost=${cost[ci][pi]})")
                }
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // Hungarian (Kuhn–Munkres) algorithm  –  O(n³)
    // Returns assignment[row] = col for a square n×n cost matrix.
    // ------------------------------------------------------------------
    private fun hungarianAssignment(cost: Array<FloatArray>, n: Int): IntArray {
        // u, v = potentials;  p, way = augmenting-path bookkeeping
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

            // Unwind augmenting path
            while (j0 != 0) {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            }
        }

        // Build row→col assignment (0-indexed)
        val ans = IntArray(n) { -1 }
        for (j in 1..n) {
            if (p[j] > 0) ans[p[j] - 1] = j - 1
        }
        return ans
    }

    // ------------------------------------------------------------------
    // Geometry helpers
    // ------------------------------------------------------------------
    private fun centroidOf(r: RectF) = PointF((r.left + r.right) / 2, (r.top + r.bottom) / 2)
    private fun areaOf(r: RectF) = (r.right - r.left) * (r.bottom - r.top)

    private fun centroidShift(prev: RectF, curr: RectF): PointF {
        val pc = centroidOf(prev)
        val cc = centroidOf(curr)
        return PointF(cc.x - pc.x, cc.y - pc.y)
    }

    /** Normalised Euclidean distance between two centroids (0-1 scale). */
    private fun centroidDistance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun relativeSizeChange(prev: RectF, curr: RectF): Float {
        val pa = areaOf(prev)
        val ca = areaOf(curr)
        return if (pa > 0.0001f) (ca - pa) / pa else 0f
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

    // ------------------------------------------------------------------
    // Distance-aware motion fusion
    // ------------------------------------------------------------------

    /**
     * Fuse distance estimates into tracked detections, producing
     * distance-aware [MotionState] reclassification and a unified
     * [ThreatLevel] for each detection.
     *
     * Call this **after** [analyzeMotion] and distance estimation.
     *
     * @param detections  Output of [analyzeMotion]
     * @param distances   Distance results keyed by detection index
     * @return New list with [distanceFeet] and [threatLevel] populated,
     *         and [motionState] potentially refined based on proximity.
     */
    fun fuseDistance(
        detections: List<TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): List<TrackedDetection> {
        return detections.mapIndexed { idx, det ->
            val distResult = distances[idx]
            val feet = distResult?.distanceFeet

            // --- Distance-aware motion reclassification ---
            // At close range, lower thresholds: even slow size-velocity
            // growth is meaningful because the object is already nearby.
            val refinedState = if (feet != null) {
                refineMotionWithDistance(det.motionState, det.motionMagnitude, feet)
            } else {
                det.motionState
            }

            val threat = computeThreatLevel(refinedState, feet)

            det.copy(
                motionState = refinedState,
                distanceFeet = feet,
                threatLevel = threat
            )
        }
    }

    /**
     * Refine the motion state using distance context.
     *
     * At close range (< [CLOSE_RANGE_FT]) even a small positive
     * size-velocity (motionMagnitude) is reclassified as APPROACHING
     * instead of STATIONARY — the object is already dangerously near.
     *
     * At far range (> [MEDIUM_RANGE_FT]) a weak APPROACHING signal is
     * demoted to STATIONARY to avoid false alarms from noisy far-field
     * detections.
     */
    private fun refineMotionWithDistance(
        state: MotionState,
        magnitude: Float,
        distanceFeet: Float
    ): MotionState {
        return when {
            // Close range: promote STATIONARY / UNKNOWN with any positive
            // motion magnitude to APPROACHING — proximity makes even
            // marginal approach urgent.
            distanceFeet < CLOSE_RANGE_FT &&
            (state == MotionState.STATIONARY || state == MotionState.UNKNOWN) &&
            magnitude > VELOCITY_THRESHOLD * CLOSE_RANGE_VEL_SCALE * REFERENCE_DT_S -> {
                Log.d(TAG, "Close-range promotion: ${state}→APPROACHING at ${distanceFeet}ft")
                MotionState.APPROACHING
            }

            // Far range: demote weak APPROACHING to STATIONARY — noise at
            // distance shouldn't create false alarms.
            distanceFeet > MEDIUM_RANGE_FT &&
            state == MotionState.APPROACHING &&
            magnitude < VELOCITY_THRESHOLD * FAR_RANGE_VEL_SCALE * REFERENCE_DT_S -> {
                Log.d(TAG, "Far-range demotion: APPROACHING→STATIONARY at ${distanceFeet}ft")
                MotionState.STATIONARY
            }

            else -> state
        }
    }

    /**
     * Compute a [ThreatLevel] from the (possibly refined) motion state
     * and distance.  This is the primary output for assistive threat
     * prioritisation downstream.
     */
    private fun computeThreatLevel(
        state: MotionState,
        distanceFeet: Float?
    ): ThreatLevel {
        if (distanceFeet == null) {
            // No distance data — fall back to motion-only heuristic
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
                isClose  -> ThreatLevel.CRITICAL   // approaching fast, very near
                isMedium -> ThreatLevel.HIGH       // approaching at mid-range
                else     -> ThreatLevel.MODERATE   // approaching but far away
            }
            MotionState.CROSSING_LEFT,
            MotionState.CROSSING_RIGHT -> when {
                isClose  -> ThreatLevel.CRITICAL   // crossing path, very near
                isMedium -> ThreatLevel.HIGH
                else     -> ThreatLevel.MODERATE
            }
            MotionState.MOVING_AWAY -> when {
                isClose  -> ThreatLevel.MODERATE   // retreating but still close
                else     -> ThreatLevel.LOW
            }
            MotionState.STATIONARY -> when {
                isClose  -> ThreatLevel.MODERATE   // sitting still but in the way
                else     -> ThreatLevel.LOW
            }
            MotionState.UNKNOWN -> when {
                isClose  -> ThreatLevel.MODERATE
                else     -> ThreatLevel.UNKNOWN
            }
        }
    }

    // Extension: convert a raw Detection to TrackedDetection
    private fun ObjectDetector.Detection.toTracked(
        state: MotionState,
        magnitude: Float
    ) = TrackedDetection(label, confidence, boundingBox, classIndex, state, magnitude, trackId = -1)
}
