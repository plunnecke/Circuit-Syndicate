package com.circuitsyndicate.findingtheway

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.max

/**
 * Sparse background tracker for rough camera ego-motion.
 *
 * Basic flow here:
 * 1) convert frames to gray,
 * 2) mask out detected objects,
 * 3) track background corners with LK optical flow,
 * 4) use medians so noisy tracks do not dominate.
 */
object SparseBackgroundTracker {

    // Tuning constants.
    private const val TAG = "SparseBgTracker"
    private const val MAX_CORNERS = 250
    private const val QUALITY_LEVEL = 0.01
    private const val MIN_DISTANCE = 7.0
    private const val MASK_MARGIN_PX = 8
    private const val MIN_VALID_TRACKS = 15
    private const val MAX_POINT_SHIFT_NORM = 0.35f

    private val WIN_SIZE = Size(21.0, 21.0)
    private val TERM_CRITERIA = TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 20, 0.03)
    private val nativeAvailabilityLock = Any()

    @Volatile
    private var nativeAvailabilityChecked = false

    @Volatile
    private var nativeAvailable = true

    @Volatile
    private var nativeDisableReason: String? = null

    data class Estimate(
        val velocity: PointF,
        val confidence: Float,
        val usedPairs: Int,
        val totalValidTracks: Int
    )

    fun isNativeAvailable(): Boolean = ensureNativeAvailability()

    private fun ensureNativeAvailability(): Boolean {
        if (nativeAvailabilityChecked) {
            return nativeAvailable
        }

        synchronized(nativeAvailabilityLock) {
            if (nativeAvailabilityChecked) {
                return nativeAvailable
            }

            nativeAvailable = runCatching {
                // Quick JNI check once; if this fails, keep sparse tracking off for this process.
                val probe = Mat()
                probe.create(1, 1, CvType.CV_8UC1)
                probe.release()
            }.map { true }.getOrElse { error ->
                nativeDisableReason = "init:${error.javaClass.simpleName}"
                Log.w(
                    TAG,
                    "Sparse ego-motion disabled for process (Option B): ${error.message}"
                )
                false
            }

            nativeAvailabilityChecked = true
            return nativeAvailable
        }
    }

    private fun isNativeLinkageFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnsatisfiedLinkError ||
                current is NoClassDefFoundError ||
                current is ExceptionInInitializerError
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun disableForProcess(reason: String, error: Throwable? = null) {
        synchronized(nativeAvailabilityLock) {
            if (nativeAvailabilityChecked && !nativeAvailable) {
                return
            }

            nativeAvailabilityChecked = true
            nativeAvailable = false
            nativeDisableReason = reason
        }

        val details = error?.message?.takeIf { it.isNotBlank() } ?: "no-details"
        Log.w(TAG, "Sparse ego-motion disabled for process (Option B): $reason ($details)")
    }

    fun estimateCameraVelocity(
        bitmaps: List<Bitmap>,
        frameDetections: List<List<ObjectDetector.Detection>>,
        frameTimestamps: List<Long>
    ): Estimate? {
        if (!ensureNativeAvailability()) {
            return null
        }

        if (bitmaps.size < 2) return null

        val pairVelocities = mutableListOf<PointF>()
        val pairConfidences = mutableListOf<Float>()
        var totalValidTracks = 0

        // Process each consecutive frame pair.
        for (i in 1 until bitmaps.size) {
            val dtMs = frameTimestamps.getOrElse(i) { frameTimestamps.lastOrNull() ?: 0L } -
                    frameTimestamps.getOrElse(i - 1) { frameTimestamps.firstOrNull() ?: 0L }
            val dtSec = max(dtMs / 1000f, 0.010f)

            val prevGray = Mat()
            val currGray = Mat()
            val bgMask = Mat()
            val corners = MatOfPoint()
            val prevPts = MatOfPoint2f()
            val currPts = MatOfPoint2f()
            val status = MatOfByte()
            val err = MatOfFloat()

            try {
                toGray(bitmaps[i - 1], prevGray)
                toGray(bitmaps[i], currGray)

                val detections = frameDetections.getOrElse(i - 1) { emptyList() }
                buildBackgroundMask(prevGray.rows(), prevGray.cols(), detections, bgMask)

                Imgproc.goodFeaturesToTrack(
                    prevGray,
                    corners,
                    MAX_CORNERS,
                    QUALITY_LEVEL,
                    MIN_DISTANCE,
                    bgMask
                )

                val featurePoints = corners.toArray()
                if (featurePoints.isEmpty()) continue

                prevPts.fromArray(*featurePoints)
                Video.calcOpticalFlowPyrLK(
                    prevGray,
                    currGray,
                    prevPts,
                    currPts,
                    status,
                    err,
                    WIN_SIZE,
                    3,
                    TERM_CRITERIA
                )

                val prevArray = prevPts.toArray()
                val currArray = currPts.toArray()
                val statusArray = status.toArray()

                if (prevArray.isEmpty() || currArray.isEmpty() || statusArray.isEmpty()) continue

                val w = prevGray.cols().toFloat().coerceAtLeast(1f)
                val h = prevGray.rows().toFloat().coerceAtLeast(1f)

                val validDx = mutableListOf<Float>()
                val validDy = mutableListOf<Float>()
                for (idx in statusArray.indices) {
                    if (statusArray[idx].toInt() == 0) continue
                    val dx = ((currArray[idx].x - prevArray[idx].x) / w).toFloat()
                    val dy = ((currArray[idx].y - prevArray[idx].y) / h).toFloat()

                    // Drop extreme jumps; they are usually bad tracks or bad correspondences.
                    if (
                        kotlin.math.abs(dx) > MAX_POINT_SHIFT_NORM ||
                        kotlin.math.abs(dy) > MAX_POINT_SHIFT_NORM
                    ) {
                        continue
                    }

                    validDx.add(dx)
                    validDy.add(dy)
                }

                // Need enough stable tracks to trust this pair.
                if (validDx.size < MIN_VALID_TRACKS) continue

                totalValidTracks += validDx.size
                val medianDx = median(validDx)
                val medianDy = median(validDy)
                pairVelocities.add(PointF(medianDx / dtSec, medianDy / dtSec))

                val pairConfidence = (validDx.size.toFloat() / featurePoints.size.toFloat()).coerceIn(0f, 1f)
                pairConfidences.add(pairConfidence)
            } catch (t: Throwable) {
                if (isNativeLinkageFailure(t)) {
                    disableForProcess("runtime:${t.javaClass.simpleName}", t)
                    return null
                }
                Log.w(TAG, "Pair $i sparse tracking failed: ${t.message}")
            } finally {
                prevGray.release()
                currGray.release()
                bgMask.release()
                corners.release()
                prevPts.release()
                currPts.release()
                status.release()
                err.release()
            }
        }

        if (pairVelocities.isEmpty()) return null

        // Aggregate pair estimates with medians to stay robust to outliers.
        val vx = median(pairVelocities.map { it.x })
        val vy = median(pairVelocities.map { it.y })

        val pairCoverage = pairVelocities.size.toFloat() / (bitmaps.size - 1).toFloat()
        val avgPairConfidence = if (pairConfidences.isNotEmpty()) {
            pairConfidences.average().toFloat()
        } else {
            0f
        }

        val confidence = (0.6f * avgPairConfidence + 0.4f * pairCoverage).coerceIn(0f, 1f)
        val estimate = Estimate(PointF(vx, vy), confidence, pairVelocities.size, totalValidTracks)

        Log.d(
            TAG,
            "Sparse ego-motion: vx=${estimate.velocity.x}, vy=${estimate.velocity.y}, " +
                    "conf=${estimate.confidence}, pairs=${estimate.usedPairs}, tracks=${estimate.totalValidTracks}"
        )
        return estimate
    }

    private fun toGray(bitmap: Bitmap, outGray: Mat) {
        val rgba = CvImageUtils.bitmapToRgbaMat(bitmap)
        try {
            Imgproc.cvtColor(rgba, outGray, Imgproc.COLOR_RGBA2GRAY)
        } finally {
            rgba.release()
        }
    }

    private fun buildBackgroundMask(
        rows: Int,
        cols: Int,
        detections: List<ObjectDetector.Detection>,
        outMask: Mat
    ) {
        // White = allowed background area, black = masked-out detection regions.
        outMask.create(rows, cols, CvType.CV_8UC1)
        outMask.setTo(Scalar(255.0))

        val maxX = cols - 1
        val maxY = rows - 1

        for (det in detections) {
            val left = ((det.boundingBox.left * cols).toInt() - MASK_MARGIN_PX).coerceIn(0, maxX)
            val top = ((det.boundingBox.top * rows).toInt() - MASK_MARGIN_PX).coerceIn(0, maxY)
            val right = ((det.boundingBox.right * cols).toInt() + MASK_MARGIN_PX).coerceIn(0, maxX)
            val bottom = ((det.boundingBox.bottom * rows).toInt() + MASK_MARGIN_PX).coerceIn(0, maxY)

            if (right <= left || bottom <= top) continue

            Imgproc.rectangle(
                outMask,
                Point(left.toDouble(), top.toDouble()),
                Point(right.toDouble(), bottom.toDouble()),
                Scalar(0.0),
                -1
            )
        }
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) * 0.5f
        } else {
            sorted[mid]
        }
    }
}

