package com.circuitsyndicate.findingtheway

import android.content.Context
import android.util.Log

/**
 * Thin adapter between GlassesBleManager and the rest of the app.
 *
 * The glasses now connect via BLE (GlassesBleManager) rather than SPP.
 * All image reassembly and inference runs inside GlassesImagePipeline.
 * This class exists purely to give DeviceManager a consistent interface
 * and to expose connection state to listeners.
 *
 * Note: GlassesBleManager.initialize() is called from FindingTheWayApp.
 * Commands go through GlassesCommandSender → GlassesBleManager.
 */
class GlassesMessageHandler(private val context: Context) {

    companion object { private const val TAG = "GlassesHandler" }

    data class GlassesState(
        var connected: Boolean = false,
        var batteryLevel: Int = -1
    )

    interface GlassesListener {
        fun onStateChanged(state: GlassesState)
        fun onImageSaved(uri: String)
        fun onConnectionChanged(connected: Boolean)
        fun onDetectionSpoken(summary: String)
        fun onBatteryLevelChanged(level: Int) {}
    }

    private val state = GlassesState()
    private val listeners = mutableListOf<GlassesListener>()

    fun addListener(l: GlassesListener) {
        synchronized(listeners) { if (!listeners.contains(l)) listeners.add(l) }
    }
    fun removeListener(l: GlassesListener) {
        synchronized(listeners) { listeners.remove(l) }
    }

    /** Called by GlassesBleManager when BLE connection state changes. */
    fun onConnectionChanged(connected: Boolean) {
        state.connected = connected
        InteractionLogger.logConnection("GLASSES", connected)
        notifyListeners { it.onConnectionChanged(connected) }
        notifyListeners { it.onStateChanged(state.copy()) }
    }

    /** Called by GlassesImagePipeline after a detection is spoken. */
    fun onDetectionSpoken(summary: String) {
        notifyListeners { it.onDetectionSpoken(summary) }
    }

    fun onBatteryLevelChanged(level: Int) {
        val normalized = level.coerceIn(0, 100)
        state.batteryLevel = normalized
        notifyListeners { it.onBatteryLevelChanged(normalized) }
        notifyListeners { it.onStateChanged(state.copy()) }
    }

    fun onBatteryUnavailable() {
        state.batteryLevel = -1
        notifyListeners { it.onStateChanged(state.copy()) }
    }

    /** Called by GlassesImagePipeline after an image is saved. */
    fun onImageSaved(uri: String) {
        notifyListeners { it.onImageSaved(uri) }
    }

    fun getCurrentState(): GlassesState = state.copy()

    fun close() {
        // GlassesBleManager.close() shuts down pipeline + speaker
    }

    private inline fun notifyListeners(action: (GlassesListener) -> Unit) {
        synchronized(listeners) {
            listeners.forEach {
                try { action(it) } catch (e: Exception) { Log.e(TAG, "Listener error", e) }
            }
        }
    }
}
