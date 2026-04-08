package com.esp32.camera

import android.graphics.*
import android.util.Log

/**
 * Draws bounding boxes, labels, distance, and motion indicators
 */
class ImageAnnotator {

    companion object {
        private const val TAG = "ImageAnnotator"
        private const val BOX_STROKE_WIDTH = 3f
        private const val TEXT_SIZE = 14f
        private const val LABEL_PADDING = 4f
    }

    // Color palette for different objects detected
    private val classColors = intArrayOf(
        Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW, Color.CYAN,
        Color.MAGENTA, Color.rgb(255, 165, 0), // orange
        Color.rgb(128, 0, 128), // purple
        Color.rgb(0, 128, 0),   // dark green
        Color.rgb(255, 192, 203) // pink
    )

    private val motionArrows = mapOf(
        MotionTracker.MotionState.APPROACHING to "→ YOU",
        MotionTracker.MotionState.MOVING_AWAY to "← AWAY",
        MotionTracker.MotionState.CROSSING_LEFT to "← LEFT",
        MotionTracker.MotionState.CROSSING_RIGHT to "→ RIGHT",
        MotionTracker.MotionState.STATIONARY to "",
        MotionTracker.MotionState.UNKNOWN to ""
    )

    /**
     * @param originalBitmap The raw photo bitmap
     * @param detections List of tracked detections with motion and distance info
     * @param distances Map of detection index to DistanceEstimator.DistanceResult
     * @return Annotated bitmap 
     */
    fun annotate(
        originalBitmap: Bitmap,
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): Bitmap {
        val width = originalBitmap.width
        val height = originalBitmap.height

        val annotated = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)

        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = BOX_STROKE_WIDTH
            isAntiAlias = true
        }

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = TEXT_SIZE * (width / 640f).coerceAtLeast(1f) // Scale with image
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }

        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for ((idx, det) in detections.withIndex()) {
            val color = classColors[det.classIndex % classColors.size]
            boxPaint.color = color

            // Convert normalized coordinates to pixel coordinates
            val left = det.boundingBox.left * width
            val top = det.boundingBox.top * height
            val right = det.boundingBox.right * width
            val bottom = det.boundingBox.bottom * height

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Color fill
            fillPaint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(left, top, right, bottom, fillPaint)

            // Create label text
            val distResult = distances[idx]
            val distText = if (distResult != null) {
                " (${distResult.distanceFeet.toInt()} ft)"
            } else ""
            val motionText = motionArrows[det.motionState] ?: ""
            val labelText = "${det.label} ${(det.confidence * 100).toInt()}%${distText}"

            // Create label background
            val textWidth = textPaint.measureText(labelText)
            val textHeight = textPaint.textSize
            bgPaint.color = Color.argb(180, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRect(
                left, top - textHeight - LABEL_PADDING * 2,
                left + textWidth + LABEL_PADDING * 2, top,
                bgPaint
            )

            // Draw label text
            canvas.drawText(labelText, left + LABEL_PADDING, top - LABEL_PADDING, textPaint)

            // Draw motion indicator 
            if (motionText.isNotEmpty()) {
                val motionPaint = Paint(textPaint).apply {
                    this.color = when (det.motionState) {
                        MotionTracker.MotionState.APPROACHING -> Color.RED
                        MotionTracker.MotionState.CROSSING_LEFT,
                        MotionTracker.MotionState.CROSSING_RIGHT -> Color.YELLOW
                        else -> Color.GREEN
                    }
                }
                canvas.drawText(motionText, left + LABEL_PADDING, bottom + textHeight + LABEL_PADDING, motionPaint)
            }
        }

        Log.d(TAG, "Annotated ${detections.size} detections on ${width}x${height} image")
        return annotated
    }
}
