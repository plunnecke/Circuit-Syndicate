package com.circuitsyndicate.findingtheway

import android.content.Context
import android.content.Intent
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
 *     "SYSTEM_POWER_LOCK_ACTIVE" system lock is OFF, subsystem enable command denied
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
        private const val AUTO_SINGLE_CAPTURE_MIN_INTERVAL_MS = 5_000L
    }

    interface GlassesCaptureGateway {
        fun isConnected(): Boolean
        fun isCaptureEnabled(): Boolean
        fun isCapturePipelineBusy(): Boolean
        fun takePhoto(): Boolean
    }

    private object DefaultGlassesCaptureGateway : GlassesCaptureGateway {
        override fun isConnected(): Boolean = GlassesCommandSender.isConnected()
        override fun isCaptureEnabled(): Boolean = GlassesCommandSender.isCaptureEnabled()
        override fun isCapturePipelineBusy(): Boolean = GlassesCommandSender.isCapturePipelineBusy()
        override fun takePhoto(): Boolean = GlassesCommandSender.takePhoto()
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
    private val autoCaptureStateLock = Any()
    private var lastAutoSingleCaptureAtMs = 0L

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
                resetAutoSingleCaptureThrottle("vest_connected", allowImmediateCapture = true)
                notifyListeners { it.onConnectionChanged(true) }
                InteractionLogger.logConnection("VEST", true)
                notifyStateChanged()
            }
            msg == "ESP_DISCONNECTED" -> {
                state.systemOn  = false
                state.hapticsOn = false
                state.sensorsOn = false
                resetAutoSingleCaptureThrottle("vest_disconnected")
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
                resetAutoSingleCaptureThrottle("both_buttons_on", allowImmediateCapture = true)
                InteractionLogger.logStateChange("HAPTICS+SENSORS", true, "VEST")
                notifyStateChanged()
            }
            msg == "BOTH_BUTTONS_OFF" -> {
                state.hapticsOn = false
                state.sensorsOn = false
                // Preserve explicit system-power intent. BOTH_BUTTONS_OFF can also
                // mean both subsystems were turned off individually while system power
                // remains logically enabled.
                state.systemOn = DeviceManager.isVestSystemEnabled()
                resetAutoSingleCaptureThrottle("both_buttons_off")
                InteractionLogger.logStateChange("HAPTICS+SENSORS", false, "VEST")
                notifyStateChanged()
            }
            msg == "HAPTICS_ON_SENSORS_OFF" -> {
                state.systemOn  = true
                state.hapticsOn = true
                state.sensorsOn = false
                resetAutoSingleCaptureThrottle("sensors_off")
                notifyStateChanged()
            }
            msg == "HAPTICS_OFF_SENSORS_ON" -> {
                state.systemOn  = true
                state.hapticsOn = false
                state.sensorsOn = true
                resetAutoSingleCaptureThrottle("sensors_on", allowImmediateCapture = true)
                notifyStateChanged()
            }
            msg == "ALL OFF" -> {
                state.systemOn  = false
                state.hapticsOn = false
                state.sensorsOn = false
                resetAutoSingleCaptureThrottle("all_off")
                InteractionLogger.logStateChange("HAPTICS+SENSORS", false, "VEST")
                notifyStateChanged()
            }
            msg == "SYSTEM_POWER_LOCK_ACTIVE" -> {
                state.systemOn = false
                state.hapticsOn = false
                state.sensorsOn = false
                resetAutoSingleCaptureThrottle("system_power_lock_active")
                InteractionLogger.logStateChange("SYSTEM", false, "VEST")
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
                resetAutoSingleCaptureThrottle("sensors_on", allowImmediateCapture = true)
                InteractionLogger.logStateChange("SENSORS", true, "VEST")
                notifyStateChanged()
            }
            msg == "SENSORS ARE NOW OFF" || msg == "SENSORS OFF" -> {
                state.sensorsOn = false
                resetAutoSingleCaptureThrottle("sensors_off")
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
            resetAutoSingleCaptureThrottle(
                reason = "capture_enabled:$source",
                allowImmediateCapture = true
            )
        } else {
            resetAutoSingleCaptureThrottle(reason = "capture_disabled:$source")
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

    private fun handleGlassesCaptureTrigger() {
        if (!state.systemOn || !state.sensorsOn) {
            resetAutoSingleCaptureThrottle("sensors_or_system_off")
            Log.d(TAG, "GLASSES_CAPTURE ignored while vest sensors/system are off")
            return
        }

        if (!glassesCaptureGateway.isConnected()) {
            resetAutoSingleCaptureThrottle("glasses_disconnected")
            Log.d(TAG, "GLASSES_CAPTURE received while glasses are disconnected")
            return
        }

        if (!glassesCaptureGateway.isCaptureEnabled()) {
            resetAutoSingleCaptureThrottle("capture_disabled")
            Log.d(TAG, "GLASSES_CAPTURE ignored because glasses capture is disabled")
            return
        }

        if (glassesCaptureGateway.isCapturePipelineBusy()) {
            Log.d(TAG, "GLASSES_CAPTURE deferred while prior capture is still processing")
            return
        }

        val now = System.currentTimeMillis()
        val (sent, waitRemainingMs) = synchronized(autoCaptureStateLock) {
            val elapsedMs = now - lastAutoSingleCaptureAtMs
            if (lastAutoSingleCaptureAtMs > 0L && elapsedMs < AUTO_SINGLE_CAPTURE_MIN_INTERVAL_MS) {
                Pair(false, AUTO_SINGLE_CAPTURE_MIN_INTERVAL_MS - elapsedMs)
            } else {
                val dispatched = glassesCaptureGateway.takePhoto()
                if (dispatched) {
                    lastAutoSingleCaptureAtMs = now
                }
                Pair(dispatched, null)
            }
        }

        if (waitRemainingMs != null) {
            Log.d(
                TAG,
                "GLASSES_CAPTURE throttled; next single-photo command allowed in ${waitRemainingMs}ms"
            )
            return
        }

        if (sent) {
            Log.d(TAG, "GLASSES_CAPTURE dispatched single-photo command")
        } else {
            Log.w(TAG, "GLASSES_CAPTURE single-photo command dispatch failed")
        }
    }

    private fun resetAutoSingleCaptureThrottle(
        reason: String,
        allowImmediateCapture: Boolean = false
    ) {
        synchronized(autoCaptureStateLock) {
            if (allowImmediateCapture) {
                lastAutoSingleCaptureAtMs = 0L
            }
        }
        Log.d(
            TAG,
            "Auto single-capture throttle reset: $reason immediate=${if (allowImmediateCapture) 1 else 0}"
        )
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
