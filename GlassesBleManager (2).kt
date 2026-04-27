package com.circuitsyndicate.findingtheway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages BLE (GATT) connections to ESP32-class peripherals.
 *
 * The glasses use BLE and are managed separately from the vest BLE session
 * in DeviceManager. This class handles:
 *   • BLE scan + GATT connection
 *   • MTU negotiation (maximise throughput for JPEG chunks)
 *   • GATT characteristic write (commands to ESP32-CAM)
 *   • GATT notification subscribe + receive (binary chunk data from ESP32-CAM)
 *   • Auto-reconnect on drop (5 s delay, same reconnect cadence as DeviceManager)
 *   • Multiple simultaneous BLE sessions (one active write target + additional links)
 *
 * Commands to ESP32-CAM:
 *   GlassesImagePipeline.CMD_SINGLE_PHOTO (0x01)
 *   GlassesImagePipeline.CMD_BURST        (0x04)
 *   GlassesImagePipeline.CMD_BURST_CADENCE_HINT (0x05, optional)
 *
 * Data from ESP32-CAM: binary chunks routed to [GlassesImagePipeline.onBleData].
 *
 * All connection state is broadcast to the app via:
 *   "BT_CONNECTION_CHANGED" → device_type="GLASSES", connected=Boolean
 *
 * Known GATT profiles are tried first. If no known profile matches, this manager
 * falls back to a generic heuristic: first writable characteristic + first
 * notify/indicate characteristic discovered on the peripheral.
 */
object GlassesBleManager {

    private const val TAG = "GlassesBleManager"

    private data class KnownProfile(
        val serviceUuid: UUID,
        val commandCharUuid: UUID,
        val dataCharUuid: UUID
    )

    private data class ResolvedProfile(
        val serviceUuid: UUID?,
        val commandCharacteristic: BluetoothGattCharacteristic?,
        val dataCharacteristic: BluetoothGattCharacteristic?,
        val usedFallback: Boolean,
        val routeNotificationsToPipeline: Boolean
    )

    private data class BleSession(
        val address: String,
        var device: BluetoothDevice? = null,
        var gatt: BluetoothGatt? = null,
        var negotiatedMtu: Int = DEFAULT_MTU,
        var serviceUuid: UUID? = null,
        var commandCharUuid: UUID? = null,
        var dataCharUuid: UUID? = null,
        var routeNotificationsToPipeline: Boolean = false,
        var batteryLevel: Int = -1,
        var isConnected: Boolean = false,
        var connectedSinceElapsedMs: Long = -1L,
        var shouldReconnect: Boolean = true,
        val writeQueue: java.util.ArrayDeque<ByteArray> = java.util.ArrayDeque(),
        var writeInProgress: Boolean = false
    )

    // Known profile set used by firmware variants in this repo lineage.
    private val KNOWN_PROFILES = listOf(
        KnownProfile(
            serviceUuid = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b"),
            commandCharUuid = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8"),
            dataCharUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        ),
        KnownProfile(
            serviceUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef0"),
            commandCharUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef2"),
            dataCharUuid = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        )
    )

    private val NOTIFY_DESC_UUID   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")  // CCCD

    private const val SCAN_TIMEOUT_MS = 15_000L
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val AUTO_CONNECT_MANUAL_DISCONNECT_COOLDOWN_MS = 15_000L
    private const val PREFS_NAME = "glasses_ble_state"
    private const val PREF_LAST_CONNECTED_ADDRESS = "last_connected_address"
    private const val TARGET_MTU = 512  // request large MTU for JPEG throughput
    private const val DEFAULT_MTU = 23
    private val AUTO_CONNECT_FIRMWARE_SERVICE_UUID =
        UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private const val BURST_CADENCE_HINT_VERSION: Byte = 0x01
    private const val BURST_CADENCE_HEALTH_STABLE: Byte = 0x00
    private const val BURST_CADENCE_HEALTH_MODERATE: Byte = 0x01
    private const val BURST_CADENCE_HEALTH_DEGRADED: Byte = 0x02
    private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private lateinit var appContext: Context
    private lateinit var pipeline: GlassesImagePipeline

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanCallback: ScanCallback? = null
    private val isConnected = AtomicBoolean(false)
    private val isScanning = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var connectedDeviceCount: Int = 0
    @Volatile private var activeSessionAddress: String? = null
    @Volatile private var autoConnectSuppressedUntilElapsedMs: Long = 0L

    private val seenScanAddresses = mutableSetOf<String>()
    private val sessionLock = Any()
    private val sessionsByAddress = linkedMapOf<String, BleSession>()

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        appContext = context.applicationContext
        pipeline = GlassesImagePipeline(appContext)
        pipeline.setCaptureGateEnabled(
            GlassesCaptureSettings.isCaptureEnabled(appContext),
            source = "INIT"
        )
        val btMgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btMgr.adapter
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun isConnected(): Boolean = isConnected.get()

    fun isConnected(address: String): Boolean {
        synchronized(sessionLock) {
            return sessionsByAddress[address]?.isConnected == true
        }
    }

    fun negotiatedMtu(): Int {
        synchronized(sessionLock) {
            val active = activeSessionAddress?.let { sessionsByAddress[it] }
            return active?.negotiatedMtu ?: DEFAULT_MTU
        }
    }

    fun connectedCount(): Int = connectedDeviceCount

    fun getBatteryLevel(): Int {
        synchronized(sessionLock) {
            val activeLevel = activeSessionAddress
                ?.let { sessionsByAddress[it]?.batteryLevel }
                ?: -1
            if (activeLevel in 0..100) return activeLevel

            return sessionsByAddress.values
                .firstOrNull { it.isConnected && it.batteryLevel in 0..100 }
                ?.batteryLevel
                ?: -1
        }
    }

    fun isCaptureEnabled(): Boolean {
        if (!::appContext.isInitialized) return true
        return GlassesCaptureSettings.isCaptureEnabled(appContext)
    }

    fun setCaptureEnabled(enabled: Boolean) {
        if (!::appContext.isInitialized) return
        val previous = GlassesCaptureSettings.isCaptureEnabled(appContext)
        GlassesCaptureSettings.setCaptureEnabled(appContext, enabled)
        pipeline.setCaptureGateEnabled(enabled, source = "APP")

        if (previous != enabled) {
            DeviceManager.vestHandler?.onCaptureGateChanged(
                enabled = enabled,
                source = "GLASSES_CAPTURE_TOGGLE"
            )
            syncCaptureGateToFirmware(source = "TOGGLE")
        }
    }

    /** Connect by MAC address (from BluetoothActivity scan). */
    fun connect(address: String) {
        InteractionLogger.logSessionEvidence("GLASSES_BLE", "CONNECT_REQUEST", "address=$address")
        synchronized(sessionLock) {
            val session = sessionsByAddress[address]
            if (session != null) {
                session.shouldReconnect = true
                activeSessionAddress = address
            }
        }
        connectToAddress(address)
    }

    /** Connect directly to a discovered BLE device instance. */
    fun connect(device: BluetoothDevice) {
        val address = device.address ?: return
        InteractionLogger.logSessionEvidence("GLASSES_BLE", "CONNECT_REQUEST", "address=$address")
        synchronized(sessionLock) {
            val session = sessionsByAddress[address]
            if (session != null) {
                session.shouldReconnect = true
                session.device = device
            }
            activeSessionAddress = address
        }
        connectToDevice(device)
    }

    fun disconnect() {
        synchronized(sessionLock) {
            sessionsByAddress.values.forEach { session ->
                if (session.isConnected) {
                    val durationMs = if (session.connectedSinceElapsedMs > 0L) {
                        (SystemClock.elapsedRealtime() - session.connectedSinceElapsedMs)
                            .coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    InteractionLogger.logConnection(
                        "GLASSES",
                        false,
                        "manual_disconnect address=${session.address} connected_ms=$durationMs"
                    )
                    InteractionLogger.logInterruption("GLASSES_BLE", "manual_disconnect")
                }
                session.shouldReconnect = false
                clearWriteQueueLocked(session)
                closeGattLocked(session)
                session.isConnected = false
                session.connectedSinceElapsedMs = -1L
                session.negotiatedMtu = DEFAULT_MTU
                session.serviceUuid = null
                session.commandCharUuid = null
                session.dataCharUuid = null
                session.routeNotificationsToPipeline = false
                session.batteryLevel = -1
            }
            sessionsByAddress.clear()
            activeSessionAddress = null
            refreshAggregateConnectionStateLocked()
            publishBatterySnapshotLocked()
        }
    }

    /** Suppress scan-driven auto-connect after an explicit user disconnect action. */
    fun suppressAutoConnectForManualDisconnect(
        durationMs: Long = AUTO_CONNECT_MANUAL_DISCONNECT_COOLDOWN_MS
    ) {
        val clampedDurationMs = durationMs.coerceAtLeast(0L)
        autoConnectSuppressedUntilElapsedMs =
            SystemClock.elapsedRealtime() + clampedDurationMs
        InteractionLogger.logSessionEvidence(
            "GLASSES_BLE",
            "AUTO_CONNECT_SUPPRESSED",
            "reason=manual_disconnect duration_ms=$clampedDurationMs"
        )
    }

    fun isAutoConnectSuppressed(): Boolean {
        return getAutoConnectSuppressionRemainingMs() > 0L
    }

    fun getAutoConnectSuppressionRemainingMs(): Long {
        return (autoConnectSuppressedUntilElapsedMs - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
    }

    fun getLastSuccessfulAddress(): String? {
        if (!::appContext.isInitialized) return null

        val stored = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_CONNECTED_ADDRESS, null)
            ?.trim()
            ?: return null

        return if (BluetoothAdapter.checkBluetoothAddress(stored)) stored else null
    }

    /** Write CMD_SINGLE_PHOTO — request one photo + detection run. */
    fun takePhoto(): Boolean {
        if (!isCaptureEnabled()) {
            Log.i(TAG, "Capture gate blocked single-photo command")
            return false
        }
        pipeline.prepareSinglePhoto()
        return writeCommand(GlassesImagePipeline.CMD_SINGLE_PHOTO)
    }

    /** Write CMD_BURST — request burst capture + detection run. */
    fun takeBurst(): Boolean {
        return takeBurst(
            recommendedInterFrameDelayMs = null,
            cadenceHealth = null
        )
    }

    /**
     * Write CMD_BURST and optionally append a cadence hint payload.
     *
     * Compatibility contract:
     * - The base burst command is always sent first.
     * - Cadence hint is best-effort and optional for firmware to consume.
     * - Failure to enqueue the hint never cancels a queued burst command.
     */
    fun takeBurst(
        recommendedInterFrameDelayMs: Long?,
        cadenceHealth: GlassesImagePipeline.BurstCadenceHealth?
    ): Boolean {
        if (!isCaptureEnabled()) {
            Log.i(TAG, "Capture gate blocked burst command")
            return false
        }
        pipeline.prepareBurst()
        val burstQueued = writeCommand(GlassesImagePipeline.CMD_BURST)
        if (!burstQueued) return false

        val cadenceHintPayload = buildBurstCadenceHintPayload(
            recommendedInterFrameDelayMs = recommendedInterFrameDelayMs,
            cadenceHealth = cadenceHealth
        )
        if (cadenceHintPayload != null) {
            val hintQueued = writePayload(cadenceHintPayload)
            if (hintQueued) {
                Log.i(
                    TAG,
                    "Queued burst cadence hint delayMs=$recommendedInterFrameDelayMs health=${cadenceHealth?.name ?: "STABLE"}"
                )
            } else {
                Log.w(TAG, "Burst command queued but cadence hint enqueue failed")
            }
        }
        return true
    }

    /**
     * Force-stop in-flight capture/transmission work and queue a fresh burst request.
     * This drops queued writes and invalidates active pipeline reassembly before
     * issuing CMD_CAPTURE_STOP followed by CMD_BURST.
     */
    fun interruptAndTakeBurst(
        recommendedInterFrameDelayMs: Long?,
        cadenceHealth: GlassesImagePipeline.BurstCadenceHealth?
    ): Boolean {
        if (!isCaptureEnabled()) {
            Log.i(TAG, "Capture gate blocked burst restart command")
            return false
        }

        pipeline.abortInFlightCapture(reason = "BURST_RESTART")
        synchronized(sessionLock) {
            sessionsByAddress.values.forEach { session ->
                clearWriteQueueLocked(session)
            }
        }

        val stopQueued = writePayload(byteArrayOf(GlassesImagePipeline.CMD_CAPTURE_STOP))
        InteractionLogger.logCommand("CMD_CAPTURE_STOP", "APP→GLASSES")
        if (!stopQueued) {
            Log.w(TAG, "Burst restart requested but capture stop command was not queued")
        }

        return takeBurst(
            recommendedInterFrameDelayMs = recommendedInterFrameDelayMs,
            cadenceHealth = cadenceHealth
        )
    }

    /** Write an arbitrary payload to the command characteristic. */
    fun writePayload(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false

        val captureCommand = captureGateCommandName(payload)
        if (captureCommand != null && !isCaptureEnabled()) {
            Log.i(TAG, "Capture gate blocked raw payload command $captureCommand")
            InteractionLogger.log(
                "COMMAND_BLOCKED",
                "APP→GLASSES",
                "$captureCommand blocked by capture gate"
            )
            return false
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false

        synchronized(sessionLock) {
            val session = selectWritableSessionLocked() ?: run {
                Log.w(TAG, "Not connected to a writable BLE device")
                return false
            }

            val maxChunk = (session.negotiatedMtu - 3).coerceAtLeast(20)
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + maxChunk, payload.size)
                session.writeQueue.addLast(payload.copyOfRange(offset, end))
                offset = end
            }

            if (!session.writeInProgress) {
                return writeNextChunkLocked(session)
            }
        }
        return true
    }

    private fun captureGateCommandName(payload: ByteArray): String? {
        return when (payload.firstOrNull()) {
            GlassesImagePipeline.CMD_SINGLE_PHOTO -> "CMD_SINGLE_PHOTO"
            GlassesImagePipeline.CMD_BURST -> "CMD_BURST"
            else -> null
        }
    }

    /** Expose pipeline so StatusActivity can show inference readiness. */
    fun getPipeline(): GlassesImagePipeline = pipeline

    fun stopSpeech() {
        if (::pipeline.isInitialized) {
            pipeline.stopSpeech()
        }
    }

    // ── BLE scan ──────────────────────────────────────────────────────────────

    fun startScan(onFound: (BluetoothDevice, String) -> Unit) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth not enabled — cannot BLE scan")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        if (isScanning.get()) stopScan()

        isScanning.set(true)
        seenScanAddresses.clear()
        var cooldownLoggedForScan = false

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address ?: return
                if (!seenScanAddresses.add(address)) return
                val name = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    result.device.name ?: result.scanRecord?.deviceName
                } else {
                    result.scanRecord?.deviceName
                } ?: "ESP32 ($address)"
                onFound(result.device, name)

                if (shouldAutoConnectFromScanResult(result, address)) {
                    InteractionLogger.logSessionEvidence(
                        "GLASSES_BLE",
                        "AUTO_CONNECT_TRIGGER",
                        "address=$address service=$AUTO_CONNECT_FIRMWARE_SERVICE_UUID"
                    )
                    connect(result.device)
                    return
                }

                if (!cooldownLoggedForScan && hasAutoConnectFirmwareSignature(result)) {
                    val remainingMs = autoConnectSuppressedUntilElapsedMs -
                        SystemClock.elapsedRealtime()
                    if (remainingMs > 0L) {
                        cooldownLoggedForScan = true
                        InteractionLogger.logSessionEvidence(
                            "GLASSES_BLE",
                            "AUTO_CONNECT_COOLDOWN_ACTIVE",
                            "address=$address remaining_ms=$remainingMs"
                        )
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning.set(false)
                scanCallback = null
                Log.e(TAG, "BLE scan failed: $errorCode")
            }
        }
        scanCallback = cb

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, cb)
        mainHandler.postDelayed({
            if (isScanning.get()) stopScan()
        }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        val cb = scanCallback
        isScanning.set(false)
        scanCallback = null
        if (cb != null && hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
        }
    }

    private fun hasAutoConnectFirmwareSignature(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false

        val advertisedServices = record.serviceUuids
        if (advertisedServices != null && advertisedServices.any {
                it.uuid == AUTO_CONNECT_FIRMWARE_SERVICE_UUID
            }) {
            return true
        }

        val serviceData = record.serviceData
        return serviceData != null && serviceData.keys.any {
            it.uuid == AUTO_CONNECT_FIRMWARE_SERVICE_UUID
        }
    }

    private fun shouldAutoConnectFromScanResult(result: ScanResult, address: String): Boolean {
        if (!hasAutoConnectFirmwareSignature(result)) return false

        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (nowElapsedMs < autoConnectSuppressedUntilElapsedMs) return false

        synchronized(sessionLock) {
            val existing = sessionsByAddress[address]
            if (existing?.isConnected == true) return false
            if (existing?.gatt != null) return false

            val hasActiveOrConnectingSession = sessionsByAddress.values.any { session ->
                session.isConnected || session.gatt != null
            }
            if (hasActiveOrConnectingSession) return false
        }

        return true
    }

    // ── GATT connection ───────────────────────────────────────────────────────

    private fun connectToAddress(address: String) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val adapter = bluetoothAdapter ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            connectToDevice(device)
        } catch (e: Exception) {
            Log.e(TAG, "BLE connect error: ${e.message}")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        stopScan()
        val address = device.address ?: run {
            Log.e(TAG, "Cannot connect BLE device with null address")
            return
        }

        synchronized(sessionLock) {
            val existing = sessionsByAddress[address]
            if (existing?.isConnected == true && existing.gatt != null) {
                activeSessionAddress = address
                Log.d(TAG, "BLE already connected to $address; active session switched")
                InteractionLogger.logSessionEvidence(
                    "GLASSES_BLE",
                    "ACTIVE_SESSION_SWITCH",
                    "address=$address"
                )
                return
            }

            val session = existing ?: BleSession(address = address)
            session.device = device
            session.shouldReconnect = true
            session.negotiatedMtu = DEFAULT_MTU
            session.isConnected = false
            clearWriteQueueLocked(session)
            closeGattLocked(session)

            val g = device.connectGatt(
                appContext,
                false,
                createGattCallback(address),
                BluetoothDevice.TRANSPORT_LE
            )
            session.gatt = g

            sessionsByAddress[address] = session
            activeSessionAddress = address
            refreshAggregateConnectionStateLocked()
        }

        InteractionLogger.logSessionEvidence("GLASSES_BLE", "CONNECT_ATTEMPT", "address=$address")
        Log.d(TAG, "Connecting BLE to $address")
    }

    private fun createGattCallback(address: String) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "BLE connected — requesting MTU $TARGET_MTU")
                if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    g.requestMtu(TARGET_MTU)
                } else {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "BLE disconnected (status=$status)")
                var shouldReconnect = false
                var wasConnected = false
                var connectedDurationMs = 0L
                synchronized(sessionLock) {
                    val session = sessionsByAddress[address]
                    if (session != null && (session.gatt == null || session.gatt === g)) {
                        wasConnected = session.isConnected
                        connectedDurationMs = if (session.connectedSinceElapsedMs > 0L) {
                            (SystemClock.elapsedRealtime() - session.connectedSinceElapsedMs)
                                .coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        session.negotiatedMtu = DEFAULT_MTU
                        session.isConnected = false
                        session.connectedSinceElapsedMs = -1L
                        clearWriteQueueLocked(session)
                        session.serviceUuid = null
                        session.commandCharUuid = null
                        session.dataCharUuid = null
                        session.routeNotificationsToPipeline = false
                        session.batteryLevel = -1
                        if (session.gatt === g) session.gatt = null
                        shouldReconnect = session.shouldReconnect
                    }
                    refreshAggregateConnectionStateLocked()
                    publishBatterySnapshotLocked()
                }

                if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    try { g.close() } catch (_: Exception) {}
                }

                if (wasConnected) {
                    InteractionLogger.logConnection(
                        "GLASSES",
                        false,
                        "reason=gatt_state_disconnected_status=$status address=$address connected_ms=$connectedDurationMs"
                    )
                    InteractionLogger.logInterruption(
                        "GLASSES_BLE",
                        "gatt_state_disconnected_status=$status address=$address"
                    )
                    InteractionLogger.logSessionEvidence(
                        "GLASSES_BLE",
                        "DISCONNECTED",
                        "address=$address status=$status reconnect_enabled=$shouldReconnect"
                    )
                }

                if (shouldReconnect) scheduleReconnect(address)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(sessionLock) {
                val session = sessionsByAddress[address]
                if (session != null && (session.gatt == null || session.gatt === g)) {
                    session.negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
                }
            }

            Log.d(TAG, "MTU=$mtu, discovering services")
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status for $address")
                InteractionLogger.logSessionEvidence(
                    "GLASSES_BLE",
                    "SERVICE_DISCOVERY_FAILED",
                    "address=$address status=$status"
                )
                return
            }

            val resolved = resolveProfile(g)

            synchronized(sessionLock) {
                val session = sessionsByAddress[address] ?: return
                if (session.gatt != null && session.gatt !== g) return

                session.serviceUuid = resolved?.serviceUuid
                session.commandCharUuid = resolved?.commandCharacteristic?.uuid
                session.dataCharUuid = resolved?.dataCharacteristic?.uuid
                session.routeNotificationsToPipeline = resolved?.routeNotificationsToPipeline == true
                session.isConnected = true
                if (session.connectedSinceElapsedMs <= 0L) {
                    session.connectedSinceElapsedMs = SystemClock.elapsedRealtime()
                }

                refreshAggregateConnectionStateLocked()
            }

            persistLastSuccessfulAddress(address)

            InteractionLogger.logConnection(
                "GLASSES",
                true,
                "address=$address service=${resolved?.serviceUuid ?: "none"} fallback=${resolved?.usedFallback == true}"
            )
            InteractionLogger.logSessionEvidence(
                "GLASSES_BLE",
                "CONNECTED",
                "address=$address profile=${resolved?.serviceUuid ?: "none"}"
            )

            if (resolved == null) {
                Log.w(TAG, "No writable/notify characteristic found on $address; keeping generic link connected")
                dumpDiscoveredServices(g)
                return
            }

            val dataChar = resolved.dataCharacteristic
            val writeChar = resolved.commandCharacteristic

            if (resolved.usedFallback) {
                Log.w(
                    TAG,
                    "Using generic fallback profile for $address service=${resolved.serviceUuid} " +
                        "write=${writeChar?.uuid} notify=${dataChar?.uuid}"
                )
            } else {
                Log.d(
                    TAG,
                    "Using known profile for $address service=${resolved.serviceUuid} " +
                        "write=${writeChar?.uuid} notify=${dataChar?.uuid}"
                )
            }

            if (dataChar != null && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                val enabled = enableCharacteristicNotifications(g, dataChar)
                if (!enabled) {
                    Log.w(TAG, "Could not enable notifications for data characteristic ${dataChar.uuid} on $address")
                }
            } else {
                Log.d(TAG, "No notify characteristic for $address; connection stays command-only")
            }

            val batteryChar = g.getService(BATTERY_SERVICE_UUID)
                ?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
            if (batteryChar != null && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                enableCharacteristicNotifications(g, batteryChar)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.readCharacteristic(batteryChar)
                } else {
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(batteryChar)
                }
            }

            syncCaptureGateToFirmware(source = "CONNECTED")

            Log.d(TAG, "BLE ready on $address")
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == NOTIFY_DESC_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed on $address: $status")
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                handleBatteryValue(address, characteristic.value)
                return
            }

            val value = characteristic.value ?: return
            val shouldRoute = synchronized(sessionLock) {
                val session = sessionsByAddress[address] ?: return@synchronized false
                if (session.gatt != null && session.gatt !== g) return@synchronized false
                val expected = session.dataCharUuid
                expected != null && expected == characteristic.uuid && session.routeNotificationsToPipeline
            }

            if (shouldRoute) {
                pipeline.onBleData(value)
            }
        }

        // API 33+ override
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                handleBatteryValue(address, value)
                return
            }

            val shouldRoute = synchronized(sessionLock) {
                val session = sessionsByAddress[address] ?: return@synchronized false
                if (session.gatt != null && session.gatt !== g) return@synchronized false
                val expected = session.dataCharUuid
                expected != null && expected == characteristic.uuid && session.routeNotificationsToPipeline
            }

            if (shouldRoute) {
                pipeline.onBleData(value)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                handleBatteryValue(address, characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                handleBatteryValue(address, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val shouldHandle = synchronized(sessionLock) {
                val session = sessionsByAddress[address] ?: return@synchronized false
                if (session.gatt != null && session.gatt !== g) return@synchronized false
                val expected = session.commandCharUuid
                expected == null || expected == characteristic.uuid
            }

            if (shouldHandle) {
                handleWriteCallback(address, status)
            }
        }
    }

    // ── Command write ─────────────────────────────────────────────────────────

    private fun writeCommand(cmd: Byte): Boolean = writePayload(byteArrayOf(cmd))

    private fun syncCaptureGateToFirmware(source: String) {
        if (!::appContext.isInitialized) return

        val command = if (isCaptureEnabled()) {
            GlassesImagePipeline.CMD_CAPTURE_ENABLE
        } else {
            GlassesImagePipeline.CMD_CAPTURE_STOP
        }
        val commandLabel = if (command == GlassesImagePipeline.CMD_CAPTURE_ENABLE) {
            "CMD_CAPTURE_ENABLE"
        } else {
            "CMD_CAPTURE_STOP"
        }

        val sent = writePayload(byteArrayOf(command))
        InteractionLogger.log(
            "CAPTURE_GATE_SYNC",
            "APP→GLASSES",
            "source=$source command=$commandLabel sent=$sent"
        )
        if (!sent) {
            Log.w(TAG, "Capture gate sync command $commandLabel was not sent (source=$source)")
        }
    }

    private fun buildBurstCadenceHintPayload(
        recommendedInterFrameDelayMs: Long?,
        cadenceHealth: GlassesImagePipeline.BurstCadenceHealth?
    ): ByteArray? {
        val delayMs = recommendedInterFrameDelayMs ?: return null
        val clampedDelayMs = delayMs.coerceIn(0L, 0xFFFFL).toInt()
        val healthCode = when (cadenceHealth ?: GlassesImagePipeline.BurstCadenceHealth.STABLE) {
            GlassesImagePipeline.BurstCadenceHealth.STABLE -> BURST_CADENCE_HEALTH_STABLE
            GlassesImagePipeline.BurstCadenceHealth.MODERATE -> BURST_CADENCE_HEALTH_MODERATE
            GlassesImagePipeline.BurstCadenceHealth.DEGRADED -> BURST_CADENCE_HEALTH_DEGRADED
        }

        // Payload format: [cmd, version, delayMs_lo, delayMs_hi, healthCode]
        return byteArrayOf(
            GlassesImagePipeline.CMD_BURST_CADENCE_HINT,
            BURST_CADENCE_HINT_VERSION,
            (clampedDelayMs and 0xFF).toByte(),
            ((clampedDelayMs shr 8) and 0xFF).toByte(),
            healthCode
        )
    }

    private fun writeNextChunkLocked(session: BleSession): Boolean {
        val g = session.gatt ?: run {
            clearWriteQueueLocked(session)
            return false
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            clearWriteQueueLocked(session)
            return false
        }

        val char = resolveWritableCharacteristic(g, session) ?: run {
            Log.e(TAG, "Writable characteristic not found for ${session.address}")
            clearWriteQueueLocked(session)
            return false
        }

        session.commandCharUuid = char.uuid
        if (session.serviceUuid == null) {
            session.serviceUuid = findServiceUuidForCharacteristic(g, char.uuid)
        }

        val chunk = session.writeQueue.pollFirst() ?: run {
            session.writeInProgress = false
            return true
        }

        val supportsWrite = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val supportsWriteNoResponse =
            (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        val writeType = when {
            supportsWrite -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            supportsWriteNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                char,
                chunk,
                writeType
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
        }

        if (!started) {
            Log.e(TAG, "Characteristic write could not be started for ${session.address}")
            clearWriteQueueLocked(session)
            return false
        }

        session.writeInProgress = true
        return true
    }

    private fun handleWriteCallback(address: String, status: Int) {
        synchronized(sessionLock) {
            val session = sessionsByAddress[address] ?: return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed on $address: $status")
                clearWriteQueueLocked(session)
                return
            }

            if (session.writeQueue.isEmpty()) {
                session.writeInProgress = false
                return
            }

            if (!writeNextChunkLocked(session)) {
                Log.e(TAG, "Failed to continue queued write on $address")
            }
        }
    }

    private fun clearWriteQueueLocked(session: BleSession) {
        session.writeQueue.clear()
        session.writeInProgress = false
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private fun scheduleReconnect(address: String) {
        InteractionLogger.logSessionEvidence(
            "GLASSES_BLE",
            "RECONNECT_SCHEDULED",
            "address=$address delay_ms=$RECONNECT_DELAY_MS"
        )
        mainHandler.postDelayed({
            val device = synchronized(sessionLock) {
                val session = sessionsByAddress[address] ?: return@synchronized null
                if (!session.shouldReconnect) return@synchronized null
                if (session.gatt != null) return@synchronized null
                session.device
            }

            if (device != null) {
                Log.d(TAG, "BLE reconnecting to $address…")
                InteractionLogger.logSessionEvidence(
                    "GLASSES_BLE",
                    "RECONNECT_ATTEMPT",
                    "address=$address"
                )
                connectToDevice(device)
            }
        }, RECONNECT_DELAY_MS)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private fun closeGattLocked(session: BleSession) {
        val g = session.gatt
        session.gatt = null

        if (g == null) return

        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
    }

    fun close() {
        disconnect()
        stopScan()
        pipeline.close()
    }

    private fun selectWritableSessionLocked(): BleSession? {
        val active = activeSessionAddress?.let { sessionsByAddress[it] }
        if (active != null && active.isConnected && active.gatt != null) {
            if (resolveWritableCharacteristic(active.gatt!!, active) != null) {
                return active
            }
        }

        val fallback = sessionsByAddress.values.firstOrNull { session ->
            val g = session.gatt ?: return@firstOrNull false
            session.isConnected && resolveWritableCharacteristic(g, session) != null
        }

        if (fallback != null) {
            activeSessionAddress = fallback.address
        }

        return fallback
    }

    private fun resolveWritableCharacteristic(
        g: BluetoothGatt,
        session: BleSession
    ): BluetoothGattCharacteristic? {
        val serviceUuid = session.serviceUuid
        val commandUuid = session.commandCharUuid

        if (serviceUuid != null && commandUuid != null) {
            val known = g.getService(serviceUuid)?.getCharacteristic(commandUuid)
            if (known != null && isWriteCapable(known)) return known
        }

        if (commandUuid != null) {
            for (service in g.services) {
                val candidate = service.getCharacteristic(commandUuid)
                if (candidate != null && isWriteCapable(candidate)) return candidate
            }
        }

        for (service in g.services) {
            val writable = service.characteristics.firstOrNull { isWriteCapable(it) }
            if (writable != null) return writable
        }

        return null
    }

    private fun resolveProfile(g: BluetoothGatt): ResolvedProfile? {
        for (profile in KNOWN_PROFILES) {
            val service = g.getService(profile.serviceUuid) ?: continue
            val writeChar = service.getCharacteristic(profile.commandCharUuid)
            val dataChar = service.getCharacteristic(profile.dataCharUuid)
            if (writeChar != null || dataChar != null) {
                return ResolvedProfile(
                    serviceUuid = service.uuid,
                    commandCharacteristic = writeChar,
                    dataCharacteristic = dataChar,
                    usedFallback = false,
                    routeNotificationsToPipeline = dataChar != null
                )
            }
        }

        var fallbackWrite: Pair<UUID, BluetoothGattCharacteristic>? = null
        var fallbackNotify: Pair<UUID, BluetoothGattCharacteristic>? = null

        for (service in g.services) {
            val chars = service.characteristics
            val notify = chars.firstOrNull { isNotifyCapable(it) }
            val write = chars.firstOrNull { isWriteCapable(it) }

            if (notify != null && write != null) {
                val writeChoice = if (write.uuid != notify.uuid) {
                    write
                } else {
                    chars.firstOrNull { isWriteCapable(it) && it.uuid != notify.uuid } ?: write
                }

                return ResolvedProfile(
                    serviceUuid = service.uuid,
                    commandCharacteristic = writeChoice,
                    dataCharacteristic = notify,
                    usedFallback = true,
                    routeNotificationsToPipeline = false
                )
            }

            if (fallbackWrite == null && write != null) {
                fallbackWrite = service.uuid to write
            }
            if (fallbackNotify == null && notify != null) {
                fallbackNotify = service.uuid to notify
            }
        }

        val serviceUuid = fallbackWrite?.first ?: fallbackNotify?.first
        if (serviceUuid != null) {
            return ResolvedProfile(
                serviceUuid = serviceUuid,
                commandCharacteristic = fallbackWrite?.second,
                dataCharacteristic = fallbackNotify?.second,
                usedFallback = true,
                routeNotificationsToPipeline = false
            )
        }

        return null
    }

    private fun findServiceUuidForCharacteristic(g: BluetoothGatt, charUuid: UUID): UUID? {
        for (service in g.services) {
            if (service.getCharacteristic(charUuid) != null) {
                return service.uuid
            }
        }
        return null
    }

    private fun isWriteCapable(characteristic: BluetoothGattCharacteristic): Boolean {
        val p = characteristic.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun isNotifyCapable(characteristic: BluetoothGattCharacteristic): Boolean {
        val p = characteristic.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun dumpDiscoveredServices(g: BluetoothGatt) {
        val details = buildString {
            g.services.forEach { service: BluetoothGattService ->
                append("service=")
                append(service.uuid)
                append(' ')
                append('[')
                append(
                    service.characteristics.joinToString(",") { ch ->
                        "${ch.uuid}|props=0x${ch.properties.toString(16)}"
                    }
                )
                append(']')
                append(';')
            }
        }
        Log.d(TAG, "Discovered services for diagnostics: $details")
    }

    private fun enableCharacteristicNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val enabled = g.setCharacteristicNotification(characteristic, true)
        if (!enabled) return false

        val desc = characteristic.getDescriptor(NOTIFY_DESC_UUID) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
    }

    private fun handleBatteryValue(address: String, value: ByteArray?) {
        val raw = value?.firstOrNull()?.toInt()?.and(0xFF) ?: return
        val level = raw.coerceIn(0, 100)

        synchronized(sessionLock) {
            val session = sessionsByAddress[address] ?: return
            session.batteryLevel = level
            if (!session.isConnected) return
            if (activeSessionAddress == null) activeSessionAddress = address
            publishBatterySnapshotLocked()
        }
    }

    private fun persistLastSuccessfulAddress(address: String) {
        if (!::appContext.isInitialized) return
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return

        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_CONNECTED_ADDRESS, address)
            .apply()
    }

    private fun publishBatterySnapshotLocked() {
        val batteryLevel = activeSessionAddress
            ?.let { sessionsByAddress[it]?.batteryLevel }
            ?.takeIf { it in 0..100 }
            ?: sessionsByAddress.values
                .firstOrNull { it.isConnected && it.batteryLevel in 0..100 }
                ?.batteryLevel
            ?: -1

        mainHandler.post {
            appContext.sendBroadcast(Intent("BATTERY_UPDATE").apply {
                putExtra("deviceType", DeviceType.GLASSES.name)
                putExtra("level", batteryLevel)
            })

            if (batteryLevel in 0..100) {
                InteractionLogger.logBattery("GLASSES", batteryLevel)
                DeviceManager.glassesHandler?.onBatteryLevelChanged(batteryLevel)
            } else {
                DeviceManager.glassesHandler?.onBatteryUnavailable()
            }
        }
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private fun refreshAggregateConnectionStateLocked() {
        val count = sessionsByAddress.values.count { it.isConnected }
        val connected = count > 0
        val previousConnected = isConnected.getAndSet(connected)
        val previousCount = connectedDeviceCount
        connectedDeviceCount = count

        if (previousConnected == connected && previousCount == count) return

        val activeAddressSnapshot = activeSessionAddress
        mainHandler.post {
            broadcastConnection(connected, count, activeAddressSnapshot)
        }
    }

    private fun broadcastConnection(connected: Boolean, count: Int, activeAddress: String?) {
        val modeName = AssistiveRuntimeSettings.getMode(appContext).name
        appContext.sendBroadcast(Intent("BT_CONNECTION_CHANGED").apply {
            putExtra("device_type", DeviceType.GLASSES.name)
            putExtra("connected", connected)
            putExtra("connected_count", count)
            putExtra("active_address", activeAddress)
            putExtra("assistive_mode", modeName)
        })
        appContext.sendBroadcast(Intent("BLUETOOTH_CONNECTION_CHANGED").apply {
            putExtra("device_type", DeviceType.GLASSES.name)
            putExtra("connected", connected)
            putExtra("connected_count", count)
            putExtra("active_address", activeAddress)
            putExtra("assistive_mode", modeName)
        })
    }

    // ── Permission helper ─────────────────────────────────────────────────────

    private fun hasPermission(permission: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        else true
}
