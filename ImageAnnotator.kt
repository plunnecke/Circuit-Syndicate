package com.circuitsyndicate.findingtheway

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws YOLOv8 detection results onto a copy of the input bitmap.
 *
 * Each box is colour-coded by ThreatLevel:
 *   CRITICAL → red
 *   HIGH     → orange
 *   MODERATE → yellow
 *   LOW      → green
 *   UNKNOWN  → cyan
 *
 * Label format:  "person  ~8 ft  [APPROACHING]"
 */
class ImageAnnotator {

    companion object {
        private val THREAT_COLORS = mapOf(
            MotionTracker.ThreatLevel.CRITICAL to Color.rgb(220,  50,  50),
            MotionTracker.ThreatLevel.HIGH     to Color.rgb(255, 140,   0),
            MotionTracker.ThreatLevel.MODERATE to Color.rgb(255, 200,   0),
            MotionTracker.ThreatLevel.LOW      to Color.rgb( 60, 200,  60),
            MotionTracker.ThreatLevel.UNKNOWN  to Color.rgb( 60, 200, 200)
        )

        private const val BOX_STROKE    = 4f
        private const val TEXT_SIZE_PX  = 36f
        private const val LABEL_PADDING = 8f
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = BOX_STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 180
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = TEXT_SIZE_PX
        typeface  = Typeface.DEFAULT_BOLD
        style     = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.BLACK
        textSize  = TEXT_SIZE_PX
        typeface  = Typeface.DEFAULT_BOLD
        style     = Paint.Style.FILL
    }

    /**
     * Returns a new mutable bitmap with all detections annotated.
     * Caller is responsible for recycling the returned bitmap.
     */
    fun annotate(
        source: Bitmap,
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // Scale text to frame size — keep it readable on small frames
        val scaledTextSize = (result.height * 0.045f).coerceIn(24f, 52f)
        textPaint.textSize   = scaledTextSize
        shadowPaint.textSize = scaledTextSize

        detections.forEachIndexed { i, det ->
            val color = THREAT_COLORS[det.threatLevel] ?: Color.CYAN
            drawDetection(canvas, det, distances[i], color)
        }

        return result
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private fun drawDetection(
        canvas: Canvas,
        det: MotionTracker.TrackedDetection,
        distResult: DistanceEstimator.DistanceResult?,
        color: Int
    ) {
        val box = det.boundingBox

        // Bounding box
        boxPaint.color = color
        canvas.drawRect(box, boxPaint)

        // Label text
        val label = buildLabel(det, distResult)

        val textW    = textPaint.measureText(label)
        val textH    = textPaint.textSize
        val labelX   = box.left.coerceAtLeast(0f)
        val labelY   = if (box.top > textH + LABEL_PADDING * 2) {
            box.top - LABEL_PADDING
        } else {
            box.top + textH + LABEL_PADDING
        }

        // Label background
        fillPaint.color = color
        canvas.drawRect(
            RectF(
                labelX,
                labelY - textH - LABEL_PADDING,
                labelX + textW + LABEL_PADDING * 2,
                labelY + LABEL_PADDING
            ),
            fillPaint
        )

        // Drop shadow then text
        canvas.drawText(label, labelX + LABEL_PADDING + 2f, labelY + 1f, shadowPaint)
        canvas.drawText(label, labelX + LABEL_PADDING, labelY, textPaint)

        // Confidence badge (bottom-right of box)
        val confText = "${"%.0f".format(det.confidence * 100)}%"
        val confX    = (box.right - textPaint.measureText(confText) - LABEL_PADDING)
            .coerceAtLeast(box.left)
        val confY    = box.bottom - LABEL_PADDING
        canvas.drawText(confText, confX + 1f, confY + 1f, shadowPaint)
        canvas.drawText(confText, confX, confY, textPaint)
    }

    private fun buildLabel(
        det: MotionTracker.TrackedDetection,
        dist: DistanceEstimator.DistanceResult?
    ): String {
        val sb = StringBuilder(det.label)

        dist?.distanceFeet?.let { ft ->
            sb.append("  ~${ft.toInt()} ft")
        }

        val motionStr = when (det.motionState) {
            MotionTracker.MotionState.APPROACHING    -> "↑ APPR"
            MotionTracker.MotionState.MOVING_AWAY   -> "↓ AWAY"
            MotionTracker.MotionState.CROSSING_LEFT -> "← LEFT"
            MotionTracker.MotionState.CROSSING_RIGHT-> "→ RIGHT"
            MotionTracker.MotionState.STATIONARY    -> ""
            MotionTracker.MotionState.UNKNOWN       -> ""
        }
        if (motionStr.isNotEmpty()) sb.append("  $motionStr")

        return sb.toString()
    }
}
