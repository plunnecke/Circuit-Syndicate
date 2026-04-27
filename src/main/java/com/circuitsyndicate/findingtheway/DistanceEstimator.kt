package com.circuitsyndicate.findingtheway

import android.graphics.RectF
import android.util.Log
import java.util.Locale

/**
 * Estimates approximate distance to detected objects using
 *
 * distance = (bounding_box_height_pixels * focal_length) / approx_real_height
 *
 */
class DistanceEstimator {

    companion object {
        private const val TAG = "DistanceEstimator"

        // Calibrated focal lengths at multiple distances for OV2640 at VGA (640×480).
        // Close-range bboxes experience more barrel distortion → lower effective focal length.
        //
        // Calibration points (person, 1.7 m real height):
        //   2 ft (0.61 m): bboxH ≈ 920 px, f = (920 * 0.61) / 1.7 ≈ 330
        //   3 ft (0.91 m): bboxH ≈ 710 px, f = (710 * 0.91) / 1.7 ≈ 380
        //   4 ft (1.22 m): bboxH ≈ 564 px, f = (564 * 1.22) / 1.7 ≈ 405
        //   6 ft (1.83 m): bboxH ≈ 403 px, f = (403 * 1.83) / 1.7 ≈ 434
        //   9 ft (2.74 m): bboxH ≈ 279 px, f = (279 * 2.74) / 1.7 ≈ 450
        //  15 ft (4.57 m): bboxH ≈ 175 px, f = (175 * 4.57) / 1.7 ≈ 470
        //  20 ft (6.10 m): bboxH ≈ 133 px, f = (133 * 6.10) / 1.7 ≈ 477
        //  25 ft (7.62 m): bboxH ≈ 108 px, f = (108 * 7.62) / 1.7 ≈ 484
        //
        // We linearly interpolate between these anchor points based on the
        // measured bboxH to get a range-adaptive focal length.
        private val FOCAL_ANCHORS = floatArrayOf(920f, 710f, 564f, 403f, 279f, 175f, 133f, 108f)  // bboxH (px)
        private val FOCAL_VALUES  = floatArrayOf(330f, 380f, 405f, 434f, 450f, 470f, 477f, 484f)  // effective f
        const val DEFAULT_FOCAL_LENGTH_PX = 434f   // mid-range fallback

        // Default frame dimensions (VGA).  Overridden at runtime when actual
        // decoded bitmap dimensions are available.
        const val DEFAULT_FRAME_WIDTH  = 640f
        const val DEFAULT_FRAME_HEIGHT = 480f

        // EMA smoothing factor for distance readings (0-1).
        // Lower = smoother / more lag.  0.35 gives ~3-reading settling.
        private const val DISTANCE_EMA_ALPHA = 0.35f

        // Maximum age (ms) before a smoothing entry is considered stale
        // and the EMA is reset.
        private const val SMOOTHING_STALE_MS = 10_000L
    }

    // Known approximate real-world heights in meters for Open Images classes.
    // null / absent = unknown/unreliable → area-based fallback.
    private enum class LabelMappingKind {
        DIRECT,
        ALIAS,
        INFERRED,
        UNKNOWN
    }

    private data class LabelMapping(
        val canonicalLabel: String,
        val knownHeightMeters: Float?,
        val kind: LabelMappingKind
    )

    private val openImagesLabelAliases: Map<String, String> = mapOf(
        "person" to "person",
        "man" to "person",
        "woman" to "person",
        "boy" to "person",
        "girl" to "person",
        "car" to "car",
        "taxi" to "car",
        "van" to "car",
        "sports car" to "car",
        "motorcycle" to "motorcycle",
        "bicycle" to "bicycle",
        "bus" to "bus",
        "truck" to "truck",
        "pickup truck" to "truck",
        "train" to "train",
        "boat" to "boat",
        "traffic light" to "traffic light",
        "fire hydrant" to "fire hydrant",
        "stop sign" to "stop sign",
        "bench" to "bench",
        "bird" to "bird",
        "cat" to "cat",
        "dog" to "dog",
        "horse" to "horse",
        "cow" to "cow",
        "elephant" to "elephant",
        "bear" to "bear",
        "zebra" to "zebra",
        "giraffe" to "giraffe",
        "chair" to "chair",
        "couch" to "couch",
        "sofa bed" to "couch",
        "bed" to "bed",
        "table" to "table",
        "dining table" to "table",
        "coffee table" to "table",
        "toilet" to "toilet",
        "television" to "tv",
        "tv" to "tv",
        "laptop" to "laptop",
        "computer monitor" to "tv",
        "refrigerator" to "refrigerator",
        "fridge" to "refrigerator"
    )

    private val openImagesInferenceRules: List<Pair<Regex, String>> = listOf(
        Regex("\\b(person|human|man|woman|boy|girl|child|adult|pedestrian)\\b") to "person",
        Regex("\\b(car|taxi|van|suv|automobile|sedan|coupe|hatchback|limousine|minivan)\\b") to "car",
        Regex("\\b(truck|lorry|pickup)\\b") to "truck",
        Regex("\\b(bus|coach|minibus)\\b") to "bus",
        Regex("\\b(train|locomotive|tram|subway)\\b") to "train",
        Regex("\\b(motorcycle|motorbike|scooter|moped)\\b") to "motorcycle",
        Regex("\\b(bicycle|bike)\\b") to "bicycle",
        Regex("\\b(boat|ship|vessel|canoe|kayak|raft)\\b") to "boat",
        Regex("\\b(bird|duck|goose|chicken|turkey|owl|eagle|parrot|crow|pigeon)\\b") to "bird",
        Regex("\\b(cat|kitten|feline)\\b") to "cat",
        Regex("\\b(dog|puppy|canine)\\b") to "dog",
        Regex("\\b(horse|pony)\\b") to "horse",
        Regex("\\b(cow|cattle|bull|calf)\\b") to "cow",
        Regex("\\b(elephant)\\b") to "elephant",
        Regex("\\b(bear)\\b") to "bear",
        Regex("\\b(zebra)\\b") to "zebra",
        Regex("\\b(giraffe)\\b") to "giraffe",
        Regex("\\b(chair|stool|armchair)\\b") to "chair",
        Regex("\\b(couch|sofa|loveseat|settee)\\b") to "couch",
        Regex("\\b(bed|mattress|bunk)\\b") to "bed",
        Regex("\\b(table|desk|counter|workbench)\\b") to "table",
        Regex("\\b(toilet|urinal)\\b") to "toilet",
        Regex("\\b(tv|television|monitor|display|screen)\\b") to "tv",
        Regex("\\b(laptop|notebook computer)\\b") to "laptop",
        Regex("\\b(refrigerator|fridge|freezer)\\b") to "refrigerator",
        Regex("\\b(stop sign)\\b") to "stop sign",
        Regex("\\b(fire hydrant)\\b") to "fire hydrant",
        Regex("\\b(bench)\\b") to "bench"
    )

    private val knownHeights: Map<String, Float> = mapOf(
        "person" to 1.7f,
        "bicycle" to 1.1f,
        "car" to 1.5f,
        "motorcycle" to 1.1f,
        "bus" to 3.0f,
        "train" to 3.5f,
        "truck" to 2.5f,
        "boat" to 2.0f,
        "fire hydrant" to 0.6f,
        "stop sign" to 0.75f,
        "bench" to 0.85f,
        "bird" to 0.25f,
        "cat" to 0.3f,
        "dog" to 0.5f,
        "horse" to 1.6f,
        "cow" to 1.4f,
        "elephant" to 3.0f,
        "bear" to 1.5f,
        "zebra" to 1.4f,
        "giraffe" to 5.0f,
        "chair" to 0.9f,
        "couch" to 0.85f,
        "bed" to 0.6f,
        "table" to 0.75f,
        "toilet" to 0.6f,
        "tv" to 0.5f,
        "laptop" to 0.25f,
        "refrigerator" to 1.7f
    )

    // Expected full-body aspect ratio (width / height) for classes where
    // partial visibility is common.  When the detected bbox is significantly
    // wider (higher aspect ratio) than expected, we assume only a portion of
    // the object is visible and scale the assumed height down accordingly.
    private val expectedAspectRatios: Map<String, Float> = mapOf(
        "person" to 0.42f,   // standing full-body at VGA
        "car" to 1.6f,
        "bus" to 1.8f,
        "truck" to 1.7f,
        "bicycle" to 1.1f,
        "motorcycle" to 1.2f,
        "horse" to 1.3f,
        "cow" to 1.5f
    )

    data class DistanceResult(
        val distanceFeet: Float,        // estimated distance in feet (always provided)
        val distanceCategory: String,   // Natural language category with numeric value
        val confidenceLevel: String     // "high", "medium", "low"
    )

    var focalLengthPx: Float = DEFAULT_FOCAL_LENGTH_PX
    var calibratedFocalLengthPx: Float? = null
    var useCalibratedIntrinsics: Boolean = false

    // Actual frame dimensions – set once from the decoded bitmap so the
    // estimator is not silently broken if the firmware resolution changes.
    var frameWidth:  Float = DEFAULT_FRAME_WIDTH
    var frameHeight: Float = DEFAULT_FRAME_HEIGHT

    // ---- EMA smoothing state keyed by trackId (or label fallback) ----
    private data class SmoothEntry(var emaFeet: Float, var lastUpdateMs: Long)
    private val smoothed = mutableMapOf<String, SmoothEntry>()

    /**
     * Estimate distance to a detected object.
     * The returned distance is EMA-smoothed across consecutive calls
     * for the same tracked instance, eliminating frame-to-frame jitter.
     *
     * @param label The object class label
     * @param boundingBox Normalized bounding box (0-1)
     * @param trackId Unique per-instance track ID from MotionTracker (-1 = untracked, falls back to label key)
     * @return DistanceResult with estimated distance and category
     */
    fun estimateDistance(label: String, boundingBox: RectF, trackId: Int = -1): DistanceResult {
        val bboxWidth  = boundingBox.right - boundingBox.left
        val bboxHeight = boundingBox.bottom - boundingBox.top
        val bboxHeightPx = bboxHeight * frameHeight
        val bboxArea = bboxWidth * bboxHeight
        val bboxAspect = if (bboxHeight > 0.001f) bboxWidth / bboxHeight else 0f
        // Smoothing key: use trackId for per-instance EMA; fall back to label when untracked.
        val mapping = resolveLabelMapping(label)
        val canonicalLabel = mapping.canonicalLabel
        val smoothKey = if (trackId >= 0) "track:$trackId" else "label:$canonicalLabel"
        Log.d(
            TAG,
            "CALIBRATE: label=$label canonical=$canonicalLabel kind=${mapping.kind} trackId=$trackId bboxH_px=$bboxHeightPx bboxArea=$bboxArea aspect=$bboxAspect"
        )

        val knownHeight = mapping.knownHeightMeters

        return if (knownHeight != null && bboxHeightPx > 5f) {
            // --- Partial-body correction ---
            // If the detection's aspect ratio is significantly wider than the
            // expected full-body ratio, the object is likely only partially
            // visible (e.g. seated / occluded).  Scale assumed height down so
            // the distance formula doesn't over-estimate.
            val effectiveHeight = adjustForPartialVisibility(canonicalLabel, knownHeight, bboxAspect)

            // Use calibrated intrinsics when available; otherwise keep
            // existing range-adaptive focal interpolation behavior.
            val effectiveFocal = if (useCalibratedIntrinsics && calibratedFocalLengthPx != null) {
                calibratedFocalLengthPx!!.coerceAtLeast(1f)
            } else {
                interpolateFocalLength(bboxHeightPx)
            }

            val distanceMeters = (effectiveHeight * effectiveFocal) / bboxHeightPx
            val distanceFeet = distanceMeters * 3.28084f

            val confidence = when {
                mapping.kind == LabelMappingKind.INFERRED -> if (bboxHeightPx > 30f) "medium" else "low"
                effectiveHeight < knownHeight * 0.95f -> "medium"  // partial-body → lower confidence
                bboxHeightPx > 30f -> "high"
                else -> "medium"
            }

            // Apply EMA smoothing per tracked instance
            val smoothedFeet = smoothDistance(smoothKey, distanceFeet)

            DistanceResult(
                distanceFeet = smoothedFeet,
                distanceCategory = categorizeDistance(smoothedFeet),
                confidenceLevel = confidence
            )
        } else {
            // Fall back to area-based estimation using inverse-square model.
            // bbox area ~= (realSize * f / d)^2 / (frameW * frameH)
            // We calibrate: area 0.15 ≈ 3 ft, area 0.01 ≈ 18 ft.
            // Model: d ≈ k / sqrt(area), k calibrated from anchor.
            val areaFeet = if (bboxArea > 0.0001f) {
                // k = 3 * sqrt(0.15) = 1.16  (calibrated from 3 ft at area 0.15)
                val k = 1.16f
                (k / kotlin.math.sqrt(bboxArea)).coerceIn(1f, 100f)
            } else {
                100f  // vanishingly small box → very far
            }

            val smoothedFeet = smoothDistance(smoothKey, areaFeet)

            DistanceResult(
                distanceFeet = smoothedFeet,
                distanceCategory = categorizeDistance(smoothedFeet),
                confidenceLevel = "low"
            )
        }
    }

    /**
     * If the bbox aspect ratio is significantly wider than the expected
     * full-body ratio for this class, scale the assumed real-world height
     * proportionally.  Clamped to [40%..100%] of the known height.
     */
    private fun adjustForPartialVisibility(
        canonicalLabel: String,
        fullHeight: Float,
        detectedAspect: Float
    ): Float {
        val expectedAR = expectedAspectRatios[canonicalLabel] ?: return fullHeight
        if (detectedAspect <= expectedAR * 1.3f) return fullHeight   // within 30% → full body

        // Ratio of expected / detected aspect gives visibility fraction.
        // e.g. expected 0.42, detected 0.84 → ~50% of height visible.
        val visibilityFraction = (expectedAR / detectedAspect).coerceIn(0.4f, 1.0f)
        val adjusted = fullHeight * visibilityFraction
        Log.d(TAG, "Partial-body correction for $canonicalLabel: aspect=$detectedAspect expected=$expectedAR → height ${fullHeight}→${adjusted}m")
        return adjusted
    }

    private fun normalizeLabel(rawLabel: String): String {
        val normalized = rawLabel
            .lowercase(Locale.US)
            .replace('_', ' ')
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized
    }

    private fun resolveLabelMapping(rawLabel: String): LabelMapping {
        val normalized = normalizeLabel(rawLabel)

        if (knownHeights.containsKey(normalized)) {
            return LabelMapping(
                canonicalLabel = normalized,
                knownHeightMeters = knownHeights[normalized],
                kind = LabelMappingKind.DIRECT
            )
        }

        val alias = openImagesLabelAliases[normalized]
        if (alias != null) {
            return LabelMapping(
                canonicalLabel = alias,
                knownHeightMeters = knownHeights[alias],
                kind = LabelMappingKind.ALIAS
            )
        }

        val inferred = openImagesInferenceRules.firstOrNull { (pattern, _) ->
            pattern.containsMatchIn(normalized)
        }?.second
        if (inferred != null) {
            return LabelMapping(
                canonicalLabel = inferred,
                knownHeightMeters = knownHeights[inferred],
                kind = LabelMappingKind.INFERRED
            )
        }

        return LabelMapping(
            canonicalLabel = normalized,
            knownHeightMeters = null,
            kind = LabelMappingKind.UNKNOWN
        )
    }

    /**
     * Piecewise-linear interpolation of focal length based on bbox height.
     * Uses the [FOCAL_ANCHORS] / [FOCAL_VALUES] calibration table.
     * Anchors are ordered from large bboxH (close) to small bboxH (far).
     */
    private fun interpolateFocalLength(bboxHeightPx: Float): Float {
        // Scale anchors to current frame height (anchors calibrated at 480).
        val scale = frameHeight / DEFAULT_FRAME_HEIGHT
        val anchors = FOCAL_ANCHORS.map { it * scale }

        // bboxH larger than closest anchor → clamp to closest focal
        if (bboxHeightPx >= anchors[0]) return FOCAL_VALUES[0]
        // bboxH smaller than farthest anchor → clamp to farthest focal
        if (bboxHeightPx <= anchors.last()) return FOCAL_VALUES.last()

        // Find segment and interpolate
        for (i in 0 until anchors.size - 1) {
            if (bboxHeightPx <= anchors[i] && bboxHeightPx >= anchors[i + 1]) {
                val t = (anchors[i] - bboxHeightPx) / (anchors[i] - anchors[i + 1])
                return FOCAL_VALUES[i] + t * (FOCAL_VALUES[i + 1] - FOCAL_VALUES[i])
            }
        }
        return focalLengthPx  // shouldn't reach here
    }

    /**
     * Exponential moving average for distance readings.
     * Keyed by [smoothKey] which encodes either a track ID or a label fallback.
     * Resets if the entry is stale (> [SMOOTHING_STALE_MS]).
     */
    private fun smoothDistance(smoothKey: String, rawFeet: Float): Float {
        val now = System.currentTimeMillis()
        val entry = smoothed[smoothKey]

        return if (entry != null && (now - entry.lastUpdateMs) < SMOOTHING_STALE_MS) {
            entry.emaFeet = DISTANCE_EMA_ALPHA * rawFeet + (1f - DISTANCE_EMA_ALPHA) * entry.emaFeet
            entry.lastUpdateMs = now
            entry.emaFeet
        } else {
            smoothed[smoothKey] = SmoothEntry(rawFeet, now)
            rawFeet
        }
    }

    private fun categorizeDistance(distanceFeet: Float): String {
        val ft = distanceFeet.toInt()
        return when {
            distanceFeet < 3f  -> "about $ft feet, right in front of you"
            distanceFeet < 10f -> "about $ft feet away"
            distanceFeet < 50f -> "about $ft feet away"
            else               -> "about $ft feet away, very far"
        }
    }
}

