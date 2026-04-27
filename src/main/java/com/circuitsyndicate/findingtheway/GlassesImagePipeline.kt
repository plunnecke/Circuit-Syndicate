package com.circuitsyndicate.findingtheway

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.circuitsyndicate.findingtheway.storage.ImageStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * This class is basically the phone-side pipeline for images coming from the
 * glasses over BLE.
 *
 * Main flow (in order):
 *   1. Rebuild JPEG data from BLE chunks (single photo or burst)
 *   2. Validate JPEG header/trailer
 *   3. Save raw frames to Pictures/FindingTheWay/
 *   4. Run YOLO object detection through ObjectDetector
 *   5. Run distance estimation through DistanceEstimator
 *   6. Run motion tracking through MotionTracker
 *   7. Annotate and save the result image
 *   8. Speak detections through DetectionSpeaker
 *   9. Broadcast OBJECT_DETECTED + GLASSES_IMAGE_SAVED
 *
 * BLE chunk protocol from ESP32-CAM notifications:
 *
 *   Single photo:
 *     Chunk 0:  [frameIdx_lo, frameIdx_hi, orientation, ...jpeg_bytes]
 *     Chunk N:  [frameIdx_lo, frameIdx_hi, ...jpeg_bytes]
 *     End:      [0xFF, 0xFF]   (frameIndex == 0xFFFF, 2 bytes)
 *
 *   Burst:
 *     Chunk 0 (legacy): [frameIdx_lo, frameIdx_hi, burstSeq, orientation, ...jpeg_bytes]
 *     Chunk 0 (current):[frameIdx_lo, frameIdx_hi, burstSeq, orientation,
 *                        captureTimeMs_le(4), ...jpeg_bytes]
 *     Chunk N:  [frameIdx_lo, frameIdx_hi, burstSeq, ...jpeg_bytes]
 *     Frame end:[0xFF, 0xFF, burstSeq]   (3 bytes)
 *     Burst end:[0xFE, 0xFF]             (2 bytes)
 *
 * Commands sent to the ESP32-CAM over BLE GATT writes:
 *   CMD_SINGLE_PHOTO = 0x01
 *   CMD_BURST        = 0x04
 *   CMD_BURST_CADENCE_HINT = 0x05  (optional follow-up hint payload)
 */
class GlassesImagePipeline(private val context: Context) {

    companion object {
        private const val TAG = "GlassesPipeline"

        const val CMD_CAPTURE_STOP: Byte = 0x00
        const val CMD_SINGLE_PHOTO: Byte = 0x01
        const val CMD_CAPTURE_ENABLE: Byte = 0x03
        const val CMD_BURST: Byte = 0x04
        const val CMD_BURST_CADENCE_HINT: Byte = 0x05
        const val ACTION_DETECTOR_BACKEND_CHANGED = "DETECTOR_BACKEND_CHANGED"
        const val ACTION_BURST_TIMING_UPDATED = "BURST_TIMING_UPDATED"
        const val ACTION_BURST_CADENCE_HANDOFF_UPDATED = "BURST_CADENCE_HANDOFF_UPDATED"

        private const val CAPTURE_DISABLE_STALE_FLUSH_MS = 5 * 60 * 1000L
        private const val CAPTURE_ACTIVITY_RECENT_WINDOW_MS = 15_000L
        private const val CAPTURE_CYCLE_NO_DATA_TIMEOUT_MS = 12_000L
        private const val CAPTURE_CYCLE_TRANSFER_IDLE_TIMEOUT_MS = 8_000L
        private const val CAPTURE_CYCLE_INFERENCE_TIMEOUT_MS = 45_000L
        private const val SINGLE_MODE_BURST_MISMATCH_COOLDOWN_MS = 750L

        private const val BURST_TIMING_ROLLING_WINDOW = 20
        private const val CADENCE_MODERATE_SPREAD_MS = 60L
        private const val CADENCE_DEGRADED_SPREAD_MS = 120L
        // Motion-priority cadence is intentionally spread over ~2s across burst frames,
        // so total latency thresholds are wider to avoid false DEGRADED flags.
        private const val CADENCE_MODERATE_TOTAL_LATENCY_MS = 5000L
        private const val CADENCE_DEGRADED_TOTAL_LATENCY_MS = 8000L
        private const val CADENCE_DEGRADED_DROP_PER_FRAME = 0.20
        private const val RECOMMEND_DELAY_MODERATE_BUMP_MS = 10L
        private const val RECOMMEND_DELAY_DEGRADED_BUMP_MS = 20L
        private const val MOTION_PRIORITY_BURST_FRAMES = 4L
        private const val MOTION_PRIORITY_BURST_SPAN_MS = 2000L
        private const val RECOMMEND_DELAY_MIN_MS =
            (MOTION_PRIORITY_BURST_SPAN_MS + (MOTION_PRIORITY_BURST_FRAMES - 2L)) /
                (MOTION_PRIORITY_BURST_FRAMES - 1L)
        private const val RECOMMEND_DELAY_MAX_MS = 1200L
        private const val DETECTOR_MODEL_SWAP_COOLDOWN_MS = 20_000L
        private const val DETECTOR_BURST_DOWNSHIFT_THRESHOLD_MS = 1_800L
        private const val DETECTOR_UPSHIFT_SIGNIFICANT_DELAY_MS = 450L
        private const val DETECTOR_IDLE_UPSHIFT_DELAY_MS = 7_000L
        private const val DETECTOR_IDLE_UPSHIFT_POLL_MS = 250L
        private const val CADENCE_ACK_MARKER_LO: Byte = 0xFD.toByte()
        private const val CADENCE_ACK_MARKER_HI: Byte = 0xFF.toByte()
        private const val CADENCE_ACK_VERSION = 0x01
        private const val CADENCE_ACK_APPLIED = 0x00
        private const val CADENCE_ACK_CLAMPED = 0x01
        private const val CADENCE_ACK_REJECTED = 0x02
        private const val TRANSFER_ABORT_MARKER_LO: Byte = 0xFC.toByte()
        private const val TRANSFER_ABORT_MARKER_HI: Byte = 0xFF.toByte()
        private const val TRANSFER_ABORT_REASON_UNKNOWN = 0x00
        private const val TRANSFER_ABORT_REASON_CMD_SINGLE = 0x01
        private const val TRANSFER_ABORT_REASON_CMD_BURST = 0x02
        private const val TRANSFER_ABORT_REASON_CAPTURE_DISABLED = 0x03
        private const val TRANSFER_ABORT_REASON_BUTTON_PRIORITY = 0x04
        private const val TRANSFER_ABORT_REASON_RUNTIME_GUARD = 0x05
        private const val DETECTOR_MODEL_SINGLE_TOP = "yolov8m-oiv7.onnx"
        private const val DETECTOR_MODEL_BURST_TOP = "yolov8m-oiv7.onnx"
        private val DETECTOR_MODEL_SINGLE_DOWNSHIFT_PATH = listOf(
            DETECTOR_MODEL_SINGLE_TOP,
            "yolov8s-oiv7.onnx",
            "yolov8n-oiv7.onnx"
        )
        private val DETECTOR_MODEL_BURST_DOWNSHIFT_PATH = listOf(
            DETECTOR_MODEL_BURST_TOP,
            "yolov8s-oiv7.onnx",
            "yolov8n-oiv7.onnx"
        )
    }

    enum class BurstCadenceHealth {
        STABLE,
        MODERATE,
        DEGRADED
    }

    private enum class InferenceProfile {
        SINGLE,
        BURST
    }

    data class BurstTimingStats(
        val requestStartedAtMs: Long,
        val firstFrameAtMs: Long?,
        val burstCompletedAtMs: Long,
        val framesInBurst: Int,
        val droppedChunks: Int,
        val firstFrameLatencyMs: Long?,
        val totalLatencyMs: Long,
        val averageFrameIntervalMs: Long?,
        val minFrameIntervalMs: Long?,
        val maxFrameIntervalMs: Long?
    )

    data class BurstTimingAggregateStats(
        val burstsInWindow: Int,
        val framesInWindow: Int,
        val droppedChunksInWindow: Int,
        val averageFirstFrameLatencyMs: Long?,
        val averageTotalLatencyMs: Long,
        val averageFrameIntervalMs: Long?,
        val minFrameIntervalMs: Long?,
        val maxFrameIntervalMs: Long?,
        val intervalSpreadMs: Long?,
        val cadenceHealth: BurstCadenceHealth,
        val recommendedInterFrameDelayMs: Long?,
        val droppedChunksPerBurst: Double,
        val droppedChunksPerFrame: Double
    )

    data class BurstCadenceHandoffFeedback(
        val receivedAtMs: Long,
        val protocolVersion: Int,
        val ackCode: Int,
        val ackLabel: String,
        val appliedInterFrameDelayMs: Long?
    )

    private data class CaptureCycleTiming(
        val id: Long,
        val mode: String,
        val trigger: String,
        val startedElapsedMs: Long,
        val startedWallMs: Long,
        var commandDispatchedElapsedMs: Long? = null,
        var firstDataElapsedMs: Long? = null,
        var lastDataElapsedMs: Long? = null,
        var transferCompletedElapsedMs: Long? = null,
        var inferenceStartedElapsedMs: Long? = null,
        var inferenceCompletedElapsedMs: Long? = null,
        var ttsQueuedElapsedMs: Long? = null,
        var ttsStartedElapsedMs: Long? = null,
        var ttsCompletedElapsedMs: Long? = null
    )

    private data class CaptureCycleTimeoutDecision(
        val reason: String,
        val idleMs: Long,
        val thresholdMs: Long
    )

    // ── Inference stack (initialized once, async) ────────────────────────────
    // This path is ONNX-only in the migrated app.

    @Volatile var objectDetector: ObjectDetector? = null
    @Volatile var distanceEstimator: DistanceEstimator? = null
    @Volatile var motionTracker: MotionTracker? = null
    @Volatile var imageAnnotator: ImageAnnotator? = null
    @Volatile var detectorBackendPreference: ObjectDetector.Backend =
        DetectorBackendSettings.sanitizeBackendForAvailability(
            DetectorBackendSettings.getBackend(context)
        )

    private val speaker: DetectionSpeaker = DetectionSpeaker(context)
    private val imageStorage = ImageStorageManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Mode + capture state ─────────────────────────────────────────────────

    var isBurstMode = false
        private set

    private val captureGateLock = Any()
    @Volatile private var captureGateEnabled = true
    @Volatile private var captureGateDisabledAtMs: Long? = null
    @Volatile private var captureGateEpoch: Long = 0L
    @Volatile private var captureGateStaleFlushed = false
    @Volatile private var processingInFlight = false
    @Volatile private var processingPreemptLatch = false
    private var captureGateFlushJob: Job? = null

    // ── Single-photo reassembly state ────────────────────────────────────────

    private val photoBuffer = ByteArrayOutputStream()
    private var expectedChunkIndex = 0
    private var photoCount = 0
    private var isReceiving = false
    private var totalBytesReceived = 0
    private var droppedChunks = 0

    // ── Burst reassembly state ───────────────────────────────────────────────

    private val burstFrameBuffers = mutableMapOf<Int, ByteArrayOutputStream>()
    private val burstFrameTimestamps = mutableMapOf<Int, Long>()
    private val burstFrameCaptureTimeHints = mutableMapOf<Int, Long>()
    private var currentBurstSeq = -1
    private var burstExpectedChunkIndex = 0
    private var burstDroppedChunks = 0
    @Volatile private var burstReassemblyPinnedForPostProcess = false
    @Volatile private var lastSingleModeBurstMismatchElapsedMs: Long = 0L
    @Volatile private var burstRequestStartedAtMs: Long = 0L
    @Volatile private var burstFirstFrameAtMs: Long? = null
    @Volatile private var lastBurstTimingStats: BurstTimingStats? = null
    private val recentBurstTimingStats = ArrayDeque<BurstTimingStats>()
    private val burstTimingAggregateLock = Any()
    @Volatile private var lastBurstTimingAggregateStats: BurstTimingAggregateStats? = null
    @Volatile private var lastBurstCadenceHandoffFeedback: BurstCadenceHandoffFeedback? = null
    @Volatile private var detectorModelAutoSwapEnabled = false
    @Volatile private var detectorUpshiftPerInferenceEnabled = false
    @Volatile private var lastDetectorModelSwapAtMs: Long = 0L
    @Volatile private var lastCaptureCommandElapsedMs: Long = 0L
    @Volatile private var lastInferenceImageDataElapsedMs: Long = 0L
    @Volatile private var lastInferenceProfile: InferenceProfile = InferenceProfile.SINGLE
    private var detectorIdleUpshiftJob: Job? = null
    private val detectorModelSwapLock = Any()

    private val captureCycleLock = Any()
    private var captureCycleCounter = 0L
    private var activeCaptureCycle: CaptureCycleTiming? = null
    private val utteranceToCycleId = mutableMapOf<String, Long>()

    // ── Init/setup ────────────────────────────────────────────────────────────

    init {
        speaker.setTelemetryListener(object : DetectionSpeaker.TelemetryListener {
            override fun onUtteranceStart(utteranceId: String) {
                onCycleUtteranceStarted(utteranceId)
            }

            override fun onUtteranceDone(utteranceId: String) {
                onCycleUtteranceCompleted(utteranceId, errorCode = null)
            }

            override fun onUtteranceError(utteranceId: String, errorCode: Int?) {
                onCycleUtteranceCompleted(utteranceId, errorCode)
            }
        })

        scope.launch {
            try {
                objectDetector = ObjectDetector(
                    context,
                    ObjectDetector.Config(detectorBackendPreference)
                )
                distanceEstimator = DistanceEstimator()
                motionTracker    = MotionTracker()
                imageAnnotator   = ImageAnnotator()
                Log.d(
                    TAG,
                    "Inference pipeline ready (detector requested=$detectorBackendPreference, active=${objectDetector?.getActiveBackend()})"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Inference pipeline init failed: ${e.message}", e)
            }
        }
    }

    fun getRequestedDetectorBackend(): ObjectDetector.Backend = detectorBackendPreference

    fun getActiveDetectorBackend(): ObjectDetector.Backend =
        objectDetector?.getActiveBackend() ?: detectorBackendPreference

    fun getLastBurstTimingStats(): BurstTimingStats? = lastBurstTimingStats

    fun getLastBurstTimingAggregateStats(): BurstTimingAggregateStats? = lastBurstTimingAggregateStats

    fun getLastBurstCadenceHandoffFeedback(): BurstCadenceHandoffFeedback? =
        lastBurstCadenceHandoffFeedback

    fun resetBurstTimingHistory() {
        synchronized(burstTimingAggregateLock) {
            recentBurstTimingStats.clear()
            lastBurstTimingStats = null
            lastBurstTimingAggregateStats = null
            lastBurstCadenceHandoffFeedback = null
        }
    }

    fun isDetectorModelAutoSwapEnabled(): Boolean = detectorModelAutoSwapEnabled

    fun setDetectorModelAutoSwapEnabled(enabled: Boolean) {
        detectorModelAutoSwapEnabled = enabled
        if (enabled) {
            // Keep the policy simple: avoid upshift churn around every inference.
            detectorUpshiftPerInferenceEnabled = false
            scheduleDetectorIdleUpshiftCheck(lastInferenceProfile)
        } else {
            detectorIdleUpshiftJob?.cancel()
            detectorIdleUpshiftJob = null
        }
    }

    /** Update preferred backend and reconfigure the detector. */
    fun setDetectorBackend(
        backend: ObjectDetector.Backend,
        onApplied: ((requested: ObjectDetector.Backend, active: ObjectDetector.Backend) -> Unit)? = null
    ) {
        val requestedBackend = DetectorBackendSettings.sanitizeBackendForAvailability(backend)
        detectorBackendPreference = requestedBackend
        DetectorBackendSettings.setBackend(context, requestedBackend)

        scope.launch {
            try {
                val detector = objectDetector
                val active = if (detector == null) {
                    val created = ObjectDetector(context, ObjectDetector.Config(backend = requestedBackend))
                    objectDetector = created
                    created.getActiveBackend()
                } else {
                    detector.setBackend(requestedBackend)
                }

                Log.d(
                    TAG,
                    "Detector backend configured (requested=$requestedBackend, active=$active)"
                )

                withContext(Dispatchers.Main) {
                    onApplied?.invoke(requestedBackend, active)
                    broadcastDetectorBackendChanged(requestedBackend, active)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to configure detector backend $requestedBackend: ${e.message}", e)
                val active = getActiveDetectorBackend()

                withContext(Dispatchers.Main) {
                    onApplied?.invoke(requestedBackend, active)
                    broadcastDetectorBackendChanged(requestedBackend, active)
                }
            }
        }
    }

    private fun broadcastDetectorBackendChanged(
        requested: ObjectDetector.Backend,
        active: ObjectDetector.Backend
    ) {
        val intent = Intent(ACTION_DETECTOR_BACKEND_CHANGED).apply {
            setPackage(context.packageName)
            putExtra("requestedBackend", requested.name)
            putExtra("activeBackend", active.name)
        }
        context.sendBroadcast(intent)
    }

    // ── Command helpers ───────────────────────────────────────────────────────

    fun prepareSinglePhoto() {
        // Keep burst buffers around until cycle completion so burst inference/TTS
        // does not get interrupted by rapid command changes.
        isBurstMode = false
        lastInferenceProfile = InferenceProfile.SINGLE
        clearSingleReassemblyState()
    }

    fun prepareBurst() {
        isBurstMode = true
        lastInferenceProfile = InferenceProfile.BURST
        clearBurstReassemblyState()
        burstRequestStartedAtMs = System.currentTimeMillis()
        burstFirstFrameAtMs = null
        clearSingleReassemblyState()
    }

    fun beginCaptureCycle(mode: String, trigger: String, allowSupersede: Boolean = false): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            expireStaleActiveCycleLocked(nowElapsed)
            activeCaptureCycle?.let { prior ->
                val supersedeBlocked = shouldBlockCycleSupersedeLocked(prior)
                val burstPriorityLock = burstReassemblyPinnedForPostProcess
                if (supersedeBlocked && (!allowSupersede || burstPriorityLock)) {
                    InteractionLogger.logSessionEvidence(
                        "GLASSES_CYCLE",
                        "CYCLE_START_BLOCKED",
                        "mode=$mode;trigger=$trigger;active_id=${prior.id};active_mode=${prior.mode};active_trigger=${prior.trigger};reason=${if (burstPriorityLock) "BURST_POSTPROCESS_PRIORITY" else "ACTIVE_PIPELINE"}"
                    )
                    return false
                }
                completeCycleLocked(prior, "SUPERSEDED_BY_NEW_COMMAND", nowElapsed)
            }

            val cycle = CaptureCycleTiming(
                id = ++captureCycleCounter,
                mode = mode,
                trigger = trigger,
                startedElapsedMs = nowElapsed,
                startedWallMs = System.currentTimeMillis()
            )
            activeCaptureCycle = cycle
            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_START",
                "id=${cycle.id};mode=${cycle.mode};trigger=${cycle.trigger};start_wall_ms=${cycle.startedWallMs}"
            )
            return true
        }
    }

    private fun shouldBlockCycleSupersedeLocked(cycle: CaptureCycleTiming): Boolean {
        if (burstReassemblyPinnedForPostProcess) return true
        if (processingInFlight) return true
        if (cycle.transferCompletedElapsedMs == null) return true
        if (cycle.inferenceCompletedElapsedMs == null) return true
        return false
    }

    fun markCaptureCommandDispatch(command: Byte, sent: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return

            if (sent) {
                lastCaptureCommandElapsedMs = nowElapsed
            }

            if (cycle.commandDispatchedElapsedMs == null) {
                cycle.commandDispatchedElapsedMs = nowElapsed
            }

            val commandMs = (cycle.commandDispatchedElapsedMs!! - cycle.startedElapsedMs).coerceAtLeast(0L)
            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_STAGE",
                "id=${cycle.id};mode=${cycle.mode};stage=COMMAND;duration_ms=$commandMs;sent=${if (sent) 1 else 0};command=${commandLabel(command)}"
            )

            if (!sent) {
                completeCycleLocked(cycle, "COMMAND_NOT_SENT", nowElapsed)
            }
        }
    }

    private fun commandLabel(command: Byte): String {
        return when (command) {
            CMD_SINGLE_PHOTO -> "CMD_SINGLE_PHOTO"
            CMD_BURST -> "CMD_BURST"
            CMD_CAPTURE_ENABLE -> "CMD_CAPTURE_ENABLE"
            CMD_CAPTURE_STOP -> "CMD_CAPTURE_STOP"
            CMD_BURST_CADENCE_HINT -> "CMD_BURST_CADENCE_HINT"
            else -> String.format(Locale.US, "0x%02X", command.toInt() and 0xFF)
        }
    }

    private fun markCycleDataReceived() {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return
            cycle.lastDataElapsedMs = nowElapsed
            if (cycle.firstDataElapsedMs != null) return

            cycle.firstDataElapsedMs = nowElapsed
            val captureMs = (
                cycle.firstDataElapsedMs!! -
                    (cycle.commandDispatchedElapsedMs ?: cycle.startedElapsedMs)
                ).coerceAtLeast(0L)

            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_STAGE",
                "id=${cycle.id};mode=${cycle.mode};stage=CAPTURE;duration_ms=$captureMs"
            )
        }
    }

    private fun timeoutDecisionForActiveCycleLocked(
        cycle: CaptureCycleTiming,
        nowElapsed: Long
    ): CaptureCycleTimeoutDecision? {
        if (cycle.firstDataElapsedMs == null) {
            val anchorElapsed = cycle.commandDispatchedElapsedMs ?: cycle.startedElapsedMs
            val idleMs = (nowElapsed - anchorElapsed).coerceAtLeast(0L)
            if (idleMs >= CAPTURE_CYCLE_NO_DATA_TIMEOUT_MS) {
                return CaptureCycleTimeoutDecision(
                    reason = "TIMEOUT_WAITING_FOR_CAPTURE_DATA",
                    idleMs = idleMs,
                    thresholdMs = CAPTURE_CYCLE_NO_DATA_TIMEOUT_MS
                )
            }
            return null
        }

        if (cycle.transferCompletedElapsedMs == null) {
            val anchorElapsed = cycle.lastDataElapsedMs ?: cycle.firstDataElapsedMs!!
            val idleMs = (nowElapsed - anchorElapsed).coerceAtLeast(0L)
            if (idleMs >= CAPTURE_CYCLE_TRANSFER_IDLE_TIMEOUT_MS) {
                return CaptureCycleTimeoutDecision(
                    reason = "TIMEOUT_WAITING_FOR_TRANSFER_END",
                    idleMs = idleMs,
                    thresholdMs = CAPTURE_CYCLE_TRANSFER_IDLE_TIMEOUT_MS
                )
            }
            return null
        }

        if (cycle.inferenceCompletedElapsedMs == null) {
            val anchorElapsed = cycle.inferenceStartedElapsedMs ?: cycle.transferCompletedElapsedMs!!
            val idleMs = (nowElapsed - anchorElapsed).coerceAtLeast(0L)
            if (idleMs >= CAPTURE_CYCLE_INFERENCE_TIMEOUT_MS) {
                return CaptureCycleTimeoutDecision(
                    reason = "TIMEOUT_WAITING_FOR_INFERENCE_DONE",
                    idleMs = idleMs,
                    thresholdMs = CAPTURE_CYCLE_INFERENCE_TIMEOUT_MS
                )
            }
        }

        return null
    }

    private fun expireStaleActiveCycleLocked(nowElapsed: Long): Boolean {
        val cycle = activeCaptureCycle ?: return false
        val timeout = timeoutDecisionForActiveCycleLocked(cycle, nowElapsed) ?: return false

        InteractionLogger.logSessionEvidence(
            "GLASSES_CYCLE",
            "CYCLE_TIMEOUT",
            "id=${cycle.id};mode=${cycle.mode};reason=${timeout.reason};idle_ms=${timeout.idleMs};threshold_ms=${timeout.thresholdMs}"
        )

        clearSingleReassemblyState()
        clearBurstReassemblyState(reason = "cycle_timeout:${timeout.reason}")
        isBurstMode = false
        completeCycleLocked(cycle, timeout.reason, nowElapsed)
        return true
    }

    private fun markCycleTransferCompleted(stageLabel: String, frames: Int? = null, dropped: Int? = null) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return
            if (cycle.transferCompletedElapsedMs != null) return

            cycle.transferCompletedElapsedMs = nowElapsed
            val transferMs = cycle.firstDataElapsedMs?.let {
                (cycle.transferCompletedElapsedMs!! - it).coerceAtLeast(0L)
            }

            val detail = buildString {
                append("id=${cycle.id};mode=${cycle.mode};stage=TRANSFER;marker=$stageLabel")
                append(";duration_ms=${transferMs ?: -1L}")
                if (frames != null) append(";frames=$frames")
                if (dropped != null) append(";dropped=$dropped")
            }

            InteractionLogger.logSessionEvidence("GLASSES_CYCLE", "CYCLE_STAGE", detail)
        }
    }

    private fun markInferenceStartedIfNeeded() {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return
            if (cycle.inferenceStartedElapsedMs == null) {
                cycle.inferenceStartedElapsedMs = nowElapsed
            }
        }
    }

    private fun markInferenceCompletedIfNeeded() {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return
            if (cycle.inferenceStartedElapsedMs == null || cycle.inferenceCompletedElapsedMs != null) return

            cycle.inferenceCompletedElapsedMs = nowElapsed
            val inferenceMs = (cycle.inferenceCompletedElapsedMs!! - cycle.inferenceStartedElapsedMs!!)
                .coerceAtLeast(0L)

            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_STAGE",
                "id=${cycle.id};mode=${cycle.mode};stage=INFERENCE;duration_ms=$inferenceMs"
            )
        }
    }

    private fun markTtsQueued(utteranceId: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return

            cycle.ttsQueuedElapsedMs = nowElapsed
            utteranceToCycleId[utteranceId] = cycle.id

            val queueDelayMs = cycle.inferenceCompletedElapsedMs?.let {
                (cycle.ttsQueuedElapsedMs!! - it).coerceAtLeast(0L)
            }
            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_STAGE",
                "id=${cycle.id};mode=${cycle.mode};stage=TTS_QUEUE;duration_ms=${queueDelayMs ?: -1L};utterance_id=$utteranceId"
            )
        }
    }

    private fun onCycleUtteranceStarted(utteranceId: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycleId = utteranceToCycleId[utteranceId] ?: return
            val cycle = activeCaptureCycle ?: return
            if (cycle.id != cycleId) return

            cycle.ttsStartedElapsedMs = nowElapsed
            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_STAGE",
                "id=${cycle.id};mode=${cycle.mode};stage=TTS_START;utterance_id=$utteranceId"
            )
        }
    }

    private fun onCycleUtteranceCompleted(utteranceId: String, errorCode: Int?) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycleId = utteranceToCycleId.remove(utteranceId) ?: return
            val cycle = activeCaptureCycle ?: return
            if (cycle.id != cycleId) return

            cycle.ttsCompletedElapsedMs = nowElapsed
            val ttsSpeakMs = if (cycle.ttsStartedElapsedMs != null) {
                (cycle.ttsCompletedElapsedMs!! - cycle.ttsStartedElapsedMs!!).coerceAtLeast(0L)
            } else {
                null
            }

            InteractionLogger.logSessionEvidence(
                "GLASSES_CYCLE",
                "CYCLE_STAGE",
                "id=${cycle.id};mode=${cycle.mode};stage=TTS_DONE;duration_ms=${ttsSpeakMs ?: -1L};utterance_id=$utteranceId;error_code=${errorCode ?: 0}"
            )

            completeCycleLocked(
                cycle,
                if (errorCode == null) "TTS_DONE" else "TTS_ERROR_$errorCode",
                nowElapsed
            )
        }
    }

    private fun completeActiveCaptureCycle(reason: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return
            completeCycleLocked(cycle, reason, nowElapsed)
        }
    }

    private fun completeCycleLocked(
        cycle: CaptureCycleTiming,
        reason: String,
        completedElapsedMs: Long
    ) {
        if (activeCaptureCycle?.id != cycle.id) return

        val totalMs = (completedElapsedMs - cycle.startedElapsedMs).coerceAtLeast(0L)
        val commandMs = cycle.commandDispatchedElapsedMs?.let {
            (it - cycle.startedElapsedMs).coerceAtLeast(0L)
        }
        val captureMs = if (cycle.firstDataElapsedMs != null) {
            (cycle.firstDataElapsedMs!! - (cycle.commandDispatchedElapsedMs ?: cycle.startedElapsedMs))
                .coerceAtLeast(0L)
        } else {
            null
        }
        val transferMs = if (cycle.firstDataElapsedMs != null && cycle.transferCompletedElapsedMs != null) {
            (cycle.transferCompletedElapsedMs!! - cycle.firstDataElapsedMs!!).coerceAtLeast(0L)
        } else {
            null
        }
        val inferenceMs = if (cycle.inferenceStartedElapsedMs != null && cycle.inferenceCompletedElapsedMs != null) {
            (cycle.inferenceCompletedElapsedMs!! - cycle.inferenceStartedElapsedMs!!).coerceAtLeast(0L)
        } else {
            null
        }
        val ttsQueueMs = if (cycle.inferenceCompletedElapsedMs != null && cycle.ttsQueuedElapsedMs != null) {
            (cycle.ttsQueuedElapsedMs!! - cycle.inferenceCompletedElapsedMs!!).coerceAtLeast(0L)
        } else {
            null
        }
        val ttsSpeakMs = if (cycle.ttsStartedElapsedMs != null && cycle.ttsCompletedElapsedMs != null) {
            (cycle.ttsCompletedElapsedMs!! - cycle.ttsStartedElapsedMs!!).coerceAtLeast(0L)
        } else {
            null
        }

        InteractionLogger.logSessionEvidence(
            "GLASSES_CYCLE",
            "CYCLE_COMPLETE",
            "id=${cycle.id};mode=${cycle.mode};trigger=${cycle.trigger};reason=$reason;total_ms=$totalMs;" +
                "command_ms=${commandMs ?: -1L};capture_ms=${captureMs ?: -1L};transfer_ms=${transferMs ?: -1L};" +
                "inference_ms=${inferenceMs ?: -1L};tts_queue_ms=${ttsQueueMs ?: -1L};tts_speak_ms=${ttsSpeakMs ?: -1L}"
        )

        utteranceToCycleId.entries.removeAll { it.value == cycle.id }
        activeCaptureCycle = null

        if (burstReassemblyPinnedForPostProcess) {
            clearBurstReassemblyState(reason = "cycle_complete:$reason")
        }
    }

    fun setCaptureGateEnabled(enabled: Boolean, source: String = "APP") {
        var shouldStopSpeaker = false
        var shouldCompleteActiveCycle = false
        synchronized(captureGateLock) {
            if (captureGateEnabled == enabled) return

            captureGateEnabled = enabled
            captureGateEpoch += 1L

            if (enabled) {
                captureGateDisabledAtMs = null
                captureGateStaleFlushed = false
                captureGateFlushJob?.cancel()
                captureGateFlushJob = null
                clearReassemblyBuffersLocked("capture_enabled_reset:$source")
            } else {
                val disabledAtMs = System.currentTimeMillis()
                captureGateDisabledAtMs = disabledAtMs
                captureGateStaleFlushed = false
                shouldStopSpeaker = true
                shouldCompleteActiveCycle = true
                scheduleCaptureGateStaleFlushLocked(disabledAtMs)
            }
        }

        if (shouldStopSpeaker) {
            speaker.stop()
        }
        if (shouldCompleteActiveCycle) {
            completeActiveCaptureCycle("CAPTURE_GATE_DISABLED")
        }

        Log.i(TAG, "Capture gate ${if (enabled) "enabled" else "disabled"} (source=$source)")
    }

    /**
        * Immediately cancel and drain in-flight capture work.
        * Active coroutine work sees a new epoch and exits, and reassembly
        * buffers are cleared so stale chunks get dropped.
     */
    fun abortInFlightCapture(reason: String = "APP_INTERRUPT") {
        synchronized(captureGateLock) {
            captureGateEpoch += 1L
            captureGateFlushJob?.cancel()
            captureGateFlushJob = null
            clearReassemblyBuffersLocked("abort:$reason")
            captureGateStaleFlushed = false

            if (!captureGateEnabled) {
                val disabledAtMs = System.currentTimeMillis()
                captureGateDisabledAtMs = disabledAtMs
                scheduleCaptureGateStaleFlushLocked(disabledAtMs)
            } else {
                captureGateDisabledAtMs = null
            }
        }

        speaker.stop()
        Log.i(TAG, "Capture pipeline interrupted ($reason)")
    }

    fun isCaptureGateEnabled(): Boolean = captureGateEnabled

    fun isProcessingInFlight(): Boolean = processingInFlight

    fun isCapturePipelineBusy(): Boolean {
        if (processingInFlight) return true
        synchronized(captureCycleLock) {
            expireStaleActiveCycleLocked(SystemClock.elapsedRealtime())
            val cycle = activeCaptureCycle ?: return false
            return shouldBlockCycleSupersedeLocked(cycle)
        }
    }

    fun isCaptureActivityRecent(maxSilenceMs: Long = CAPTURE_ACTIVITY_RECENT_WINDOW_MS): Boolean {
        if (!captureGateEnabled || maxSilenceMs <= 0L) return false

        val lastSignalElapsedMs = maxOf(lastInferenceImageDataElapsedMs, lastCaptureCommandElapsedMs)
        if (lastSignalElapsedMs <= 0L) return false

        val silenceElapsedMs = (SystemClock.elapsedRealtime() - lastSignalElapsedMs).coerceAtLeast(0L)
        return silenceElapsedMs <= maxSilenceMs
    }

    private fun scheduleCaptureGateStaleFlushLocked(disabledAtMs: Long) {
        captureGateFlushJob?.cancel()
        captureGateFlushJob = scope.launch {
            delay(CAPTURE_DISABLE_STALE_FLUSH_MS)
            synchronized(captureGateLock) {
                if (!captureGateEnabled && captureGateDisabledAtMs == disabledAtMs && !captureGateStaleFlushed) {
                    clearReassemblyBuffersLocked("capture_disabled_stale_flush")
                    captureGateStaleFlushed = true
                    Log.i(TAG, "Capture gate stale flush applied after ${CAPTURE_DISABLE_STALE_FLUSH_MS}ms")
                }
            }
        }
    }

    private fun clearSingleReassemblyState() {
        photoBuffer.reset()
        isReceiving = false
        expectedChunkIndex = 0
        totalBytesReceived = 0
        droppedChunks = 0
    }

    private fun clearBurstReassemblyState(reason: String? = null) {
        burstFrameBuffers.clear()
        burstFrameTimestamps.clear()
        burstFrameCaptureTimeHints.clear()
        currentBurstSeq = -1
        burstExpectedChunkIndex = 0
        burstDroppedChunks = 0
        burstRequestStartedAtMs = 0L
        burstFirstFrameAtMs = null
        burstReassemblyPinnedForPostProcess = false
        if (reason != null) {
            Log.d(TAG, "Burst reassembly state cleared ($reason)")
        }
    }

    private fun clearInboundReassemblyAfterTransferAbort(reasonLabel: String) {
        clearSingleReassemblyState()
        clearBurstReassemblyState(reason = "transfer_abort:$reasonLabel")
        isBurstMode = false
    }

    private fun clearReassemblyBuffersLocked(reason: String) {
        clearSingleReassemblyState()
        clearBurstReassemblyState(reason = "capture_buffer_reset:$reason")
        isBurstMode = false

        Log.i(TAG, "Capture buffers cleared ($reason)")
    }

    private fun shouldAbortCaptureProcessing(gateEpochSnapshot: Long): Boolean {
        return !captureGateEnabled || captureGateEpoch != gateEpochSnapshot
    }

    private fun recycleBitmaps(bitmaps: List<Bitmap>) {
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun activeCaptureCycleMode(): String? {
        synchronized(captureCycleLock) {
            return activeCaptureCycle?.mode
        }
    }

    private fun handleSingleModeBurstMismatch(frameIndex: Int, dataSize: Int, reason: String) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedSinceLastMismatch =
            (nowElapsed - lastSingleModeBurstMismatchElapsedMs).coerceAtLeast(0L)
        if (elapsedSinceLastMismatch < SINGLE_MODE_BURST_MISMATCH_COOLDOWN_MS) {
            return
        }
        lastSingleModeBurstMismatchElapsedMs = nowElapsed

        val activeMode = activeCaptureCycleMode() ?: "NONE"
        val dropSnapshot = if (isBurstMode) burstDroppedChunks else droppedChunks

        Log.w(
            TAG,
            "Burst payload received during SINGLE cycle; forcing parser recovery (reason=$reason, frameIndex=$frameIndex, size=$dataSize)"
        )
        InteractionLogger.logSessionEvidence(
            "GLASSES_TRANSFER",
            "BURST_IN_SINGLE_MISMATCH",
            "reason=$reason;frame_index=$frameIndex;size=$dataSize;active_mode=$activeMode;burst_mode=${if (isBurstMode) 1 else 0};drop_count=$dropSnapshot"
        )

        markCycleTransferCompleted(
            stageLabel = "SINGLE_MODE_MISMATCH",
            frames = 0,
            dropped = dropSnapshot
        )
        clearSingleReassemblyState()
        clearBurstReassemblyState(reason = "single_mode_mismatch:$reason")
        isBurstMode = false

        if (activeMode == "SINGLE") {
            completeActiveCaptureCycle("BURST_STREAM_IN_SINGLE_MODE")
        }
    }

    private fun handleSingleModeBurstTakeover(data: ByteArray, frameIndex: Int) {
        Log.w(
            TAG,
            "Burst chunk-0 received during SINGLE cycle; preempting SINGLE cycle and switching parser to burst mode"
        )
        InteractionLogger.logSessionEvidence(
            "GLASSES_TRANSFER",
            "BURST_TAKEOVER_FROM_SINGLE",
            "frame_index=$frameIndex;size=${data.size};active_mode=SINGLE"
        )

        if (processingInFlight && !processingPreemptLatch) {
            processingPreemptLatch = true
            abortInFlightCapture(reason = "BURST_PREEMPT_SINGLE")
        }

        markCycleTransferCompleted(
            stageLabel = "SINGLE_PREEMPTED_BY_BURST",
            frames = 0,
            dropped = droppedChunks
        )
        completeActiveCaptureCycle("SINGLE_PREEMPTED_BY_BURST")
        prepareBurst()
        handleBurstData(data, frameIndex)
    }

    // ── Incoming BLE data entry point ────────────────────────────────────────

    /**
        * Route one incoming BLE notification chunk.
        * Called by [GlassesMessageHandler] on the BLE callback thread.
     */
    fun onBleData(data: ByteArray) {
        if (tryHandleCadenceHandoffFeedback(data)) return
        if (tryHandleTransferAbortMarker(data)) return
        if (data.size < 2) return
        val frameIndex = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val incomingProfile = incomingInferenceProfile(data, frameIndex)
        val burstEndMarker = isBurstEndMarker(data)
        val burstChunkZero = shouldAutoSwitchToBurst(data, frameIndex)
        val activeCycleMode = activeCaptureCycleMode()

        if (!captureGateEnabled) {
            val disabledAt = captureGateDisabledAtMs
            if (!captureGateStaleFlushed && disabledAt != null) {
                val disabledForMs = System.currentTimeMillis() - disabledAt
                if (disabledForMs >= CAPTURE_DISABLE_STALE_FLUSH_MS) {
                    synchronized(captureGateLock) {
                        if (!captureGateEnabled && !captureGateStaleFlushed) {
                            clearReassemblyBuffersLocked("capture_disabled_stale_flush_on_data")
                            captureGateStaleFlushed = true
                        }
                    }
                }
            }
            return
        }

        if (burstReassemblyPinnedForPostProcess) {
            Log.w(TAG, "Dropping inbound capture chunk while burst post-process is pinned")
            return
        }

        lastInferenceImageDataElapsedMs = SystemClock.elapsedRealtime()
        if (!detectorUpshiftPerInferenceEnabled) {
            scheduleDetectorIdleUpshiftCheck(incomingProfile)
        }

        if (shouldPreemptForIncomingCapture(frameIndex, data)) {
            processingPreemptLatch = true
            abortInFlightCapture(reason = "INCOMING_CAPTURE_PRIORITY")
        }

        markCycleDataReceived()

        if (activeCycleMode == "SINGLE") {
            when {
                isBurstMode -> {
                    handleSingleModeBurstMismatch(
                        frameIndex = frameIndex,
                        dataSize = data.size,
                        reason = "MODE_DESYNC"
                    )
                    return
                }
                burstEndMarker -> {
                    handleSingleModeBurstMismatch(
                        frameIndex = frameIndex,
                        dataSize = data.size,
                        reason = "BURST_END_MARKER"
                    )
                    return
                }
                burstChunkZero -> {
                    handleSingleModeBurstTakeover(data = data, frameIndex = frameIndex)
                    return
                }
            }
        }

        if (!isBurstMode) {
            if (burstEndMarker) {
                Log.w(TAG, "Ignoring burst-end marker while in single-photo mode")
                return
            }
            if (burstChunkZero) {
                Log.w(TAG, "Detected burst chunk while in single-photo mode; switching parser mode")
                prepareBurst()
                handleBurstData(data, frameIndex)
                return
            }
        }

        if (isBurstMode) handleBurstData(data, frameIndex)
        else handleSinglePhotoData(data, frameIndex)
    }

    private fun tryHandleTransferAbortMarker(data: ByteArray): Boolean {
        if (data.size < 2) return false
        if (data[0] != TRANSFER_ABORT_MARKER_LO || data[1] != TRANSFER_ABORT_MARKER_HI) return false

        val reasonCode = if (data.size >= 3) {
            data[2].toInt() and 0xFF
        } else {
            TRANSFER_ABORT_REASON_UNKNOWN
        }
        val reasonLabel = transferAbortReasonLabel(reasonCode)
        val frameCount = if (isBurstMode) burstFrameBuffers.size else 1
        val dropCount = if (isBurstMode) burstDroppedChunks else droppedChunks

        Log.w(TAG, "Received transfer-abort marker from firmware (reason=$reasonLabel)")
        InteractionLogger.logSessionEvidence(
            "GLASSES_TRANSFER",
            "TRANSFER_ABORT_MARKER",
            "reason=$reasonLabel;burst_mode=${if (isBurstMode) 1 else 0};frames=$frameCount;drop_count=$dropCount"
        )

        markCycleTransferCompleted(
            stageLabel = "TRANSFER_ABORT",
            frames = frameCount,
            dropped = dropCount
        )
        clearInboundReassemblyAfterTransferAbort(reasonLabel)
        completeActiveCaptureCycle("TRANSFER_ABORT_$reasonLabel")
        return true
    }

    private fun transferAbortReasonLabel(reasonCode: Int): String {
        return when (reasonCode) {
            TRANSFER_ABORT_REASON_CMD_SINGLE -> "CMD_SINGLE"
            TRANSFER_ABORT_REASON_CMD_BURST -> "CMD_BURST"
            TRANSFER_ABORT_REASON_CAPTURE_DISABLED -> "CAPTURE_DISABLED"
            TRANSFER_ABORT_REASON_BUTTON_PRIORITY -> "BUTTON_PRIORITY"
            TRANSFER_ABORT_REASON_RUNTIME_GUARD -> "RUNTIME_GUARD"
            else -> "UNKNOWN_$reasonCode"
        }
    }

    private fun isBurstEndMarker(data: ByteArray): Boolean {
        return data.size == 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()
    }

    private fun incomingInferenceProfile(data: ByteArray, frameIndex: Int): InferenceProfile {
        if (isBurstMode) {
            return InferenceProfile.BURST
        }
        if (isBurstEndMarker(data) || shouldAutoSwitchToBurst(data, frameIndex)) {
            return InferenceProfile.BURST
        }
        return InferenceProfile.SINGLE
    }

    private fun shouldAutoSwitchToBurst(data: ByteArray, frameIndex: Int): Boolean {
        if (frameIndex != 0) return false

        val hasSingleHeader = hasJpegHeaderAtOffset(data, 3)
        val hasLegacyBurstHeader = hasJpegHeaderAtOffset(data, 4)
        val hasTimestampedBurstHeader = hasJpegHeaderAtOffset(data, 8)

        return !hasSingleHeader && (hasLegacyBurstHeader || hasTimestampedBurstHeader)
    }

    private fun hasJpegHeaderAtOffset(data: ByteArray, offset: Int): Boolean {
        if (data.size < offset + 3) return false
        return data[offset] == 0xFF.toByte() &&
            data[offset + 1] == 0xD8.toByte() &&
            data[offset + 2] == 0xFF.toByte()
    }

    private fun shouldPreemptForIncomingCapture(frameIndex: Int, data: ByteArray): Boolean {
        if (frameIndex != 0) return false
        if (!processingInFlight || processingPreemptLatch) return false
        if (!isPriorityBurstCycleActive()) return false

        val hasLegacyBurstHeader = hasJpegHeaderAtOffset(data, 4)
        val hasTimestampedBurstHeader = hasJpegHeaderAtOffset(data, 8)
        return hasLegacyBurstHeader || hasTimestampedBurstHeader
    }

    private fun isPriorityBurstCycleActive(): Boolean {
        synchronized(captureCycleLock) {
            val cycle = activeCaptureCycle ?: return false
            if (cycle.mode != "BURST") return false
            return cycle.trigger == "INTERRUPT_RESTART"
        }
    }

    // ── Single-photo reassembly ──────────────────────────────────────────────

    private fun handleSinglePhotoData(data: ByteArray, frameIndex: Int) {
        // End marker for single-photo mode: exact [0xFF, 0xFF].
        if (frameIndex == 0xFFFF) {
            if (data.size != 2) {
                Log.w(TAG, "Ignoring non-single end marker in single-photo mode (size=${data.size})")
                return
            }
            Log.d(TAG, "Photo complete: $totalBytesReceived bytes, $droppedChunks dropped")
            markCycleTransferCompleted(stageLabel = "SINGLE_END", frames = 1, dropped = droppedChunks)
            val jpeg = photoBuffer.toByteArray()
            photoBuffer.reset()
            isReceiving = false
            processFrames(listOf(jpeg), listOf(System.currentTimeMillis()))
            return
        }

        if (frameIndex == 0) {
            if (data.size >= 6 && !hasJpegHeaderAtOffset(data, 3)) {
                Log.w(TAG, "Single-photo chunk-0 missing JPEG signature; dropping frame")
                photoBuffer.reset()
                isReceiving = false
                expectedChunkIndex = 0
                totalBytesReceived = 0
                droppedChunks = 0
                return
            }
            isReceiving = true
            photoBuffer.reset()
            expectedChunkIndex = 0
            totalBytesReceived = 0
            droppedChunks = 0
            // Chunk 0 payload starts after frameIndex(2) + orientation(1).
            if (data.size > 3) {
                photoBuffer.write(data, 3, data.size - 3)
                totalBytesReceived += data.size - 3
            }
            expectedChunkIndex = 1
        } else if (isReceiving) {
            if (frameIndex != expectedChunkIndex) {
                droppedChunks += frameIndex - expectedChunkIndex
                Log.w(TAG, "Dropped ${frameIndex - expectedChunkIndex} chunk(s)")
            }
            // Chunk N payload starts after frameIndex(2).
            if (data.size > 2) {
                photoBuffer.write(data, 2, data.size - 2)
                totalBytesReceived += data.size - 2
            }
            expectedChunkIndex = frameIndex + 1
        }
    }

    // ── Burst reassembly ──────────────────────────────────────────────────────

    private fun handleBurstData(data: ByteArray, frameIndex: Int) {
        // End-of-burst marker.
        if (data.size == 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            Log.d(TAG, "Burst complete: ${burstFrameBuffers.size} frames")
            processBurstFrames(System.currentTimeMillis())
            return
        }
        // Per-frame end marker.
        if (frameIndex == 0xFFFF && data.size == 3) {
            burstExpectedChunkIndex = 0
            return
        }
        if (data.size < 3) return

        val burstSeq = data[2].toInt() and 0xFF

        if (frameIndex == 0) {
            if (burstFrameBuffers.containsKey(burstSeq)) {
                InteractionLogger.logSessionEvidence(
                    "GLASSES_TRANSFER",
                    "BURST_FRAME_RESET",
                    "seq=$burstSeq;reason=CHUNK0_RESTART"
                )
            }
            currentBurstSeq = burstSeq
            val frameBuffer = ByteArrayOutputStream()
            burstFrameBuffers[burstSeq] = frameBuffer
            val now = System.currentTimeMillis()
            burstFrameTimestamps[burstSeq] = now
            burstFrameCaptureTimeHints.remove(burstSeq)
            BurstChunkHeaderPolicy.captureTimeMs(data)?.let { captureTime ->
                burstFrameCaptureTimeHints[burstSeq] = captureTime
            }
            if (burstFirstFrameAtMs == null) burstFirstFrameAtMs = now
            burstExpectedChunkIndex = 0

            val payloadOffset = BurstChunkHeaderPolicy.payloadOffset(data)
            if (data.size > payloadOffset) {
                frameBuffer.write(data, payloadOffset, data.size - payloadOffset)
                totalBytesReceived += data.size - payloadOffset
            }
            burstExpectedChunkIndex = 1
            return
        }

        val buf = burstFrameBuffers[burstSeq] ?: run {
            burstDroppedChunks += 1
            Log.w(TAG, "Dropping burst chunk without frame start (seq=$burstSeq, chunk=$frameIndex)")
            return
        }

        if (frameIndex != burstExpectedChunkIndex) {
            val missingChunks = frameIndex - burstExpectedChunkIndex
            if (missingChunks > 0) {
                burstDroppedChunks += missingChunks
                Log.w(TAG, "Dropped $missingChunks burst chunk(s) for seq=$burstSeq")
            } else {
                Log.w(
                    TAG,
                    "Out-of-order burst chunk for seq=$burstSeq (chunk=$frameIndex expected=$burstExpectedChunkIndex)"
                )
            }
        }
        // Payload starts after frameIndex(2) + burstSeq(1).
        if (data.size > 3) {
            buf.write(data, 3, data.size - 3)
            totalBytesReceived += data.size - 3
        }
        burstExpectedChunkIndex = frameIndex + 1
    }

    private fun processBurstFrames(burstCompletedAtMs: Long) {
        val sortedSeqs = run {
            val seqs = burstFrameBuffers.keys.toList()
            val allHaveCaptureHints = seqs.isNotEmpty() && seqs.all { burstFrameCaptureTimeHints[it] != null }
            if (allHaveCaptureHints) {
                seqs.sortedWith(
                    compareBy<Int> { burstFrameCaptureTimeHints[it] ?: Long.MAX_VALUE }
                        .thenBy { it }
                )
            } else {
                seqs.sorted()
            }
        }

        val allHaveCaptureHints = sortedSeqs.isNotEmpty() && sortedSeqs.all {
            burstFrameCaptureTimeHints[it] != null
        }

        val frameSamples = sortedSeqs.mapIndexedNotNull { index, seq ->
            val jpeg = burstFrameBuffers[seq]?.toByteArray() ?: return@mapIndexedNotNull null
            if (!validateJpeg(jpeg)) return@mapIndexedNotNull null

            val timestamp = if (allHaveCaptureHints) {
                burstFrameCaptureTimeHints[seq] ?: return@mapIndexedNotNull null
            } else {
                burstFrameTimestamps[seq] ?: (burstCompletedAtMs + index)
            }

            Pair(jpeg, timestamp)
        }

        val frames = frameSamples.map { it.first }
        val timestamps = frameSamples.map { it.second }

        val intervalsMs = timestamps.zipWithNext { a, b -> b - a }
        val requestStartedAt = burstRequestStartedAtMs
        val firstFrameAt = burstFirstFrameAtMs
        val stats = BurstTimingStats(
            requestStartedAtMs = requestStartedAt,
            firstFrameAtMs = firstFrameAt,
            burstCompletedAtMs = burstCompletedAtMs,
            framesInBurst = frames.size,
            droppedChunks = burstDroppedChunks,
            firstFrameLatencyMs = firstFrameAt?.let { first ->
                if (requestStartedAt > 0L) (first - requestStartedAt).coerceAtLeast(0L) else null
            },
            totalLatencyMs =
                if (requestStartedAt > 0L) (burstCompletedAtMs - requestStartedAt).coerceAtLeast(0L) else 0L,
            averageFrameIntervalMs =
                if (intervalsMs.isNotEmpty()) intervalsMs.average().roundToLong() else null,
            minFrameIntervalMs = intervalsMs.minOrNull(),
            maxFrameIntervalMs = intervalsMs.maxOrNull()
        )
        lastBurstTimingStats = stats
        val aggregate = updateBurstTimingAggregate(stats)
        broadcastBurstTimingUpdated(stats, aggregate)

        Log.i(
            TAG,
            "Burst timing stats: frames=${stats.framesInBurst}, firstLatency=${stats.firstFrameLatencyMs}, " +
                "totalLatency=${stats.totalLatencyMs}, avgInterval=${stats.averageFrameIntervalMs}, dropped=${stats.droppedChunks}, " +
                "rollingBursts=${aggregate.burstsInWindow}, rollingTotalAvg=${aggregate.averageTotalLatencyMs}, " +
                "rollingDropPerBurst=${String.format(Locale.US, "%.2f", aggregate.droppedChunksPerBurst)}, " +
                "rollingHealth=${aggregate.cadenceHealth}, rollingRecommendDelay=${aggregate.recommendedInterFrameDelayMs}"
        )

        markCycleTransferCompleted(
            stageLabel = "BURST_END",
            frames = frames.size,
            dropped = burstDroppedChunks
        )

        isBurstMode = false
        burstReassemblyPinnedForPostProcess = frames.isNotEmpty()
        if (frames.isNotEmpty()) {
            processFrames(frames, timestamps)
        } else {
            clearBurstReassemblyState(reason = "burst_complete_empty")
        }
    }

    private fun updateBurstTimingAggregate(stats: BurstTimingStats): BurstTimingAggregateStats {
        synchronized(burstTimingAggregateLock) {
            recentBurstTimingStats.addLast(stats)
            while (recentBurstTimingStats.size > BURST_TIMING_ROLLING_WINDOW) {
                recentBurstTimingStats.removeFirst()
            }

            val window = recentBurstTimingStats.toList()
            val burstsInWindow = window.size
            val framesInWindow = window.sumOf { it.framesInBurst }
            val droppedInWindow = window.sumOf { it.droppedChunks }
            val firstFrameLatencyValues = window.mapNotNull { it.firstFrameLatencyMs }
            val totalLatencyValues = window.map { it.totalLatencyMs }
            val averageIntervalValues = window.mapNotNull { it.averageFrameIntervalMs }
            val minInterval = window.mapNotNull { it.minFrameIntervalMs }.minOrNull()
            val maxInterval = window.mapNotNull { it.maxFrameIntervalMs }.maxOrNull()
            val averageTotalLatencyMs =
                if (totalLatencyValues.isNotEmpty()) totalLatencyValues.average().roundToLong() else 0L
            val averageFrameIntervalMs = averageOrNull(averageIntervalValues)
            val intervalSpreadMs = if (minInterval != null && maxInterval != null) {
                (maxInterval - minInterval).coerceAtLeast(0L)
            } else {
                null
            }
            val droppedChunksPerBurst = if (burstsInWindow > 0) {
                droppedInWindow.toDouble() / burstsInWindow.toDouble()
            } else {
                0.0
            }
            val droppedChunksPerFrame = if (framesInWindow > 0) {
                droppedInWindow.toDouble() / framesInWindow.toDouble()
            } else {
                0.0
            }
            val cadenceHealth = classifyCadenceHealth(
                droppedChunksPerFrame = droppedChunksPerFrame,
                intervalSpreadMs = intervalSpreadMs,
                averageTotalLatencyMs = averageTotalLatencyMs
            )
            val recommendedInterFrameDelayMs = recommendInterFrameDelayMs(
                currentAverageIntervalMs = averageFrameIntervalMs,
                cadenceHealth = cadenceHealth
            )

            val aggregate = BurstTimingAggregateStats(
                burstsInWindow = burstsInWindow,
                framesInWindow = framesInWindow,
                droppedChunksInWindow = droppedInWindow,
                averageFirstFrameLatencyMs = averageOrNull(firstFrameLatencyValues),
                averageTotalLatencyMs = averageTotalLatencyMs,
                averageFrameIntervalMs = averageFrameIntervalMs,
                minFrameIntervalMs = minInterval,
                maxFrameIntervalMs = maxInterval,
                intervalSpreadMs = intervalSpreadMs,
                cadenceHealth = cadenceHealth,
                recommendedInterFrameDelayMs = recommendedInterFrameDelayMs,
                droppedChunksPerBurst = droppedChunksPerBurst,
                droppedChunksPerFrame = droppedChunksPerFrame
            )

            lastBurstTimingAggregateStats = aggregate
            return aggregate
        }
    }

    private fun averageOrNull(values: List<Long>): Long? {
        return if (values.isNotEmpty()) values.average().roundToLong() else null
    }

    private fun classifyCadenceHealth(
        droppedChunksPerFrame: Double,
        intervalSpreadMs: Long?,
        averageTotalLatencyMs: Long
    ): BurstCadenceHealth {
        if (droppedChunksPerFrame >= CADENCE_DEGRADED_DROP_PER_FRAME) {
            return BurstCadenceHealth.DEGRADED
        }
        if (intervalSpreadMs != null && intervalSpreadMs >= CADENCE_DEGRADED_SPREAD_MS) {
            return BurstCadenceHealth.DEGRADED
        }
        if (averageTotalLatencyMs >= CADENCE_DEGRADED_TOTAL_LATENCY_MS) {
            return BurstCadenceHealth.DEGRADED
        }

        if (droppedChunksPerFrame > 0.0) {
            return BurstCadenceHealth.MODERATE
        }
        if (intervalSpreadMs != null && intervalSpreadMs >= CADENCE_MODERATE_SPREAD_MS) {
            return BurstCadenceHealth.MODERATE
        }
        if (averageTotalLatencyMs >= CADENCE_MODERATE_TOTAL_LATENCY_MS) {
            return BurstCadenceHealth.MODERATE
        }

        return BurstCadenceHealth.STABLE
    }

    private fun recommendInterFrameDelayMs(
        currentAverageIntervalMs: Long?,
        cadenceHealth: BurstCadenceHealth
    ): Long? {
        val current = currentAverageIntervalMs ?: return null
        val candidate = when (cadenceHealth) {
            BurstCadenceHealth.STABLE -> current
            BurstCadenceHealth.MODERATE -> current + RECOMMEND_DELAY_MODERATE_BUMP_MS
            BurstCadenceHealth.DEGRADED -> current + RECOMMEND_DELAY_DEGRADED_BUMP_MS
        }
        return candidate.coerceIn(RECOMMEND_DELAY_MIN_MS, RECOMMEND_DELAY_MAX_MS)
    }

    private fun tryHandleCadenceHandoffFeedback(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (data[0] != CADENCE_ACK_MARKER_LO || data[1] != CADENCE_ACK_MARKER_HI) return false

        val version = data[2].toInt() and 0xFF
        val ackCode = data[3].toInt() and 0xFF
        val appliedDelayMs = if (data.size >= 6) {
            ((data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)).toLong()
        } else {
            null
        }

        val feedback = BurstCadenceHandoffFeedback(
            receivedAtMs = System.currentTimeMillis(),
            protocolVersion = version,
            ackCode = ackCode,
            ackLabel = cadenceAckLabel(ackCode),
            appliedInterFrameDelayMs = appliedDelayMs
        )
        lastBurstCadenceHandoffFeedback = feedback
        broadcastBurstCadenceHandoffUpdated(feedback)

        val versionNote = if (version == CADENCE_ACK_VERSION) "" else " unexpectedVersion=$version"
        Log.i(
            TAG,
            "Cadence handoff ack: status=${feedback.ackLabel}, appliedDelay=${feedback.appliedInterFrameDelayMs}$versionNote"
        )
        return true
    }

    private fun cadenceAckLabel(code: Int): String {
        return when (code) {
            CADENCE_ACK_APPLIED -> "APPLIED"
            CADENCE_ACK_CLAMPED -> "CLAMPED"
            CADENCE_ACK_REJECTED -> "REJECTED"
            else -> "UNKNOWN_$code"
        }
    }

    private fun broadcastBurstCadenceHandoffUpdated(feedback: BurstCadenceHandoffFeedback) {
        val intent = Intent(ACTION_BURST_CADENCE_HANDOFF_UPDATED).apply {
            setPackage(context.packageName)
            putExtra("receivedAtMs", feedback.receivedAtMs)
            putExtra("protocolVersion", feedback.protocolVersion)
            putExtra("ackCode", feedback.ackCode)
            putExtra("ackLabel", feedback.ackLabel)
            putExtra("appliedInterFrameDelayMs", feedback.appliedInterFrameDelayMs ?: -1L)
        }
        context.sendBroadcast(intent)
    }

    private fun broadcastBurstTimingUpdated(
        stats: BurstTimingStats,
        aggregate: BurstTimingAggregateStats?
    ) {
        val intent = Intent(ACTION_BURST_TIMING_UPDATED).apply {
            setPackage(context.packageName)
            putExtra("requestStartedAtMs", stats.requestStartedAtMs)
            putExtra("firstFrameAtMs", stats.firstFrameAtMs ?: -1L)
            putExtra("burstCompletedAtMs", stats.burstCompletedAtMs)
            putExtra("framesInBurst", stats.framesInBurst)
            putExtra("droppedChunks", stats.droppedChunks)
            putExtra("firstFrameLatencyMs", stats.firstFrameLatencyMs ?: -1L)
            putExtra("totalLatencyMs", stats.totalLatencyMs)
            putExtra("averageFrameIntervalMs", stats.averageFrameIntervalMs ?: -1L)
            putExtra("minFrameIntervalMs", stats.minFrameIntervalMs ?: -1L)
            putExtra("maxFrameIntervalMs", stats.maxFrameIntervalMs ?: -1L)
            putExtra("rollingBurstsInWindow", aggregate?.burstsInWindow ?: 0)
            putExtra("rollingFramesInWindow", aggregate?.framesInWindow ?: 0)
            putExtra("rollingDroppedChunksInWindow", aggregate?.droppedChunksInWindow ?: 0)
            putExtra("rollingAverageFirstFrameLatencyMs", aggregate?.averageFirstFrameLatencyMs ?: -1L)
            putExtra("rollingAverageTotalLatencyMs", aggregate?.averageTotalLatencyMs ?: -1L)
            putExtra("rollingAverageFrameIntervalMs", aggregate?.averageFrameIntervalMs ?: -1L)
            putExtra("rollingMinFrameIntervalMs", aggregate?.minFrameIntervalMs ?: -1L)
            putExtra("rollingMaxFrameIntervalMs", aggregate?.maxFrameIntervalMs ?: -1L)
            putExtra("rollingIntervalSpreadMs", aggregate?.intervalSpreadMs ?: -1L)
            putExtra("rollingCadenceHealth", aggregate?.cadenceHealth?.name ?: BurstCadenceHealth.STABLE.name)
            putExtra("rollingRecommendedInterFrameDelayMs", aggregate?.recommendedInterFrameDelayMs ?: -1L)
            putExtra("rollingDroppedChunksPerBurst", aggregate?.droppedChunksPerBurst ?: 0.0)
            putExtra("rollingDroppedChunksPerFrame", aggregate?.droppedChunksPerFrame ?: 0.0)
        }
        context.sendBroadcast(intent)
    }

    // ── Detection/inference pipeline ─────────────────────────────────────────

    private fun processFrames(frames: List<ByteArray>, timestamps: List<Long>) {
        scope.launch {
            processingInFlight = true
            var inferenceDetector: ObjectDetector? = null
            var didRunDetection = false
            var inferenceProfile = InferenceProfile.SINGLE
            try {
                data class DecodedFrame(val bitmap: Bitmap, val timestampMs: Long)

                val gateEpochSnapshot = captureGateEpoch
                if (shouldAbortCaptureProcessing(gateEpochSnapshot)) {
                    Log.d(TAG, "Dropping frame batch while capture gate is disabled")
                    completeActiveCaptureCycle("CAPTURE_GATE_ABORT_PREPROCESS")
                    return@launch
                }

                markInferenceStartedIfNeeded()

                val fallbackTimestamp = timestamps.lastOrNull() ?: System.currentTimeMillis()
                val validFrameSamples = frames.mapIndexedNotNull { idx, jpeg ->
                    if (!validateJpeg(jpeg)) {
                        null
                    } else {
                        val ts = timestamps.getOrElse(idx) { fallbackTimestamp + idx.toLong() }
                        Pair(jpeg, ts)
                    }
                }

                if (validFrameSamples.isEmpty()) {
                    Log.e(TAG, "No valid JPEG frames")
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("NO_VALID_JPEG")
                    return@launch
                }

                val validFrames = validFrameSamples.map { it.first }
                val validTimestamps = validFrameSamples.map { it.second }

                if (shouldAbortCaptureProcessing(gateEpochSnapshot)) {
                    Log.d(TAG, "Aborting frame batch before persistence due to capture gate state")
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("CAPTURE_GATE_ABORT_PRE_PERSIST")
                    return@launch
                }

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

                // Save raw frames first.
                validFrames.forEachIndexed { i, jpeg ->
                    saveJpeg(jpeg, "ESP32_${ts}_frame$i.jpg")
                }

                val detector = objectDetector
                if (detector == null) {
                    Log.w(TAG, "ObjectDetector not ready — skipping inference")
                    // Detector is not ready yet, but still persist and broadcast save.
                    imageStorage.saveImage(validFrames.last())?.let { uri ->
                        context.sendBroadcast(android.content.Intent("GLASSES_IMAGE_SAVED").apply {
                            putExtra("uri", uri.toString())
                        })
                    }
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("DETECTOR_NOT_READY")
                    return@launch
                }
                inferenceDetector = detector

                val decodedFrames = validFrames.mapIndexedNotNull { idx, jpeg ->
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bitmap == null) {
                        Log.w(TAG, "Dropping undecodable frame at index=$idx")
                        null
                    } else {
                        DecodedFrame(bitmap = bitmap, timestampMs = validTimestamps[idx])
                    }
                }
                if (decodedFrames.isEmpty()) {
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("DECODE_FAILED")
                    return@launch
                }

                // Keep only frames with matching dimensions (same assumption as MainActivity).
                val refW = decodedFrames.first().bitmap.width
                val refH = decodedFrames.first().bitmap.height
                val consistentFrames = decodedFrames.filter { frame ->
                    val bmp = frame.bitmap
                    if (bmp.width != refW || bmp.height != refH) {
                        Log.w(TAG, "Dropping frame with mismatched dimensions ${bmp.width}x${bmp.height}")
                        bmp.recycle()
                        false
                    } else true
                }
                if (consistentFrames.isEmpty()) {
                    Log.w(TAG, "No dimension-consistent frames available for motion tracking")
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("FRAME_DIMENSION_MISMATCH")
                    return@launch
                }

                val consistentBitmaps = consistentFrames.map { it.bitmap }
                val consistentTimestamps = consistentFrames.map { it.timestampMs }

                if (shouldAbortCaptureProcessing(gateEpochSnapshot)) {
                    recycleBitmaps(consistentBitmaps)
                    Log.d(TAG, "Aborting decoded frame batch due to capture gate state")
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("CAPTURE_GATE_ABORT_POST_DECODE")
                    return@launch
                }

                inferenceProfile = profileForFrameCount(consistentBitmaps.size)
                lastInferenceProfile = inferenceProfile

                // Run YOLO on each frame.
                val detectionStartedAtMs = SystemClock.elapsedRealtime()
                val frameDetections = consistentBitmaps.map { detector.detect(it) }
                val detectionLatencyMs =
                    (SystemClock.elapsedRealtime() - detectionStartedAtMs).coerceAtLeast(0L)
                didRunDetection = true

                maybeDownshiftDetectorModelForLatency(
                    detector = detector,
                    frameCount = consistentBitmaps.size,
                    detectionLatencyMs = detectionLatencyMs,
                    profile = inferenceProfile
                )

                if (shouldAbortCaptureProcessing(gateEpochSnapshot)) {
                    recycleBitmaps(consistentBitmaps)
                    Log.d(TAG, "Aborting post-detection work due to capture gate state")
                    markInferenceCompletedIfNeeded()
                    completeActiveCaptureCycle("CAPTURE_GATE_ABORT_POST_DETECTION")
                    return@launch
                }

                // Estimate camera ego-motion from sparse background features
                // so relative object motion is more stable.
                val sparseEstimate = if (SparseBackgroundTracker.isNativeAvailable()) {
                    runCatching {
                        SparseBackgroundTracker.estimateCameraVelocity(
                            bitmaps = consistentBitmaps,
                            frameDetections = frameDetections,
                            frameTimestamps = consistentTimestamps
                        )
                    }.getOrElse { error ->
                        Log.w(TAG, "Sparse background ego-motion estimate failed: ${error.message}")
                        null
                    }
                } else {
                    null
                }

                if (sparseEstimate != null) {
                    Log.d(
                        TAG,
                        "Using sparse ego-motion estimate vx=${sparseEstimate.velocity.x}, " +
                            "vy=${sparseEstimate.velocity.y}, conf=${sparseEstimate.confidence}, " +
                            "pairs=${sparseEstimate.usedPairs}"
                    )
                }

                // Motion tracking stage.
                val tracked = motionTracker?.analyzeMotion(
                    frameDetections,
                    consistentTimestamps,
                    externalCameraVelocity = sparseEstimate?.velocity,
                    externalCameraConfidence = sparseEstimate?.confidence ?: 0f
                )
                    ?: frameDetections.lastOrNull()?.map { det ->
                        MotionTracker.TrackedDetection(
                            label          = det.label,
                            confidence     = det.confidence,
                            boundingBox    = det.boundingBox,
                            classIndex     = det.classIndex,
                            motionState    = MotionTracker.MotionState.UNKNOWN,
                            motionMagnitude = 0f
                        )
                    }.orEmpty()

                // Distance estimation stage.
                val distances = mutableMapOf<Int, DistanceEstimator.DistanceResult>()
                distanceEstimator?.let { est ->
                    est.frameWidth  = refW.toFloat()
                    est.frameHeight = refH.toFloat()
                    tracked.forEachIndexed { i, det ->
                        distances[i] = est.estimateDistance(det.label, det.boundingBox, det.trackId)
                    }
                }

                // Fuse distance info into threat levels.
                val fused = if (distances.isNotEmpty())
                    motionTracker?.fuseDistance(tracked, distances) ?: tracked
                else tracked

                // Annotate and save.
                imageAnnotator?.let { ann ->
                    if (fused.isNotEmpty()) {
                        val annotated = ann.annotate(consistentBitmaps.last(), fused, distances)
                        saveAnnotatedBitmap(annotated, "ESP32_${ts}_annotated.jpg")
                        annotated.recycle()
                    }
                }

                // Save to app gallery through ImageStorageManager.
                val retentionHint = buildRetentionHint(fused, distances)
                imageStorage.saveImage(consistentBitmaps.last().let {
                    val out = ByteArrayOutputStream()
                    it.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }, retentionHint)?.let { uri ->
                    context.sendBroadcast(android.content.Intent("GLASSES_IMAGE_SAVED").apply {
                        putExtra("uri", uri.toString())
                    })
                    ObjectHistory.addDetection("[Glasses] Image saved")
                }

                // Convert to DetectionSpeaker.Detection and speak.
                val speechDetections = fused.mapIndexed { i, det ->
                    val dist = distances[i]
                    DetectionSpeaker.Detection(
                        label            = det.label,
                        motionState      = mapMotionState(det.motionState),
                        distanceFeet     = det.distanceFeet ?: dist?.distanceFeet,
                        distanceCategory = dist?.distanceCategory ?: "nearby",
                        threatLevel      = mapThreatLevel(det.threatLevel)
                    )
                }

                withContext(Dispatchers.Main) {
                    if (shouldAbortCaptureProcessing(gateEpochSnapshot)) {
                        speaker.stop()
                        Log.d(TAG, "Skipping speech/broadcast due to capture gate state")
                        markInferenceCompletedIfNeeded()
                        completeActiveCaptureCycle("CAPTURE_GATE_ABORT_PRE_TTS")
                    } else if (AssistiveRuntimeSettings.isAssistiveSpeechAllowed(context)) {
                        markInferenceCompletedIfNeeded()
                        val utteranceId = speaker.speak(
                            detections = speechDetections,
                            interruptInFlight = shouldInterruptSpeechForThreat(speechDetections)
                        )
                        if (utteranceId != null) {
                            markTtsQueued(utteranceId)
                        } else {
                            completeActiveCaptureCycle("TTS_NOT_QUEUED")
                        }
                    } else {
                        Log.d(TAG, "Assistive speech muted by runtime mode or audio setting")
                        markInferenceCompletedIfNeeded()
                        completeActiveCaptureCycle("SPEECH_SKIPPED")
                    }
                    // Broadcast a summary so the UI log can show it.
                    if (!shouldAbortCaptureProcessing(gateEpochSnapshot)) {
                        val summary = buildDetectionSummary(fused, distances)
                        context.sendBroadcast(android.content.Intent("GLASSES_DETECTION_SUMMARY").apply {
                            putExtra("summary", summary)
                        })
                    }
                }

                recycleBitmaps(consistentBitmaps)

            } catch (e: Exception) {
                Log.e(TAG, "Detection pipeline error: ${e.message}", e)
                markInferenceCompletedIfNeeded()
                completeActiveCaptureCycle("PIPELINE_ERROR")
            } finally {
                if (didRunDetection && inferenceDetector != null) {
                    maybeReselectHighestModelAfterInference(inferenceDetector, inferenceProfile)
                }
                processingInFlight = false
                processingPreemptLatch = false
            }
        }
    }

    // ── Enum mappers: MotionTracker -> DetectionSpeaker ─────────────────────

    private fun mapMotionState(ms: MotionTracker.MotionState) = when (ms) {
        MotionTracker.MotionState.APPROACHING     -> DetectionSpeaker.MotionState.APPROACHING
        MotionTracker.MotionState.MOVING_AWAY     -> DetectionSpeaker.MotionState.MOVING_AWAY
        MotionTracker.MotionState.CROSSING_LEFT   -> DetectionSpeaker.MotionState.CROSSING_LEFT
        MotionTracker.MotionState.CROSSING_RIGHT  -> DetectionSpeaker.MotionState.CROSSING_RIGHT
        MotionTracker.MotionState.STATIONARY      -> DetectionSpeaker.MotionState.STATIONARY
        else                                       -> DetectionSpeaker.MotionState.UNKNOWN
    }

    private fun mapThreatLevel(tl: MotionTracker.ThreatLevel) = when (tl) {
        MotionTracker.ThreatLevel.CRITICAL -> DetectionSpeaker.ThreatLevel.CRITICAL
        MotionTracker.ThreatLevel.HIGH     -> DetectionSpeaker.ThreatLevel.HIGH
        MotionTracker.ThreatLevel.MODERATE -> DetectionSpeaker.ThreatLevel.MODERATE
        MotionTracker.ThreatLevel.LOW      -> DetectionSpeaker.ThreatLevel.LOW
        else                               -> DetectionSpeaker.ThreatLevel.UNKNOWN
    }

    private fun shouldInterruptSpeechForThreat(
        detections: List<DetectionSpeaker.Detection>
    ): Boolean {
        return detections.any {
            (it.threatLevel == DetectionSpeaker.ThreatLevel.CRITICAL ||
                it.threatLevel == DetectionSpeaker.ThreatLevel.HIGH) &&
                it.motionState != DetectionSpeaker.MotionState.UNKNOWN &&
                it.motionState != DetectionSpeaker.MotionState.STATIONARY
        }
    }

    private fun buildRetentionHint(
        tracked: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): ImageStorageManager.RetentionHint {
        val criticalThreatCount = tracked.count {
            it.threatLevel == MotionTracker.ThreatLevel.CRITICAL
        }
        val highThreatCount = tracked.count {
            it.threatLevel == MotionTracker.ThreatLevel.HIGH
        }
        val approachingCount = tracked.count {
            it.motionState == MotionTracker.MotionState.APPROACHING
        }
        val nearestDistanceFeet = tracked.mapIndexedNotNull { index, det ->
            det.distanceFeet ?: distances[index]?.distanceFeet
        }.minOrNull()

        return ImageStorageManager.RetentionHint(
            fromInference = true,
            detectionCount = tracked.size,
            criticalThreatCount = criticalThreatCount,
            highThreatCount = highThreatCount,
            approachingCount = approachingCount,
            nearestDistanceFeet = nearestDistanceFeet
        )
    }

    private fun profileForFrameCount(frameCount: Int): InferenceProfile {
        return if (frameCount > 1) {
            InferenceProfile.BURST
        } else {
            InferenceProfile.SINGLE
        }
    }

    private fun downshiftPathForProfile(profile: InferenceProfile): List<String> {
        return when (profile) {
            InferenceProfile.BURST -> DETECTOR_MODEL_BURST_DOWNSHIFT_PATH
            InferenceProfile.SINGLE -> DETECTOR_MODEL_SINGLE_DOWNSHIFT_PATH
        }
    }

    private fun preferredAssetForProfile(profile: InferenceProfile): String {
        return downshiftPathForProfile(profile).firstOrNull() ?: DETECTOR_MODEL_SINGLE_TOP
    }

    private fun ensurePreferredModelForProfile(
        detector: ObjectDetector,
        profile: InferenceProfile,
        trigger: String
    ) {
        if (!detectorModelAutoSwapEnabled) return

        val preferredAsset = preferredAssetForProfile(profile)

        synchronized(detectorModelSwapLock) {
            val currentAsset = detector.getActiveModelAsset()
            if (currentAsset == preferredAsset) {
                return
            }

            val swapStartedAtMs = SystemClock.elapsedRealtime()
            val activeBackend = detector.setConfig(
                ObjectDetector.Config(
                    backend = detectorBackendPreference,
                    onnxModelAsset = preferredAsset
                )
            )
            val swapDurationMs =
                (SystemClock.elapsedRealtime() - swapStartedAtMs).coerceAtLeast(0L)
            val appliedAsset = detector.getActiveModelAsset()

            if (appliedAsset == currentAsset) {
                return
            }

            InteractionLogger.logSessionEvidence(
                "GLASSES_MODEL_SWAP",
                "PROFILE_ALIGN",
                "profile=${profile.name};trigger=$trigger;from=$currentAsset;requested=$preferredAsset;applied=$appliedAsset;swap_ms=$swapDurationMs;backend=${activeBackend.name}"
            )
            Log.i(
                TAG,
                "Detector profile align applied ($trigger/${profile.name}): $currentAsset -> $appliedAsset (swap=${swapDurationMs}ms)"
            )
        }
    }

    private fun maybeDownshiftDetectorModelForLatency(
        detector: ObjectDetector,
        frameCount: Int,
        detectionLatencyMs: Long,
        profile: InferenceProfile
    ) {
        if (!detectorModelAutoSwapEnabled) return
        if (detectionLatencyMs < DETECTOR_BURST_DOWNSHIFT_THRESHOLD_MS) return

        val downshiftPath = downshiftPathForProfile(profile)

        synchronized(detectorModelSwapLock) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastDetectorModelSwapAtMs < DETECTOR_MODEL_SWAP_COOLDOWN_MS) {
                return
            }

            val currentAsset = detector.getActiveModelAsset()
            val currentIndex = downshiftPath.indexOf(currentAsset)
            if (currentIndex < 0 || currentIndex >= downshiftPath.lastIndex) {
                return
            }

            val requestedAsset = downshiftPath[currentIndex + 1]
            val swapStartedAtMs = SystemClock.elapsedRealtime()
            val activeBackend = detector.setConfig(
                ObjectDetector.Config(
                    backend = detectorBackendPreference,
                    onnxModelAsset = requestedAsset
                )
            )
            val swapDurationMs =
                (SystemClock.elapsedRealtime() - swapStartedAtMs).coerceAtLeast(0L)

            val appliedAsset = detector.getActiveModelAsset()
            if (appliedAsset == currentAsset) {
                return
            }

            lastDetectorModelSwapAtMs = nowMs
            InteractionLogger.logSessionEvidence(
                "GLASSES_MODEL_SWAP",
                "AUTO_DOWNSHIFT",
                "profile=${profile.name};from=$currentAsset;requested=$requestedAsset;applied=$appliedAsset;latency_ms=$detectionLatencyMs;swap_ms=$swapDurationMs;frames=$frameCount;backend=${activeBackend.name}"
            )
            Log.i(
                TAG,
                "Detector auto-downshift applied (${profile.name}): $currentAsset -> $appliedAsset (latency=${detectionLatencyMs}ms, swap=${swapDurationMs}ms, frames=$frameCount)"
            )
        }
    }

    private data class DetectorModelSwapResult(
        val fromAsset: String,
        val toAsset: String,
        val swapDurationMs: Long,
        val backend: ObjectDetector.Backend
    )

    private fun maybeReselectHighestModelAfterInference(
        detector: ObjectDetector,
        profile: InferenceProfile
    ) {
        if (!detectorModelAutoSwapEnabled) return

        if (detectorUpshiftPerInferenceEnabled) {
            val result = tryUpshiftDetectorModel(
                detector = detector,
                trigger = "POST_INFERENCE",
                profile = profile
            )

            if (result != null && result.swapDurationMs > DETECTOR_UPSHIFT_SIGNIFICANT_DELAY_MS) {
                detectorUpshiftPerInferenceEnabled = false
                InteractionLogger.logSessionEvidence(
                    "GLASSES_MODEL_SWAP",
                    "AUTO_UPSHIFT_POLICY_FALLBACK",
                    "swap_ms=${result.swapDurationMs};threshold_ms=$DETECTOR_UPSHIFT_SIGNIFICANT_DELAY_MS;from=${result.fromAsset};to=${result.toAsset}"
                )
                Log.w(
                    TAG,
                    "Detector upshift swap took ${result.swapDurationMs}ms (> ${DETECTOR_UPSHIFT_SIGNIFICANT_DELAY_MS}ms); switching to idle-upshift fallback"
                )
                scheduleDetectorIdleUpshiftCheck(profile)
            }
            return
        }

        scheduleDetectorIdleUpshiftCheck(profile)
    }

    private fun scheduleDetectorIdleUpshiftCheck(
        preferredProfile: InferenceProfile = lastInferenceProfile
    ) {
        if (!detectorModelAutoSwapEnabled) return
        if (detectorUpshiftPerInferenceEnabled) return
        if (lastInferenceImageDataElapsedMs <= 0L) return

        detectorIdleUpshiftJob?.cancel()
        val idleProfile = preferredProfile
        detectorIdleUpshiftJob = scope.launch {
            while (true) {
                val idleForMs =
                    (SystemClock.elapsedRealtime() - lastInferenceImageDataElapsedMs).coerceAtLeast(0L)
                val remainingMs = DETECTOR_IDLE_UPSHIFT_DELAY_MS - idleForMs
                if (remainingMs > 0L) {
                    delay(remainingMs)
                    continue
                }
                if (processingInFlight) {
                    delay(DETECTOR_IDLE_UPSHIFT_POLL_MS)
                    continue
                }
                break
            }

            val detector = objectDetector ?: return@launch
            val result = tryUpshiftDetectorModel(
                detector = detector,
                trigger = "IDLE_NO_IMAGE",
                profile = idleProfile
            )

            if (result != null && result.swapDurationMs <= DETECTOR_UPSHIFT_SIGNIFICANT_DELAY_MS) {
                detectorUpshiftPerInferenceEnabled = true
                InteractionLogger.logSessionEvidence(
                    "GLASSES_MODEL_SWAP",
                    "AUTO_UPSHIFT_POLICY_RESTORED",
                    "swap_ms=${result.swapDurationMs};threshold_ms=$DETECTOR_UPSHIFT_SIGNIFICANT_DELAY_MS;from=${result.fromAsset};to=${result.toAsset}"
                )
                Log.i(
                    TAG,
                    "Detector upshift policy restored to per-inference mode (idle swap=${result.swapDurationMs}ms)"
                )
            }
        }
    }

    private fun tryUpshiftDetectorModel(
        detector: ObjectDetector,
        trigger: String,
        profile: InferenceProfile
    ): DetectorModelSwapResult? {
        val preferredAsset = preferredAssetForProfile(profile)

        synchronized(detectorModelSwapLock) {
            val currentAsset = detector.getActiveModelAsset()
            if (currentAsset == preferredAsset) {
                return null
            }

            val swapStartedAtMs = SystemClock.elapsedRealtime()
            val activeBackend = detector.setConfig(
                ObjectDetector.Config(
                    backend = detectorBackendPreference,
                    onnxModelAsset = preferredAsset
                )
            )
            val swapDurationMs =
                (SystemClock.elapsedRealtime() - swapStartedAtMs).coerceAtLeast(0L)
            val appliedAsset = detector.getActiveModelAsset()

            if (appliedAsset == currentAsset) {
                Log.w(
                    TAG,
                    "Detector upshift requested but model did not change (trigger=$trigger, profile=${profile.name}, current=$currentAsset, requested=$preferredAsset)"
                )
                return null
            }

            lastDetectorModelSwapAtMs = System.currentTimeMillis()
            InteractionLogger.logSessionEvidence(
                "GLASSES_MODEL_SWAP",
                "AUTO_UPSHIFT",
                "trigger=$trigger;profile=${profile.name};from=$currentAsset;requested=$preferredAsset;applied=$appliedAsset;swap_ms=$swapDurationMs;backend=${activeBackend.name}"
            )
            Log.i(
                TAG,
                "Detector auto-upshift applied ($trigger/${profile.name}): $currentAsset -> $appliedAsset (swap=${swapDurationMs}ms)"
            )

            return DetectorModelSwapResult(
                fromAsset = currentAsset,
                toAsset = appliedAsset,
                swapDurationMs = swapDurationMs,
                backend = activeBackend
            )
        }
    }

    // ── JPEG validation (same assumptions as glasses MainActivity) ───────────

    private fun validateJpeg(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte() || data[2] != 0xFF.toByte()) {
            Log.e(TAG, "Invalid JPEG header: ${data.take(4).joinToString { "%02X".format(it) }}")
            return false
        }
        // Warn if FFD9 end marker is missing (usually means truncated transfer).
        val last = data.size - 1
        if (data[last - 1] != 0xFF.toByte() || data[last] != 0xD9.toByte()) {
            Log.w(TAG, "JPEG missing FFD9 trailer — frame may be truncated (${data.size} bytes)")
        }
        return true
    }

    // ── File saving helpers ──────────────────────────────────────────────────

    private fun saveJpeg(bytes: ByteArray, filename: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // API 29+: use MediaStore (scoped storage path).
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FindingTheWay")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "FindingTheWay"
                ).also { it.mkdirs() }
                File(dir, filename).writeBytes(bytes)
                MediaScannerConnection.scanFile(context, arrayOf(File(dir, filename).absolutePath),
                    arrayOf("image/jpeg"), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save failed: ${e.message}")
        }
    }

    private fun saveAnnotatedBitmap(bitmap: Bitmap, filename: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FindingTheWay")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return
                resolver.openOutputStream(uri)?.use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "FindingTheWay"
                ).also { it.mkdirs() }
                FileOutputStream(File(dir, filename)).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                }
                MediaScannerConnection.scanFile(context,
                    arrayOf(File(dir, filename).absolutePath), arrayOf("image/jpeg"), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Annotated save failed: ${e.message}")
        }
    }

    // ── Summary builder for UI/log ───────────────────────────────────────────

    private fun buildDetectionSummary(
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): String {
        if (detections.isEmpty()) return "No objects detected"
        return detections.mapIndexed { i, det ->
            val dist = distances[i]?.distanceFeet?.let { "~${it.toInt()} ft" } ?: "?"
            "${det.label} $dist [${det.motionState}] [${det.threatLevel}]"
        }.joinToString(", ")
    }

    // ── Lifecycle cleanup ────────────────────────────────────────────────────

    fun close() {
        completeActiveCaptureCycle("PIPELINE_CLOSED")
        detectorIdleUpshiftJob?.cancel()
        detectorIdleUpshiftJob = null
        scope.cancel()
        speaker.setTelemetryListener(null)
        speaker.close()
        objectDetector?.close()
    }

    fun stopSpeech() {
        speaker.stop()
    }
}
