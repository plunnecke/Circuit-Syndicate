package com.esp32.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer

// Implements YOLO asset for object detection within captured image(s)

class ObjectDetector(context: Context) {

    companion object {
        private const val TAG = "ObjectDetector"
        private const val MODEL_FILENAME = "yolov8n.onnx"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.3f
        private const val IOU_THRESHOLD = 0.45f

        // COCO 80-class labels
        val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )
    }

    data class Detection(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF,  // Normalized coordinates, 0-1
        val classIndex: Int
    )

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false

    // Pre-allocated buffers, avoids repeated garbage collector calls
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val chwBuffer = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    private val outputBuffer = Array(84) { FloatArray(8400) }

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()

            // Load model from assets
            val modelBytes = context.assets.open(MODEL_FILENAME).use { it.readBytes() }

            // Try NNAPI first, fall back to unoptimized CPU resources on failure
            var session: OrtSession? = null
            try {
                val nnapiOptions = OrtSession.SessionOptions().apply {
                    addNnapi()
                    setIntraOpNumThreads(2)
                }
                session = ortEnv!!.createSession(modelBytes, nnapiOptions)
                Log.d(TAG, "NNAPI delegate enabled")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI failed, falling back to CPU: ${e.message}")
                session?.close()
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }
                session = ortEnv!!.createSession(modelBytes, cpuOptions)
                Log.d(TAG, "CPU inference enabled")
            }

            ortSession = session
            isInitialized = true
            Log.d(TAG, "ObjectDetector ready (ONNX Runtime)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ObjectDetector: ${e.message}")
            isInitialized = false
        }
    }

    /**
     * Run YOLO inference on a bitmap.
     * Returns list of detections with normalized bounding boxes.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || ortSession == null || ortEnv == null) {
            Log.e(TAG, "Detector not initialized")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()

        // Resize to model input size
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        resized.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (i in pixelBuffer.indices) {
            val pixel = pixelBuffer[i]
            chwBuffer[i] = ((pixel shr 16) and 0xFF) / 255.0f                          // R channel
            chwBuffer[INPUT_SIZE * INPUT_SIZE + i] = ((pixel shr 8) and 0xFF) / 255.0f  // G channel
            chwBuffer[2 * INPUT_SIZE * INPUT_SIZE + i] = (pixel and 0xFF) / 255.0f      // B channel
        }

        val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv!!, FloatBuffer.wrap(chwBuffer), inputShape)

        // Run inference
        val results: OrtSession.Result
        try {
            results = ortSession!!.run(mapOf("images" to inputTensor))
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            inputTensor.close()
            if (resized != bitmap) resized.recycle()
            return emptyList()
        }

        // Parse output: [1, 84, 8400]
        val outputTensor = results[0] as OnnxTensor
        val rawOutput = outputTensor.floatBuffer
        val numDetections = 8400
        val numChannels = 84  // 4 bbox + 80 classes

        // Read output into pre-allocated buffer [84][8400]
        for (c in 0 until numChannels) {
            for (d in 0 until numDetections) {
                outputBuffer[c][d] = rawOutput.get(c * numDetections + d)
            }
        }

        // Diagnostic: find top 5 raw scores across all detections
        data class RawScore(val detIdx: Int, val classIdx: Int, val score: Float)
        val topScores = mutableListOf<RawScore>()

        // Parse detections
        val detections = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            var maxScore = 0f
            var maxClassIdx = 0
            for (c in 0 until 80) {
                val score = outputBuffer[4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClassIdx = c
                }
            }

            // Track top 5 for diagnostics
            if (topScores.size < 5 || maxScore > (topScores.lastOrNull()?.score ?: 0f)) {
                topScores.add(RawScore(i, maxClassIdx, maxScore))
                topScores.sortByDescending { it.score }
                if (topScores.size > 5) topScores.removeAt(5)
            }

            if (maxScore >= CONFIDENCE_THRESHOLD) {
                val xCenter = outputBuffer[0][i]
                val yCenter = outputBuffer[1][i]
                val width = outputBuffer[2][i]
                val height = outputBuffer[3][i]

                val left = (xCenter - width / 2) / INPUT_SIZE
                val top = (yCenter - height / 2) / INPUT_SIZE
                val right = (xCenter + width / 2) / INPUT_SIZE
                val bottom = (yCenter + height / 2) / INPUT_SIZE

                detections.add(
                    Detection(
                        label = COCO_LABELS[maxClassIdx],
                        confidence = maxScore,
                        boundingBox = RectF(
                            left.coerceIn(0f, 1f),
                            top.coerceIn(0f, 1f),
                            right.coerceIn(0f, 1f),
                            bottom.coerceIn(0f, 1f)
                        ),
                        classIndex = maxClassIdx
                    )
                )
            }
        }

        // Diagnostic: log top 5 raw scores to help debug detection issues
        Log.d(TAG, "Top-5 raw scores (threshold=$CONFIDENCE_THRESHOLD):")
        for (ts in topScores) {
            val label = if (ts.classIdx < COCO_LABELS.size) COCO_LABELS[ts.classIdx] else "class${ts.classIdx}"
            Log.d(TAG, "  det[${ts.detIdx}] ${label} = ${String.format("%.4f", ts.score)}")
        }

        inputTensor.close()
        results.close()

        // Remove duplicate bounding boxes
        val nmsDetections = nonMaxSuppression(detections)

        if (resized != bitmap) resized.recycle()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference: ${nmsDetections.size} detections in ${elapsed}ms")

        return nmsDetections
    }

    /**
     * Non-maximum suppression to remove overlapping detections.
     */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)

            sorted.removeAll { other ->
                other.classIndex == best.classIndex &&
                        computeIoU(best.boundingBox, other.boundingBox) > IOU_THRESHOLD
            }
        }

        return result
    }

    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)

        return interArea / (aArea + bArea - interArea)
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
        ortSession = null
        ortEnv = null
        isInitialized = false
    }
}
