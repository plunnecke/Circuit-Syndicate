package com.circuitsyndicate.findingtheway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.*
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Implements YOLO asset for object detection within captured image(s)

class ObjectDetector(
    private val context: Context,
    config: Config = Config()
) {

    companion object {
        private const val TAG = "ObjectDetector"
        private const val PREFERRED_MODEL_ASSET = "yolov8m-oiv7.onnx"
        private val FALLBACK_MODEL_ASSETS = listOf(
            "yolov8s-oiv7.onnx",
            "yolov8n-oiv7.onnx"
        )
        private const val OPEN_IMAGES_LABELS_ASSET = "openimages_v7_labels.txt"
        private const val OPEN_IMAGES_V7_CLASS_COUNT = 601
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.12f
        private const val IOU_THRESHOLD = 0.45f
        private const val GENERIC_CLASS_SUPPRESS_IOU = 0.55f
        private const val HIERARCHY_CLASS_SUPPRESS_IOU = 0.65f
        private const val GENERIC_CLASS_CONFIDENCE_PENALTY = 0.03f
        private const val MOTION_TRUST_RANKING_PENALTY_WEIGHT = 0.04f
        private const val TOP_SCORE_LOG_COUNT = 5
        private const val MAX_INTRA_OP_THREADS = 4

        // Open Images includes broad superclass labels. Prefer specific classes
        // when they overlap strongly with generic class detections.
        private val GENERIC_OPEN_IMAGES_LABELS = setOf(
            "animal", "mammal", "vehicle", "land vehicle", "aircraft", "watercraft",
            "furniture", "plant", "tree", "building", "house", "food", "tool"
        )

        // Backward-compatible label alias expected by existing pipeline code.
        @Volatile
        var LABELS: List<String> = emptyList()
            private set
    }

    /** Backward-compatible backend enum expected by existing settings/pipeline code. */
    enum class Backend {
        ONNX
    }

    /**
     * Backward-compatible config seam retained for integration safety.
     * The imported legacy detector enforces ONNX at runtime.
     */
    data class Config(
        val backend: Backend = Backend.ONNX,
        val onnxModelAsset: String = PREFERRED_MODEL_ASSET
    )

    data class Detection(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF,  // Normalized coordinates, 0-1
        val classIndex: Int
    )

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var activeModelName: String = "unknown"
    private var isInitialized = false

    @Volatile
    private var requestedBackend: Backend = config.backend

    @Volatile
    private var activeBackend: Backend = Backend.ONNX

    @Volatile
    private var currentConfig: Config = config

    private var classLabels: List<String> = emptyList()

    private data class RawScore(val detIdx: Int, val classIdx: Int, val score: Float)
    private data class LoadedModelAsset(val assetName: String, val bytes: ByteArray)

    // Pre-allocated buffers, avoids repeated garbage collector calls
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val chwBuffer = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    private val reusableScaledBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val reusableScaleCanvas = Canvas(reusableScaledBitmap)
    private val reusableScaleRect = Rect(0, 0, INPUT_SIZE, INPUT_SIZE)
    private val reusableScalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        try {
            requestedBackend = config.backend
            currentConfig = config
            ortEnv = OrtEnvironment.getEnvironment()

            val loadedModel = loadBestAvailableModelAsset(context, config.onnxModelAsset)
            val modelBytes = loadedModel.bytes
            activeModelName = loadedModel.assetName
            if (activeModelName != config.onnxModelAsset) {
                Log.w(
                    TAG,
                    "Requested model ${config.onnxModelAsset} unavailable, using fallback $activeModelName"
                )
            }
            Log.d(TAG, "Loaded model asset: $activeModelName")

            // Try NNAPI first, fall back to unoptimized CPU resources on failure
            var session: OrtSession? = null
            val cpuThreadCount = Runtime.getRuntime()
                .availableProcessors()
                .coerceIn(2, MAX_INTRA_OP_THREADS)
            try {
                val nnapiOptions = OrtSession.SessionOptions().apply {
                    addNnapi()
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setInterOpNumThreads(1)
                    setIntraOpNumThreads(cpuThreadCount)
                }
                session = ortEnv!!.createSession(modelBytes, nnapiOptions)
                Log.d(TAG, "NNAPI delegate enabled")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI failed, falling back to CPU: ${e.message}")
                session?.close()
                val cpuOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setInterOpNumThreads(1)
                    setIntraOpNumThreads(cpuThreadCount)
                }
                session = ortEnv!!.createSession(modelBytes, cpuOptions)
                Log.d(TAG, "CPU inference enabled")
            }

            ortSession = session
            classLabels = loadOpenImagesV7Labels(context)
            LABELS = classLabels
            isInitialized = true
            activeBackend = Backend.ONNX
            Log.d(
                TAG,
                "ObjectDetector ready (ONNX Runtime, model=$activeModelName, classes=${classLabels.size})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ObjectDetector: ${e.message}")
            isInitialized = false
            throw IllegalStateException("ObjectDetector initialization failed", e)
        }
    }

    private fun loadBestAvailableModelAsset(
        context: Context,
        requestedAsset: String
    ): LoadedModelAsset {
        val candidates = linkedSetOf(
            requestedAsset,
            PREFERRED_MODEL_ASSET,
            *FALLBACK_MODEL_ASSETS.toTypedArray()
        ).filter { it.isNotBlank() }

        var lastError: Exception? = null
        for (assetName in candidates) {
            try {
                val bytes = context.assets.open(assetName).use { it.readBytes() }
                return LoadedModelAsset(assetName = assetName, bytes = bytes)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Model asset $assetName unavailable: ${e.message}")
            }
        }

        throw IllegalStateException(
            "No compatible ONNX model asset found. Tried: ${candidates.joinToString(", ")}",
            lastError
        )
    }

    private fun loadOpenImagesV7Labels(context: Context): List<String> {
        val loaded = runCatching {
            context.assets.open(OPEN_IMAGES_LABELS_ASSET).bufferedReader().useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        }.getOrElse { error ->
            Log.w(TAG, "Could not load $OPEN_IMAGES_LABELS_ASSET: ${error.message}")
            emptyList()
        }

        if (loaded.isNotEmpty()) {
            if (loaded.size != OPEN_IMAGES_V7_CLASS_COUNT) {
                Log.w(
                    TAG,
                    "Open Images labels count ${loaded.size} differs from expected $OPEN_IMAGES_V7_CLASS_COUNT"
                )
            }
            return loaded
        }

        Log.w(TAG, "Falling back to generic Open Images class labels")
        return List(OPEN_IMAGES_V7_CLASS_COUNT) { idx -> "class$idx" }
    }

    fun getRequestedBackend(): Backend = requestedBackend

    fun getActiveBackend(): Backend = activeBackend

    @Synchronized
    fun setBackend(backend: Backend): Backend {
        requestedBackend = Backend.ONNX
        activeBackend = Backend.ONNX
        return activeBackend
    }

    @Synchronized
    fun setConfig(config: Config): Backend {
        currentConfig = config
        requestedBackend = Backend.ONNX
        activeBackend = Backend.ONNX
        return activeBackend
    }

    /**
     * Run YOLO inference on a bitmap.
     * Returns list of detections with normalized bounding boxes.
     */
    @Synchronized
    fun detect(bitmap: Bitmap): List<Detection> {
        if (!isInitialized || ortSession == null || ortEnv == null) {
            Log.e(TAG, "Detector not initialized")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()

        val inputBitmap = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            reusableScaleCanvas.drawBitmap(bitmap, null, reusableScaleRect, reusableScalePaint)
            reusableScaledBitmap
        }

        inputBitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (i in pixelBuffer.indices) {
            val pixel = pixelBuffer[i]
            chwBuffer[i] = ((pixel shr 16) and 0xFF) / 255.0f                          // R channel
            chwBuffer[INPUT_SIZE * INPUT_SIZE + i] = ((pixel shr 8) and 0xFF) / 255.0f  // G channel
            chwBuffer[2 * INPUT_SIZE * INPUT_SIZE + i] = (pixel and 0xFF) / 255.0f      // B channel
        }

        val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv!!, FloatBuffer.wrap(chwBuffer), inputShape)

        // Run inference
        val detections: List<Detection>
        var results: OrtSession.Result? = null
        try {
            val inputName = ortSession!!.inputNames.firstOrNull() ?: "images"
            results = ortSession!!.run(mapOf(inputName to inputTensor))

            val outputTensor = results[0] as? OnnxTensor
                ?: throw IllegalStateException("Unexpected output type from model")

            val outputShape = outputTensor.info.shape.map { it.toInt() }
            detections = parseOutputDetections(outputTensor.floatBuffer, outputShape)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            return emptyList()
        } finally {
            inputTensor.close()
            results?.close()
        }

        // Remove duplicate bounding boxes
        val nmsDetections = nonMaxSuppression(detections)

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Inference: ${nmsDetections.size} detections in ${elapsed}ms")

        return nmsDetections
    }

    private fun parseOutputDetections(rawOutput: FloatBuffer, outputShape: List<Int>): List<Detection> {
        if (outputShape.size < 2) {
            Log.w(TAG, "Unexpected output shape: $outputShape")
            return emptyList()
        }

        if (classLabels.isEmpty()) {
            Log.w(TAG, "Class labels unavailable, cannot decode detections")
            return emptyList()
        }

        val secondLast = outputShape[outputShape.size - 2]
        val last = outputShape[outputShape.size - 1]
        val featureCountNoObjectness = 4 + classLabels.size
        val featureCountWithObjectness = 5 + classLabels.size
        rawOutput.rewind()

        return when {
            // End-to-end layout: [1, N, 6/7] (xyxy + confidence + class)
            last in 6..7 && secondLast > 0 -> {
                Log.d(TAG, "Parsing end-to-end layout (row-major): $outputShape")
                parseEndToEndRowMajor(rawOutput, secondLast, last)
            }
            // End-to-end transposed layout: [1, 6/7, N]
            secondLast in 6..7 && last > 0 -> {
                Log.d(TAG, "Parsing end-to-end layout (transposed): $outputShape")
                parseEndToEndTransposed(rawOutput, secondLast, last)
            }
            // Channel-first layout: [1, 4+N or 5+N, boxes]
            secondLast == featureCountNoObjectness || secondLast == featureCountWithObjectness -> {
                Log.d(TAG, "Parsing channel-first layout: $outputShape")
                parseChannelFirst(rawOutput, secondLast, last)
            }
            // Channel-last layout: [1, boxes, 4+N or 5+N]
            last == featureCountNoObjectness || last == featureCountWithObjectness -> {
                Log.d(TAG, "Parsing channel-last layout: $outputShape")
                parseChannelLast(rawOutput, secondLast, last)
            }
            else -> {
                Log.w(TAG, "Unsupported output shape: $outputShape")
                emptyList()
            }
        }
    }

    private fun parseChannelFirst(
        rawOutput: FloatBuffer,
        channels: Int,
        numDetections: Int
    ): List<Detection> {
        val expectedValues = channels * numDetections
        if (expectedValues > rawOutput.limit()) {
            Log.w(TAG, "Output buffer too small for channel-first layout: expected=$expectedValues actual=${rawOutput.limit()}")
            return emptyList()
        }

        val hasObjectness = channels == classLabels.size + 5
        val classStart = if (hasObjectness) 5 else 4
        val classCount = minOf(classLabels.size, channels - classStart)
        if (classCount <= 0) return emptyList()

        val detections = mutableListOf<Detection>()
        val topScores = mutableListOf<RawScore>()

        for (i in 0 until numDetections) {
            val objectness = if (hasObjectness) rawOutput.get(4 * numDetections + i) else 1f

            var bestScore = 0f
            var bestClassIdx = 0
            for (c in 0 until classCount) {
                val classScore = rawOutput.get((classStart + c) * numDetections + i)
                val combinedScore = objectness * classScore
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestClassIdx = c
                }
            }

            updateTopScores(topScores, i, bestClassIdx, bestScore)

            if (bestScore >= CONFIDENCE_THRESHOLD) {
                val xCenter = rawOutput.get(i)
                val yCenter = rawOutput.get(numDetections + i)
                val width = rawOutput.get(2 * numDetections + i)
                val height = rawOutput.get(3 * numDetections + i)

                detections.add(
                    Detection(
                        label = classLabels[bestClassIdx],
                        confidence = bestScore,
                        boundingBox = xywhToNormalizedRect(xCenter, yCenter, width, height),
                        classIndex = bestClassIdx
                    )
                )
            }
        }

        logTopScores(topScores)
        return detections
    }

    private fun parseChannelLast(
        rawOutput: FloatBuffer,
        numDetections: Int,
        features: Int
    ): List<Detection> {
        val expectedValues = numDetections * features
        if (expectedValues > rawOutput.limit()) {
            Log.w(TAG, "Output buffer too small for channel-last layout: expected=$expectedValues actual=${rawOutput.limit()}")
            return emptyList()
        }

        val hasObjectness = features == classLabels.size + 5
        val classStart = if (hasObjectness) 5 else 4
        val classCount = minOf(classLabels.size, features - classStart)
        if (classCount <= 0) return emptyList()

        val detections = mutableListOf<Detection>()
        val topScores = mutableListOf<RawScore>()

        for (i in 0 until numDetections) {
            val base = i * features
            val objectness = if (hasObjectness) rawOutput.get(base + 4) else 1f

            var bestScore = 0f
            var bestClassIdx = 0
            for (c in 0 until classCount) {
                val classScore = rawOutput.get(base + classStart + c)
                val combinedScore = objectness * classScore
                if (combinedScore > bestScore) {
                    bestScore = combinedScore
                    bestClassIdx = c
                }
            }

            updateTopScores(topScores, i, bestClassIdx, bestScore)

            if (bestScore >= CONFIDENCE_THRESHOLD) {
                detections.add(
                    Detection(
                        label = classLabels[bestClassIdx],
                        confidence = bestScore,
                        boundingBox = xywhToNormalizedRect(
                            rawOutput.get(base),
                            rawOutput.get(base + 1),
                            rawOutput.get(base + 2),
                            rawOutput.get(base + 3)
                        ),
                        classIndex = bestClassIdx
                    )
                )
            }
        }

        logTopScores(topScores)
        return detections
    }

    private fun parseEndToEndRowMajor(
        rawOutput: FloatBuffer,
        numDetections: Int,
        features: Int
    ): List<Detection> {
        val expectedValues = numDetections * features
        if (expectedValues > rawOutput.limit()) {
            Log.w(TAG, "Output buffer too small for end-to-end layout: expected=$expectedValues actual=${rawOutput.limit()}")
            return emptyList()
        }

        val detections = mutableListOf<Detection>()
        val topScores = mutableListOf<RawScore>()

        for (i in 0 until numDetections) {
            val base = i * features
            val confidence = if (features >= 7) {
                rawOutput.get(base + 4) * rawOutput.get(base + 5)
            } else {
                rawOutput.get(base + 4)
            }
            val classRaw = if (features >= 7) rawOutput.get(base + 6) else rawOutput.get(base + 5)
            val classIdx = classRaw.roundToInt()
            if (classIdx !in classLabels.indices) continue

            updateTopScores(topScores, i, classIdx, confidence)

            if (confidence >= CONFIDENCE_THRESHOLD) {
                detections.add(
                    Detection(
                        label = classLabels[classIdx],
                        confidence = confidence,
                        boundingBox = xyxyToNormalizedRect(
                            rawOutput.get(base),
                            rawOutput.get(base + 1),
                            rawOutput.get(base + 2),
                            rawOutput.get(base + 3)
                        ),
                        classIndex = classIdx
                    )
                )
            }
        }

        logTopScores(topScores)
        return detections
    }

    private fun parseEndToEndTransposed(
        rawOutput: FloatBuffer,
        features: Int,
        numDetections: Int
    ): List<Detection> {
        val expectedValues = features * numDetections
        if (expectedValues > rawOutput.limit()) {
            Log.w(TAG, "Output buffer too small for transposed end-to-end layout: expected=$expectedValues actual=${rawOutput.limit()}")
            return emptyList()
        }

        val detections = mutableListOf<Detection>()
        val topScores = mutableListOf<RawScore>()

        for (i in 0 until numDetections) {
            val confidence = if (features >= 7) {
                rawOutput.get(4 * numDetections + i) * rawOutput.get(5 * numDetections + i)
            } else {
                rawOutput.get(4 * numDetections + i)
            }
            val classRaw = if (features >= 7) {
                rawOutput.get(6 * numDetections + i)
            } else {
                rawOutput.get(5 * numDetections + i)
            }
            val classIdx = classRaw.roundToInt()
            if (classIdx !in classLabels.indices) continue

            updateTopScores(topScores, i, classIdx, confidence)

            if (confidence >= CONFIDENCE_THRESHOLD) {
                detections.add(
                    Detection(
                        label = classLabels[classIdx],
                        confidence = confidence,
                        boundingBox = xyxyToNormalizedRect(
                            rawOutput.get(i),
                            rawOutput.get(numDetections + i),
                            rawOutput.get(2 * numDetections + i),
                            rawOutput.get(3 * numDetections + i)
                        ),
                        classIndex = classIdx
                    )
                )
            }
        }

        logTopScores(topScores)
        return detections
    }

    private fun updateTopScores(
        topScores: MutableList<RawScore>,
        detectionIndex: Int,
        classIndex: Int,
        score: Float
    ) {
        if (topScores.size < TOP_SCORE_LOG_COUNT || score > topScores.last().score) {
            topScores.add(RawScore(detectionIndex, classIndex, score))
            topScores.sortByDescending { it.score }
            if (topScores.size > TOP_SCORE_LOG_COUNT) {
                topScores.removeAt(topScores.lastIndex)
            }
        }
    }

    private fun logTopScores(topScores: List<RawScore>) {
        Log.d(TAG, "Top-$TOP_SCORE_LOG_COUNT raw scores (threshold=$CONFIDENCE_THRESHOLD):")
        for (score in topScores) {
            val label = classLabels.getOrElse(score.classIdx) { "class${score.classIdx}" }
            Log.d(
                TAG,
                "  det[${score.detIdx}] $label = ${String.format(Locale.US, "%.4f", score.score)}"
            )
        }
    }

    private fun xywhToNormalizedRect(
        xCenter: Float,
        yCenter: Float,
        width: Float,
        height: Float
    ): RectF {
        val alreadyNormalized = maxOf(abs(xCenter), abs(yCenter), abs(width), abs(height)) <= 2.5f

        val left = if (alreadyNormalized) xCenter - width / 2f else (xCenter - width / 2f) / INPUT_SIZE
        val top = if (alreadyNormalized) yCenter - height / 2f else (yCenter - height / 2f) / INPUT_SIZE
        val right = if (alreadyNormalized) xCenter + width / 2f else (xCenter + width / 2f) / INPUT_SIZE
        val bottom = if (alreadyNormalized) yCenter + height / 2f else (yCenter + height / 2f) / INPUT_SIZE

        return RectF(
            left.coerceIn(0f, 1f),
            top.coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            bottom.coerceIn(0f, 1f)
        )
    }

    private fun xyxyToNormalizedRect(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): RectF {
        val left = normalizeCoordinate(minOf(x1, x2))
        val top = normalizeCoordinate(minOf(y1, y2))
        val right = normalizeCoordinate(maxOf(x1, x2))
        val bottom = normalizeCoordinate(maxOf(y1, y2))
        return RectF(left, top, right, bottom)
    }

    private fun normalizeCoordinate(value: Float): Float {
        if (!value.isFinite()) return 0f
        val normalized = if (value > 1.5f || value < -0.5f) value / INPUT_SIZE else value
        return normalized.coerceIn(0f, 1f)
    }

    /**
     * Non-maximum suppression to remove overlapping detections.
     */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { adjustedRankingConfidence(it) }.toMutableList()
        val result = mutableListOf<Detection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)

            sorted.removeAll { other ->
                val iou = computeIoU(best.boundingBox, other.boundingBox)
                val sameClassOverlap =
                    other.classIndex == best.classIndex && iou > IOU_THRESHOLD
                val suppressGenericOverlap =
                    !isGenericOpenImagesLabel(best.label) &&
                        isGenericOpenImagesLabel(other.label) &&
                        iou > GENERIC_CLASS_SUPPRESS_IOU
                val suppressHierarchyOverlap =
                    LabelSemantics.areHierarchyCompatible(best.label, other.label) &&
                        iou > HIERARCHY_CLASS_SUPPRESS_IOU

                sameClassOverlap || suppressGenericOverlap || suppressHierarchyOverlap
            }
        }

        return pruneGenericSuperclassOverlaps(result)
    }

    private fun adjustedRankingConfidence(detection: Detection): Float {
        val genericPenalty = if (isGenericOpenImagesLabel(detection.label)) {
            GENERIC_CLASS_CONFIDENCE_PENALTY
        } else {
            0f
        }
        val trustPenalty = (
            1f - LabelSemantics.motionTrust(detection.label).coerceIn(0.45f, 1f)
            ) * MOTION_TRUST_RANKING_PENALTY_WEIGHT
        return detection.confidence - genericPenalty - trustPenalty
    }

    private fun isGenericOpenImagesLabel(label: String): Boolean {
        val normalized = LabelSemantics.normalize(label)
        return normalized in GENERIC_OPEN_IMAGES_LABELS
    }

    private fun pruneGenericSuperclassOverlaps(detections: List<Detection>): List<Detection> {
        val specific = detections.filterNot { isGenericOpenImagesLabel(it.label) }
        if (specific.isEmpty()) return detections

        return detections.filter { candidate ->
            if (!isGenericOpenImagesLabel(candidate.label)) return@filter true
            specific.none { specificDet ->
                computeIoU(candidate.boundingBox, specificDet.boundingBox) > GENERIC_CLASS_SUPPRESS_IOU
            }
        }
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

