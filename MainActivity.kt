package com.esp32.camera

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.*
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        const val CMD_SINGLE_PHOTO: Byte = 0x01
        const val CMD_BURST: Byte = 0x04
        const val REQUEST_PERMISSIONS = 1
    }

    // UI
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnBurst: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtProgress: TextView
    private lateinit var txtDetections: TextView

    // BLE connector
    private lateinit var bleConnector: SmartGlassesBleConnector

    // Photo buffer 
    private val photoBuffer = ByteArrayOutputStream()
    private var expectedChunkIndex = 0
    private var photoCount = 0
    private var isReceiving = false
    private var totalBytesReceived = 0
    private var lastReceivedChunk = -1
    private var droppedChunks = 0

    // Burst mode state
    private var isBurstMode = false
    private val burstFrameBuffers = mutableMapOf<Int, ByteArrayOutputStream>()
    private val burstFrameTimestamps = mutableMapOf<Int, Long>()
    private var currentBurstSeq = -1
    private var burstChunkIndex = 0
    private var burstExpectedChunkIndex = 0
    private var burstDroppedChunks = 0

    // Inference pipeline 
    @Volatile private var objectDetector: ObjectDetector? = null
    @Volatile private var distanceEstimator: DistanceEstimator? = null
    @Volatile private var motionTracker: MotionTracker? = null
    @Volatile private var imageAnnotator: ImageAnnotator? = null
    @Volatile private var detectionSpeaker: DetectionSpeaker? = null

    private val inferenceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        txtStatus = TextView(this).apply {
            text = "ESP32-CAM Smart Glasses"
            textSize = 20f
        }
        layout.addView(txtStatus)

        txtProgress = TextView(this).apply {
            text = "Ready"
            setPadding(0, 16, 0, 16)
        }
        layout.addView(txtProgress)

        txtDetections = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        layout.addView(txtDetections)

        btnScan = Button(this).apply {
            text = "Scan for ESP32-CAM"
            setOnClickListener { startScan() }
        }
        layout.addView(btnScan)

        btnConnect = Button(this).apply {
            text = "Connect"
            isEnabled = false
            setOnClickListener { connect() }
        }
        layout.addView(btnConnect)

        btnPhoto = Button(this).apply {
            text = "Take Photo"
            isEnabled = false
            setOnClickListener { takePhoto() }
        }
        layout.addView(btnPhoto)

        btnBurst = Button(this).apply {
            text = "Burst + Detect"
            isEnabled = false
            setOnClickListener { takeBurst() }
        }
        layout.addView(btnBurst)

        setContentView(layout)

        // Init BLE connector (generic – works from any Android device)
        bleConnector = SmartGlassesBleConnector(
            context  = this,
            config   = SmartGlassesBleConnector.Config(),   // defaults match ESP32-CAM firmware
            listener = bleListener
        )

        requestPermissions()

        // Initialize inference pipeline 
        initInferencePipeline()
    }

    // ── BLE listener (bridges generic connector → MainActivity UI & data) ──

    private val bleListener = object : SmartGlassesBleConnector.ConnectionListener {
        override fun onDeviceFound(device: BluetoothDevice, name: String) {
            txtStatus.text = "Found: $name"
            btnConnect.isEnabled = true
            btnScan.isEnabled = true
        }

        override fun onConnecting() {
            txtStatus.text = "Connecting..."
        }

        override fun onConnected(negotiatedMtu: Int) {
            txtStatus.text = "Ready! (MTU=$negotiatedMtu)"
            btnPhoto.isEnabled = true
            btnBurst.isEnabled = true
        }

        override fun onDisconnected() {
            txtStatus.text = "Disconnected"
            btnPhoto.isEnabled = false
            btnBurst.isEnabled = false
        }

        override fun onDataReceived(data: ByteArray) {
            handlePhotoData(data)
        }

        override fun onStatusMessage(message: String) {
            txtStatus.text = message
        }

        override fun onError(message: String) {
            txtStatus.text = message
            btnScan.isEnabled = true
        }
    }

    //
    // Initialize all inference components once at app startup
    //
    private fun initInferencePipeline() {
        inferenceScope.launch {
            try {
                val detector = ObjectDetector(this@MainActivity)
                val estimator = DistanceEstimator()
                val tracker = MotionTracker()
                val annotator = ImageAnnotator()
                val speaker = DetectionSpeaker(this@MainActivity)

                objectDetector = detector
                distanceEstimator = estimator
                motionTracker = tracker
                imageAnnotator = annotator
                detectionSpeaker = speaker

                Log.d(TAG, "Inference pipeline initialized")
                runOnUiThread {
                    txtDetections.text = "YOLO model loaded. Ready for detection."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize inference pipeline: ${e.message}")
                runOnUiThread {
                    txtDetections.text = "YOLO model NOT loaded (${e.message})"
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS)
    }

    //
    // Connect Smartglasses via BLE (delegated to SmartGlassesBleConnector)
    //
    private fun startScan() {
        btnScan.isEnabled = false
        btnConnect.isEnabled = false
        bleConnector.startScan()
    }

    private fun connect() {
        bleConnector.connect()
    }

    //  Smartglasses Command Actions

    private fun takePhoto() {
        isBurstMode = false
        photoBuffer.reset()
        isReceiving = false
        expectedChunkIndex = 0
        totalBytesReceived = 0
        lastReceivedChunk = -1
        droppedChunks = 0
        Log.w(TAG, "TAKE_PHOTO CMD SENT @ ${System.currentTimeMillis()}")
        if (bleConnector.writeCommand(CMD_SINGLE_PHOTO)) {
            txtProgress.text = "Requesting photo."
        } else {
            txtProgress.text = "Not connected"
        }
    }

    private fun takeBurst() {
        isBurstMode = true
        resetBurstState()
        if (bleConnector.writeCommand(CMD_BURST)) {
            txtProgress.text = "Requesting burst capture."
        } else {
            txtProgress.text = "Not connected"
        }
    }

    // Data Handling

    private fun handlePhotoData(data: ByteArray) {
        if (data.size < 2) return

        val frameIndex = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

        if (isBurstMode) {
            handleBurstData(data, frameIndex)
        } else {
            handleSinglePhotoData(data, frameIndex)
        }
    }

    // Single photo capture Action

    private fun handleSinglePhotoData(data: ByteArray, frameIndex: Int) {
        Log.d(TAG, "Single chunk: index=$frameIndex, size=${data.size}")

        // End marker: 0xFFFF (2 bytes)
        if (frameIndex == 0xFFFF) {
            Log.w(TAG, "END MARKER @ ${System.currentTimeMillis()} Total=$totalBytesReceived Dropped=$droppedChunks")
            runOnUiThread { txtProgress.text = "Complete: ${totalBytesReceived} bytes" }
            val photoBytes = photoBuffer.toByteArray()
            photoBuffer.reset()
            isReceiving = false

            // Save raw photo and run object detection
            saveSinglePhotoAndDetect(photoBytes)
            return
        }

        if (frameIndex == 0) {
            Log.w(TAG, "FIRST CHUNK @ ${System.currentTimeMillis()} size=${data.size}")
            isReceiving = true
            photoBuffer.reset()
            expectedChunkIndex = 0
            totalBytesReceived = 0
            lastReceivedChunk = -1
            droppedChunks = 0
            runOnUiThread { txtProgress.text = "Receiving photo..." }

            // Skip frame_index(2) + orientation(1)
            if (data.size > 3) {
                photoBuffer.write(data, 3, data.size - 3)
                totalBytesReceived += data.size - 3
            }
            lastReceivedChunk = 0
            expectedChunkIndex = 1
        } else if (isReceiving) {
            if (frameIndex != expectedChunkIndex) {
                val missed = frameIndex - expectedChunkIndex
                Log.e(TAG, "DROPPED $missed CHUNK(S): expected $expectedChunkIndex, got $frameIndex")
                droppedChunks += missed
            }
            // Skip frame_index(2)
            if (data.size > 2) {
                photoBuffer.write(data, 2, data.size - 2)
                totalBytesReceived += data.size - 2
            }
            lastReceivedChunk = frameIndex
            expectedChunkIndex = frameIndex + 1
        }

        runOnUiThread { txtProgress.text = "Receiving: ${totalBytesReceived / 1024} KB" }
    }

    // Burst photo capture Action 

    private fun resetBurstState() {
        burstFrameBuffers.clear()
        burstFrameTimestamps.clear()
        currentBurstSeq = -1
        burstChunkIndex = 0
        burstExpectedChunkIndex = 0
        burstDroppedChunks = 0
        isReceiving = false
        totalBytesReceived = 0
    }

    private fun handleBurstData(data: ByteArray, frameIndex: Int) {
        // End-of-burst marker: [0xFE, 0xFF] 
        if (data.size == 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
            Log.d(TAG, "END-OF-BURST marker received. Frames: ${burstFrameBuffers.size}")
            runOnUiThread { txtProgress.text = "Burst complete: ${burstFrameBuffers.size} frames" }
            isReceiving = false
            processBurstFrames()
            return
        }

        // Per-frame end marker: [0xFF, 0xFF, burst_seq]
        if (frameIndex == 0xFFFF && data.size == 3) {
            val burstSeq = data[2].toInt() and 0xFF
            Log.d(TAG, "Burst frame $burstSeq end marker. Bytes: ${burstFrameBuffers[burstSeq]?.size() ?: 0}")
            runOnUiThread { txtProgress.text = "Frame $burstSeq complete" }
            // Reset per-frame chunk tracking for next frame
            burstExpectedChunkIndex = 0
            return
        }

        // Check data chunks for corruption by size, minimum 3 bytes
        if (data.size < 3) return

        val burstSeq = data[2].toInt() and 0xFF

        // New burst frame starting
        if (frameIndex == 0 && burstSeq != currentBurstSeq) {
            currentBurstSeq = burstSeq
            burstFrameBuffers[burstSeq] = ByteArrayOutputStream()
            burstFrameTimestamps[burstSeq] = System.currentTimeMillis()
            burstExpectedChunkIndex = 0
            Log.d(TAG, "Burst frame $burstSeq starting")
        }

        val buffer = burstFrameBuffers[burstSeq] ?: return

        if (frameIndex == 0) {
            if (data.size > 4) {
                buffer.write(data, 4, data.size - 4)
                totalBytesReceived += data.size - 4
            }
            burstExpectedChunkIndex = 1
        } else {
            if (frameIndex != burstExpectedChunkIndex) {
                val missed = frameIndex - burstExpectedChunkIndex
                Log.e(TAG, "Burst[$burstSeq] DROPPED $missed chunk(s)")
                burstDroppedChunks += missed
            }
            if (data.size > 3) {
                buffer.write(data, 3, data.size - 3)
                totalBytesReceived += data.size - 3
            }
            burstExpectedChunkIndex = frameIndex + 1
        }

        runOnUiThread {
            txtProgress.text = "Burst[$burstSeq]: ${totalBytesReceived / 1024} KB"
        }
    }

    // Object Detection

    private fun saveSinglePhotoAndDetect(photoBytes: ByteArray) {
        Log.w(TAG, "SAVE+DETECT @ ${System.currentTimeMillis()} size=${photoBytes.size}")
        if (!validateJpeg(photoBytes)) return

        photoCount++
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val rawFilename = "ESP32_${timestamp}_${photoCount}.jpg"

        val dcimDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "ESP32_Camera"
        )
        dcimDir.mkdirs()

        val rawFile = File(dcimDir, rawFilename)
        try {
            FileOutputStream(rawFile).use { it.write(photoBytes) }
            MediaScannerConnection.scanFile(this, arrayOf(rawFile.absolutePath), arrayOf("image/jpeg"), null)
            runOnUiThread { txtProgress.text = "Saved: $rawFilename (${photoBytes.size / 1024} KB)" }
        } catch (e: Exception) {
            runOnUiThread { txtProgress.text = "Save failed: ${e.message}" }
            return
        }

        runDetection(listOf(photoBytes), listOf(System.currentTimeMillis()), timestamp)
    }

    private fun processBurstFrames() {
        val sortedSeqs = burstFrameBuffers.keys.sorted()
        if (sortedSeqs.isEmpty()) {
            runOnUiThread {txtProgress.text = "Error: No burst frames received"}
            return
        }

        val frameBytesList = mutableListOf<ByteArray>()
        val timestamps = mutableListOf<Long>()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dcimDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "ESP32_Camera"
        )
        dcimDir.mkdirs()

        for (seq in sortedSeqs) {
            val bytes = burstFrameBuffers[seq]?.toByteArray() ?: continue
            val ts = burstFrameTimestamps[seq] ?: System.currentTimeMillis()

            if (!validateJpeg(bytes)) {
                Log.e(TAG, "Burst frame $seq invalid JPEG, skipped")
                continue
            }

            frameBytesList.add(bytes)
            timestamps.add(ts)

            photoCount++
            val filename = "ESP32_${timestamp}_burst${seq}_${photoCount}.jpg"
            val file = File(dcimDir, filename)
            try {
                FileOutputStream(file).use { it.write(bytes) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save burst frame $seq: ${e.message}")
            }
        }

        burstFrameBuffers.clear()
        burstFrameTimestamps.clear()

        if (frameBytesList.isEmpty()) {
            runOnUiThread { txtProgress.text = "Error: No valid burst frames" }
            return
        }

        runOnUiThread { txtProgress.text = "Saved ${frameBytesList.size} burst frames. Running detection..." }

        runDetection(frameBytesList, timestamps, timestamp)
    }

    
    private fun runDetection(
        frameBytesList: List<ByteArray>,
        timestamps: List<Long>,
        sessionTimestamp: String
    ) {
        val detector = objectDetector
        if (detector == null) {
            runOnUiThread { txtDetections.text = "YOLO model not loaded" }
            return
        }

        inferenceScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // Decode all frames to Bitmaps
                val allBitmaps = frameBytesList.mapNotNull { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (allBitmaps.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        txtDetections.text = "Error: Could not decode JPEG"
                    }
                    return@launch
                }

                // Use the first successfully decoded frame as the reference
                // resolution.  Filter out any frames that differ in size so
                // that detection and distance estimation use a single
                // consistent set of dimensions.
                val refWidth  = allBitmaps.first().width
                val refHeight = allBitmaps.first().height
                val bitmaps = allBitmaps.filter { bmp ->
                    if (bmp.width != refWidth || bmp.height != refHeight) {
                        Log.w(TAG, "Dropping burst frame with mismatched dimensions " +
                                "${bmp.width}x${bmp.height} (expected ${refWidth}x${refHeight})")
                        bmp.recycle()
                        false
                    } else true
                }
                if (bitmaps.isEmpty()) {
                    allBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                    withContext(Dispatchers.Main) {
                        txtDetections.text = "Error: No frames with consistent dimensions"
                    }
                    return@launch
                }

                // Run YOLO on each frame
                val frameDetections = bitmaps.map { bitmap ->
                    detector.detect(bitmap)
                }

                // Motion tracking across frames
                val trackedDetections = motionTracker?.analyzeMotion(frameDetections, timestamps)
                    ?: frameDetections.last().map { det ->
                        MotionTracker.TrackedDetection(
                            label = det.label,
                            confidence = det.confidence,
                            boundingBox = det.boundingBox,
                            classIndex = det.classIndex,
                            motionState = MotionTracker.MotionState.UNKNOWN,
                            motionMagnitude = 0f
                        )
                    }

                // Distance estimation for each detection
                // All bitmaps were validated to have consistent dimensions
                // (refWidth x refHeight), so use those directly.
                val distances = mutableMapOf<Int, DistanceEstimator.DistanceResult>()
                val estimator = distanceEstimator
                if (estimator != null) {
                    estimator.frameWidth  = refWidth.toFloat()
                    estimator.frameHeight = refHeight.toFloat()
                    for ((idx, det) in trackedDetections.withIndex()) {
                        distances[idx] = estimator.estimateDistance(det.label, det.boundingBox, det.trackId)
                    }
                }

                // Fuse distance into motion tracking: refines motion states
                // based on proximity and computes a unified threat level.
                val fusedDetections = if (distances.isNotEmpty()) {
                    motionTracker?.fuseDistance(trackedDetections, distances) ?: trackedDetections
                } else {
                    trackedDetections
                }

                // Frame Annotation 
                val lastBitmap = bitmaps.last()
                val annotator = imageAnnotator
                if (annotator != null && fusedDetections.isNotEmpty()) {
                    val annotated = annotator.annotate(lastBitmap, fusedDetections, distances)
                    val dcimDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "ESP32_Camera"
                    )
                    val annotatedFile = File(dcimDir, "ESP32_${sessionTimestamp}_annotated.jpg")
                    try {
                        FileOutputStream(annotatedFile).use { fos ->
                            annotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos)
                        }
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(annotatedFile.absolutePath),
                            arrayOf("image/jpeg"),
                            null
                        )
                        Log.d(TAG, "Annotated image saved: ${annotatedFile.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save annotated image: ${e.message}")
                    }
                    annotated.recycle()
                }

                // Speak detections
                detectionSpeaker?.speak(fusedDetections, distances)

                val elapsed = System.currentTimeMillis() - startTime
                val summary = buildDetectionSummary(fusedDetections, distances, elapsed)

                bitmaps.forEach { if (!it.isRecycled) it.recycle() }

                withContext(Dispatchers.Main) {
                    txtDetections.text = summary
                    txtProgress.text = "Detection complete (${elapsed}ms)"
                }

            } catch (e: Exception) {
                Log.e(TAG, "Detection pipeline failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    txtDetections.text = "Detection error: ${e.message}"
                }
            }
        }
    }

    private fun buildDetectionSummary(
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>,
        elapsedMs: Long
    ): String {
        if (detections.isEmpty()) return "No objects detected (${elapsedMs}ms)"

        val sb = StringBuilder()
        sb.appendLine("Detected ${detections.size} object(s) in ${elapsedMs}ms:")
        for ((idx, det) in detections.withIndex()) {
            val distResult = distances[idx]
            val dist = if (distResult != null) {
                "~${distResult.distanceFeet.toInt()} ft (${distResult.distanceCategory})"
            } else "?"
            val conf = "${(det.confidence * 100).toInt()}%"
            val motion = when (det.motionState) {
                MotionTracker.MotionState.APPROACHING -> " [APPROACHING]"
                MotionTracker.MotionState.MOVING_AWAY -> " [MOVING AWAY]"
                MotionTracker.MotionState.CROSSING_LEFT -> " [←LEFT]"
                MotionTracker.MotionState.CROSSING_RIGHT -> " [RIGHT→]"
                MotionTracker.MotionState.STATIONARY -> " [STILL]"
                MotionTracker.MotionState.UNKNOWN -> ""
            }
            val threat = when (det.threatLevel) {
                MotionTracker.ThreatLevel.CRITICAL -> " ⚠CRITICAL"
                MotionTracker.ThreatLevel.HIGH     -> " ⚠HIGH"
                MotionTracker.ThreatLevel.MODERATE -> " !MODERATE"
                MotionTracker.ThreatLevel.LOW      -> ""
                MotionTracker.ThreatLevel.UNKNOWN  -> ""
            }
            sb.appendLine("  ${det.label} $conf - $dist$motion$threat")
        }
        return sb.toString().trim()
    }


    private fun validateJpeg(data: ByteArray): Boolean {
        if (data.size < 4) {
            Log.e(TAG, "JPEG too small: ${data.size} bytes")
            return false
        }
        if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte() || data[2] != 0xFF.toByte()) {
            Log.e(TAG, "Invalid JPEG header: ${data.take(4).joinToString { String.format("%02X", it) }}")
            runOnUiThread {
                Toast.makeText(this, "Received corrupted data - invalid JPEG", Toast.LENGTH_LONG).show()
            }
            return false
        }
        val lastIdx = data.size - 1
        if (data[lastIdx - 1] != 0xFF.toByte() || data[lastIdx] != 0xD9.toByte()) {
            Log.w(TAG, "JPEG missing FFD9 end marker - file may be corrupted")
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceScope.cancel()
        detectionSpeaker?.close()
        objectDetector?.close()
        bleConnector.disconnect()
    }
}
