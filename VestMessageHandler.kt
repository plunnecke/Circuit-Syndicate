package com.circuitsyndicate.findingtheway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.circuitsyndicate.findingtheway.storage.ImageStorageManager

/**
 * Handles all messages received from the VEST ESP32.
 *
 * Protocol reference (from firmware — do not change these strings without
 * updating the ESP32 sketch):
 *
 * VEST → APP (SerialBT.println sends string + \n):
 *   Connection:
 *     "ESP_CONNECTED"
 *   State (sent by reportSystemState() after every change):
 *     "BOTH_BUTTONS_ON"          hapticsOn=true,  sensorsOn=true
 *     "BOTH_BUTTONS_OFF"         hapticsOn=false, sensorsOn=false
 *     "HAPTICS_ON_SENSORS_OFF"   hapticsOn=true,  sensorsOn=false
 *     "HAPTICS_OFF_SENSORS_ON"   hapticsOn=false, sensorsOn=true
 *   Button press state (sent immediately, then reportSystemState() follows):
 *     "HAPTICS:ON"   "HAPTICS:OFF"   "SENSORS:ON"
 *   After safety check:
 *     "SENSORS ARE NOW OFF"      sensors turned off after confirmation
 *     "HAPTICS ARE NOW OFF"      haptics turned off after confirmation
 *     "CONFIRMED: GLASSES ON"    user confirmed glasses are on
 *     "TURN GLASSES ON"          user said glasses are off
 *   Safety prompt:
 *     "Are Glasses On?"
 *   Battery (sent every 5 s):
 *     "BATTERY:<0-100>"             numeric level
 *     "BATTERY_CRITICAL: 5%"        ≤ 5%
 *     "BATTERY_LOW: 10%"            ≤ 10%
 *     "BATTERY_LOW: 25%"            ≤ 25%
 *     "BATTERY_MEDIUM: 50%"         ≤ 50%
 *     "BATTERY_HIGH: 80%"           ≤ 80%
 *     "BATTERY_CHARGED, 100%"       > 80%  (note: comma, not colon)
 *   Error:
 *     "APP_TOGGLE_MISMATCH:<TARGET>:<REQUESTED>:<PHYSICAL>"
 *        Example: APP_TOGGLE_MISMATCH:HAPTICS:OFF:ON
 *     "UNKNOWN RESPONSE"
 *     "TYPE: 'Glasses are on' OR 'Glasses are off'"
 *
 * APP → VEST (matched after cmd.toUpperCase()):
 *   See VestCommandSender for the exact strings.
 */
class VestMessageHandler(
    private val context: Context,
    private val glassesCaptureGateway: GlassesCaptureGateway = DefaultGlassesCaptureGateway
) {

    companion object {
        private const val TAG = "VestMessageHandler"
        private const val AUTO_BURST_RETRY_MS = 300L
        private const val AUTO_BURST_IN_FLIGHT_TIMEOUT_MS = 15_000L
        private const val MAX_PENDING_AUTO_BURST_REQUESTS = 1
        private const val AUTO_BURST_COOLDOWN_MS = 15_000L
        private const val AUTO_BURST_TRIGGER_WINDOW_MS = 8_000L
        private const val AUTO_BURST_REQUIRED_TRIGGER_COUNT = 2
        private const val AUTO_BURST_CONTINUOUS_DETECTION_MS = 8_000L
        private const val AUTO_BURST_TRIGGER_GAP_RESET_MS = 5_000L
    }

    interface GlassesCaptureGateway {
        fun isConnected(): Boolean
        fun isCaptureEnabled(): Boolean
        fun takeBurst(): Boolean
    }

    private object DefaultGlassesCaptureGateway : GlassesCaptureGateway {
        override fun isConnected(): Boolean = GlassesCommandSender.isConnected()
        override fun isCaptureEnabled(): Boolean = GlassesCommandSender.isCaptureEnabled()
        override fun takeBurst(): Boolean = GlassesCommandSender.takeBurst()
    }

    data class VestState(
        var hapticsOn: Boolean = false,
        var sensorsOn: Boolean = false,
        var systemOn: Boolean = false,
        var batteryLevel: Int = -1,
        var batteryLabel: String = ""       // "CRITICAL" | "LOW" | "MEDIUM" | "HIGH" | "CHARGED"
    )

    interface VestListener {
        fun onStateChanged(state: VestState)
        fun onDetection(objectName: String, hapticPattern: String?)
        fun onSafetyPrompt(message: String)
        fun onConnectionChanged(connected: Boolean)
        fun onBatteryWarning(label: String, level: Int)
        fun onToggleMismatch(target: String, requestedStateOn: Boolean, physicalStateOn: Boolean)
    }

    private val state = VestState()
    private val listeners = mutableListOf<VestListener>()

    private var imageBuffer: ByteArray? = null
    private var receivingImage = false
    private val imageStorage by lazy { ImageStorageManager.getInstance(context) }
    private val autoBurstHandler = Handler(Looper.getMainLooper())
    private val autoBurstStateLock = Any()
    private var pendingAutoBurstRequests = 0
    private var autoBurstInFlight = false
    private var autoBurstInFlightSinceMs = 0L
    private var autoBurstRetryScheduled = false
    private var autoBurstImmediateTriggerArmed = false
    private val recentAutoBurstTriggerTimesMs = java.util.ArrayDeque<Long>()
    private var autoBurstTriggerStreakStartMs = 0L
    private var lastAutoBurstTriggerMs = 0L
    private var autoBurstCooldownUntilMs = 0L

    private val burstTimingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GlassesImagePipeline.ACTION_BURST_TIMING_UPDATED) return
            onAutoBurstCompleted("pipeline_burst_timing")
        }
    }

    init {
        registerBurstTimingReceiver()
    }

    // ── Listener management ───────────────────────────────────────────────────

    fun addListener(listener: VestListener) {
        synchronized(listeners) { if (!listeners.contains(listener)) listeners.add(listener) }
    }

    fun removeListener(listener: VestListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ── Message routing ───────────────────────────────────────────────────────

    fun handleMessage(message: String) {
        val msg = message.trim()
        if (msg.isEmpty()) return
        Log.d(TAG, "← \"$msg\"")
        InteractionLogger.log("VEST_MSG", "VEST", msg)

        when {

            // ── Connection ────────────────────────────────────────────────────
            msg == "ESP_CONNECTED" -> {
                state.systemOn  = true
                state.hapticsOn = true   // firmware initialises both ON
                state.sensorsOn = true
                resetAutoBurstQueueState("vest_connected", armImmediateTrigger = true)
                notifyListeners { it.onConnectionChanged(true) }
                InteractionLogger.logConnection("VEST", true)
                notifyStateChanged()
            }
            msg == "ESP_DISCONNECTED" -> {
                state.systemOn  = false
                state.hapticsOn = false
                state.sensorsOn = false
                resetAutoBurstQueueState("vest_disconnected")
                notifyListeners { it.onConnectionChanged(false) }
                InteractionLogger.logConnection("VEST", false)
                notifyStateChanged()
            }

            // ── Image transfer ────────────────────────────────────────────────
            msg == "IMAGE_START" -> {
                receivingImage = true
                imageBuffer = ByteArray(0)
                Log.d(TAG, "Image transfer started")
            }
            msg == "IMAGE_END" -> {
                receivingImage = false
                imageBuffer?.takeIf { it.isNotEmpty() }?.let { bytes ->
                    imageStorage.saveImage(bytes)
                    Log.d(TAG, "Vest image saved (${bytes.size} bytes)")
                }
                imageBuffer = null
            }

            // ── Combined state reports (from firmware reportSystemState()) ────
            // These are authoritative — always set all three fields.
            msg == "BOTH_BUTTONS_ON" -> {
                state.systemOn  = true
                state.hapticsOn = true
                state.sensorsOn = true
                resetAutoBurstQueueState("both_buttons_on", armImmediateTrigger = true)
                InteractionLogger.logStateChange("HAPTICS+SENSORS", true, "VEST")
                notifyStateChanged()
            }
            msg == "BOTH_BUTTONS_OFF" -> {
                state.systemOn  = false
                state.hapticsOn = false
                state.sensorsOn = false
                resetAutoBurstQueueState("both_buttons_off")
                InteractionLogger.logStateChange("HAPTICS+SENSORS", false, "VEST")
                notifyStateChanged()
            }
            msg == "HAPTICS_ON_SENSORS_OFF" -> {
                state.systemOn  = true
                state.hapticsOn = true
                state.sensorsOn = false
                resetAutoBurstQueueState("sensors_off")
                notifyStateChanged()
            }
            msg == "HAPTICS_OFF_SENSORS_ON" -> {
                state.systemOn  = true
                state.hapticsOn = false
                state.sensorsOn = true
                resetAutoBurstQueueState("sensors_on", armImmediateTrigger = true)
                notifyStateChanged()
            }
            msg == "ALL OFF" -> {
                state.systemOn  = false
                state.hapticsOn = false
                state.sensorsOn = false
                resetAutoBurstQueueState("all_off")
                InteractionLogger.logStateChange("HAPTICS+SENSORS", false, "VEST")
                notifyStateChanged()
            }

            // ── Individual state changes (button press, before reportSystemState) ─
            msg == "HAPTICS:ON" || msg == "HAPTICS ON" -> {
                state.hapticsOn = true
                InteractionLogger.logStateChange("HAPTICS", true, "VEST")
                notifyStateChanged()
            }
            msg == "HAPTICS:OFF" || msg == "HAPTICS OFF" || msg == "HAPTICS ARE NOW OFF" -> {
                state.hapticsOn = false
                InteractionLogger.logStateChange("HAPTICS", false, "VEST")
                notifyStateChanged()
            }
            msg == "SENSORS:ON" || msg == "SENSORS ON" -> {
                state.sensorsOn = true
                resetAutoBurstQueueState("sensors_on", armImmediateTrigger = true)
                InteractionLogger.logStateChange("SENSORS", true, "VEST")
                notifyStateChanged()
            }
            msg == "SENSORS ARE NOW OFF" || msg == "SENSORS OFF" -> {
                state.sensorsOn = false
                resetAutoBurstQueueState("sensors_off")
                InteractionLogger.logStateChange("SENSORS", false, "VEST")
                notifyStateChanged()
            }

            // ── Post-safety-check confirmations ───────────────────────────────
            // These arrive after CONFIRMED: GLASSES ON, before reportSystemState()
            msg == "CONFIRMED: GLASSES ON" -> {
                InteractionLogger.log("SAFETY", "VEST", "Glasses confirmed ON — executing pending action")
            }

            // ── Safety prompts (vest needs user to confirm glasses status) ────
            msg == "Are Glasses On?" -> {
                notifyListeners { it.onSafetyPrompt("Are your smart glasses on?") }
            }
            msg == "TURN GLASSES ON" -> {
                notifyListeners { it.onSafetyPrompt("Please put on your smart glasses before continuing.") }
            }

            // ── Vest-triggered glasses capture ───────────────────────────────
            msg == "GLASSES_CAPTURE" -> {
                handleGlassesCaptureTrigger()
            }

            // ── Battery — numeric level ───────────────────────────────────────
            // "BATTERY:<number>" sent every 5 seconds
            msg.startsWith("BATTERY:") && !msg.contains(" ") -> {
                val level = msg.substringAfter("BATTERY:").trim().toIntOrNull()
                if (level != null && level in 0..100) {
                    state.batteryLevel = level
                    InteractionLogger.logBattery("VEST", level)
                    broadcastBattery("VEST", level)
                    notifyStateChanged()
                }
            }

            // ── Battery — status labels (sent alongside numeric update) ───────
            msg.startsWith("BATTERY_CRITICAL:") -> {
                state.batteryLabel = "CRITICAL"
                notifyListeners { it.onBatteryWarning("CRITICAL", state.batteryLevel) }
                InteractionLogger.log("BATTERY_WARN", "VEST", msg)
            }
            msg.startsWith("BATTERY_LOW:") -> {
                state.batteryLabel = "LOW"
                notifyListeners { it.onBatteryWarning("LOW", state.batteryLevel) }
                InteractionLogger.log("BATTERY_WARN", "VEST", msg)
            }
            msg.startsWith("BATTERY_MEDIUM:") -> {
                state.batteryLabel = "MEDIUM"
                InteractionLogger.log("BATTERY", "VEST", msg)
            }
            msg.startsWith("BATTERY_HIGH:") -> {
                state.batteryLabel = "HIGH"
                InteractionLogger.log("BATTERY", "VEST", msg)
            }
            // Note: firmware sends "BATTERY_CHARGED, 100%" with a COMMA not colon
            msg.startsWith("BATTERY_CHARGED") -> {
                state.batteryLabel = "CHARGED"
                InteractionLogger.log("BATTERY", "VEST", msg)
            }

            // ── App toggle mismatch reports ─────────────────────────────────
            msg.startsWith("APP_TOGGLE_MISMATCH:") -> {
                val parsed = parseToggleMismatch(msg)
                if (parsed != null) {
                    val (target, requestedStateOn, physicalStateOn) = parsed
                    notifyListeners {
                        it.onToggleMismatch(target, requestedStateOn, physicalStateOn)
                    }
                    InteractionLogger.log("MISMATCH", "VEST", msg)
                } else {
                    Log.w(TAG, "Malformed mismatch message: $msg")
                    InteractionLogger.log("VEST_ERROR", "VEST", "Malformed mismatch: $msg")
                }
            }

            // ── Firmware error / debug messages ───────────────────────────────
            msg == "UNKNOWN RESPONSE" ||
            msg.startsWith("TYPE:") -> {
                Log.w(TAG, "Vest reported protocol error: $msg")
                InteractionLogger.log("VEST_ERROR", "VEST", msg)
            }

            // ── Ignore benign noise ───────────────────────────────────────────
            msg == "ACK" || msg == "OK" -> Log.d(TAG, "ACK: $msg")

            else -> Log.d(TAG, "Unhandled vest message: \"$msg\"")
        }
    }

    fun handleImageBytes(chunk: ByteArray) {
        if (!receivingImage) return
        imageBuffer = (imageBuffer ?: ByteArray(0)) + chunk
    }

    fun getCurrentState(): VestState = state.copy()

    fun onCaptureGateChanged(enabled: Boolean, source: String = "APP") {
        if (enabled) {
            resetAutoBurstQueueState(
                reason = "capture_enabled:$source",
                armImmediateTrigger = true
            )
        } else {
            resetAutoBurstQueueState(
                reason = "capture_disabled:$source",
                armImmediateTrigger = false
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun notifyStateChanged() {
        context.sendBroadcast(Intent("ESP32_STATUS_UPDATE").apply {
            putExtra("haptics",      state.hapticsOn)
            putExtra("sensors",      state.sensorsOn)
            putExtra("system",       state.systemOn)
            putExtra("battery",      state.batteryLevel)
            putExtra("batteryLabel", state.batteryLabel)
        })
        notifyListeners { it.onStateChanged(state.copy()) }
    }

    private fun broadcastBattery(deviceType: String, level: Int) {
        context.sendBroadcast(Intent("BATTERY_UPDATE").apply {
            putExtra("deviceType", deviceType)
            putExtra("level", level)
        })
    }

    private fun registerBurstTimingReceiver() {
        val filter = IntentFilter(GlassesImagePipeline.ACTION_BURST_TIMING_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(burstTimingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(burstTimingReceiver, filter)
        }
    }

    private fun handleGlassesCaptureTrigger() {
        if (!state.systemOn || !state.sensorsOn) {
            resetAutoBurstQueueState("sensors_or_system_off")
            Log.d(TAG, "GLASSES_CAPTURE ignored while vest sensors/system are off")
            return
        }

        if (!glassesCaptureGateway.isConnected()) {
            resetAutoBurstQueueState("glasses_disconnected")
            Log.d(TAG, "GLASSES_CAPTURE received while glasses are disconnected")
            return
        }

        if (!glassesCaptureGateway.isCaptureEnabled()) {
            resetAutoBurstQueueState("capture_disabled")
            Log.d(TAG, "GLASSES_CAPTURE ignored because glasses capture is disabled")
            return
        }

        val now = System.currentTimeMillis()
        val shouldDispatch = synchronized(autoBurstStateLock) {
            shouldDispatchAutoBurstForTriggerLocked(now)
        }

        if (!shouldDispatch) {
            return
        }

        enqueueAutoBurstRequest()
    }

    private fun shouldDispatchAutoBurstForTriggerLocked(nowMs: Long): Boolean {
        if (autoBurstImmediateTriggerArmed) {
            autoBurstImmediateTriggerArmed = false
            recentAutoBurstTriggerTimesMs.clear()
            autoBurstTriggerStreakStartMs = nowMs
            lastAutoBurstTriggerMs = nowMs
            return true
        }

        if (lastAutoBurstTriggerMs <= 0L ||
            nowMs - lastAutoBurstTriggerMs > AUTO_BURST_TRIGGER_GAP_RESET_MS
        ) {
            autoBurstTriggerStreakStartMs = nowMs
            recentAutoBurstTriggerTimesMs.clear()
        }

        lastAutoBurstTriggerMs = nowMs
        if (autoBurstTriggerStreakStartMs <= 0L) {
            autoBurstTriggerStreakStartMs = nowMs
        }

        recentAutoBurstTriggerTimesMs.addLast(nowMs)
        while (recentAutoBurstTriggerTimesMs.isNotEmpty()) {
            val ageMs = nowMs - (recentAutoBurstTriggerTimesMs.peekFirst() ?: nowMs)
            if (ageMs <= AUTO_BURST_TRIGGER_WINDOW_MS) break
            recentAutoBurstTriggerTimesMs.removeFirst()
        }

        if (nowMs < autoBurstCooldownUntilMs) {
            return false
        }

        val triggerCount = recentAutoBurstTriggerTimesMs.size
        val streakDurationMs = (nowMs - autoBurstTriggerStreakStartMs).coerceAtLeast(0L)
        val qualifiesByCount = triggerCount >= AUTO_BURST_REQUIRED_TRIGGER_COUNT
        val qualifiesByDuration = streakDurationMs >= AUTO_BURST_CONTINUOUS_DETECTION_MS

        if (!qualifiesByCount && !qualifiesByDuration) {
            return false
        }

        recentAutoBurstTriggerTimesMs.clear()
        autoBurstTriggerStreakStartMs = nowMs
        return true
    }

    private fun enqueueAutoBurstRequest() {
        synchronized(autoBurstStateLock) {
            if (pendingAutoBurstRequests >= MAX_PENDING_AUTO_BURST_REQUESTS) {
                // Keep one pending request while in-flight to avoid stale burst backlogs.
                Log.d(TAG, "Auto burst trigger coalesced while request is pending")
                return
            }
            pendingAutoBurstRequests += 1
        }
        drainAutoBurstQueue("vest_trigger")
    }

    private fun onAutoBurstCompleted(source: String) {
        synchronized(autoBurstStateLock) {
            if (!autoBurstInFlight) return
            autoBurstInFlight = false
            autoBurstInFlightSinceMs = 0L
        }
        drainAutoBurstQueue(source)
    }

    private fun resetAutoBurstQueueState(reason: String, armImmediateTrigger: Boolean = false) {
        synchronized(autoBurstStateLock) {
            pendingAutoBurstRequests = 0
            autoBurstInFlight = false
            autoBurstInFlightSinceMs = 0L
            autoBurstRetryScheduled = false
            resetAutoBurstTriggerHistoryLocked()
            autoBurstImmediateTriggerArmed = armImmediateTrigger
        }
        autoBurstHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Auto burst queue reset: $reason")
    }

    private fun resetAutoBurstTriggerHistoryLocked() {
        recentAutoBurstTriggerTimesMs.clear()
        autoBurstTriggerStreakStartMs = 0L
        lastAutoBurstTriggerMs = 0L
        autoBurstCooldownUntilMs = 0L
    }

    private fun drainAutoBurstQueue(trigger: String) {
        synchronized(autoBurstStateLock) {
            val now = System.currentTimeMillis()

            if (autoBurstInFlight) {
                val inFlightAgeMs = now - autoBurstInFlightSinceMs
                if (autoBurstInFlightSinceMs > 0L && inFlightAgeMs > AUTO_BURST_IN_FLIGHT_TIMEOUT_MS) {
                    Log.w(TAG, "Auto burst in-flight timeout after ${inFlightAgeMs}ms; recovering queue")
                    autoBurstInFlight = false
                    autoBurstInFlightSinceMs = 0L
                } else {
                    return
                }
            }

            if (pendingAutoBurstRequests <= 0) {
                return
            }

            if (now < autoBurstCooldownUntilMs) {
                scheduleAutoBurstRetryLocked(
                    reason = "cooldown_active",
                    delayMs = (autoBurstCooldownUntilMs - now).coerceAtLeast(AUTO_BURST_RETRY_MS)
                )
                return
            }

            if (!glassesCaptureGateway.isConnected()) {
                scheduleAutoBurstRetryLocked("glasses_disconnected")
                return
            }

            if (!glassesCaptureGateway.isCaptureEnabled()) {
                Log.w(TAG, "Auto burst request blocked because glasses capture is disabled")
                pendingAutoBurstRequests = 0
                autoBurstRetryScheduled = false
                autoBurstInFlight = false
                autoBurstInFlightSinceMs = 0L
                resetAutoBurstTriggerHistoryLocked()
                return
            }

            val sent = glassesCaptureGateway.takeBurst()
            if (sent) {
                pendingAutoBurstRequests -= 1
                autoBurstInFlight = true
                autoBurstInFlightSinceMs = now
                autoBurstRetryScheduled = false
                autoBurstCooldownUntilMs = now + AUTO_BURST_COOLDOWN_MS
                Log.d(
                    TAG,
                    "Auto burst dispatched (trigger=$trigger, pending=$pendingAutoBurstRequests)"
                )
                return
            }

            Log.w(TAG, "Auto burst dispatch failed (trigger=$trigger); retrying")
            scheduleAutoBurstRetryLocked("dispatch_failed")
        }
    }

    private fun scheduleAutoBurstRetryLocked(reason: String, delayMs: Long = AUTO_BURST_RETRY_MS) {
        if (autoBurstRetryScheduled) {
            return
        }
        autoBurstRetryScheduled = true
        autoBurstHandler.postDelayed({
            synchronized(autoBurstStateLock) {
                autoBurstRetryScheduled = false
            }
            drainAutoBurstQueue("retry")
        }, delayMs)
        Log.d(TAG, "Auto burst retry scheduled ($reason delay_ms=$delayMs)")
    }

    private fun parseToggleMismatch(msg: String): Triple<String, Boolean, Boolean>? {
        val parts = msg.split(":")
        if (parts.size != 4) return null

        val target = parts[1].trim()
        val requestedStateOn = when (parts[2].trim()) {
            "ON" -> true
            "OFF" -> false
            else -> return null
        }
        val physicalStateOn = when (parts[3].trim()) {
            "ON" -> true
            "OFF" -> false
            else -> return null
        }

        return Triple(target, requestedStateOn, physicalStateOn)
    }

    private inline fun notifyListeners(action: (VestListener) -> Unit) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try { action(listener) } catch (e: Exception) {
                    Log.e(TAG, "Listener error", e)
                }
            }
        }
    }
}
