package com.circuitsyndicate.findingtheway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * DeviceManager owns the VEST connection.
 *
 * The glasses use BLE — managed separately by [GlassesBleManager].
 * isGlassesConnected() delegates to GlassesBleManager so the rest of
 * the app can use a single connection-check entry point.
 *
 * Broadcasts emitted:
 *   "BT_CONNECTION_CHANGED"  → device_type, connected
 *   "BLUETOOTH_CONNECTION_CHANGED" (legacy compat)
 *   "ESP32_STATUS_UPDATE"    → haptics, sensors, system, battery
 *   "BATTERY_UPDATE"         → deviceType, level
 *   "OBJECT_DETECTED"        → object, source
 */
object DeviceManager {

    private const val TAG = "DeviceManager"
    private const val CMD_TURN_HAPTICS_ON = "TURN HAPTICS ON"
    private const val CMD_TURN_SENSORS_ON = "TURN SENSORS ON"
    private const val CMD_TURN_ALL_ON = "TURN ALL ON"
    private const val CMD_TURN_ALL_OFF = "TURN ALL OFF"
    private const val CMD_TURN_SYSTEM_ON = "TURN SYSTEM ON"
    private const val CMD_TURN_SYSTEM_OFF = "TURN SYSTEM OFF"
    private const val CMD_APP_CONNECTED = "APP_CONNECTED"
    private val VEST_BLE_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val VEST_BLE_CHAR_UUID: UUID = UUID.fromString("00002a57-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val VEST_BLE_TARGET_MTU = 185
    private const val EVIDENCE_HEARTBEAT_INTERVAL_MS = 60_000L

    private lateinit var appContext: Context
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val executor = Executors.newCachedThreadPool()
    private val reconnectHandler = Handler(Looper.getMainLooper())

    var vestHandler: VestMessageHandler? = null
        private set

    // GlassesMessageHandler is a state adapter only — actual BLE connection is
    // in GlassesBleManager. This is exposed so activities can register listeners
    // for connection-state callbacks from GlassesBleManager.
    var glassesHandler: GlassesMessageHandler? = null
        private set

    private enum class VestTransport {
        BLE_GATT
    }

    private data class VestConnection(
        val address: String,
        val transport: VestTransport,
        var device: BluetoothDevice? = null,
        var gatt: BluetoothGatt? = null,
        var ioCharacteristic: BluetoothGattCharacteristic? = null,
        val isConnected: AtomicBoolean = AtomicBoolean(false),
        val shouldReconnect: AtomicBoolean = AtomicBoolean(true),
        val deferAppConnectedUntilNotifyEnabled: AtomicBoolean = AtomicBoolean(false)
    )

    private val vestConn = AtomicReference<VestConnection?>()
    private val vestSystemEnabled = AtomicBoolean(false)
    private val vestConnectedSinceMs = AtomicLong(-1L)
    private val evidenceHeartbeatStarted = AtomicBoolean(false)

    private val evidenceHeartbeatRunnable = object : Runnable {
        override fun run() {
            if (::appContext.isInitialized) {
                val mode = AssistiveRuntimeSettings.getMode(appContext).name
                InteractionLogger.logSessionEvidence(
                    source = "ANDROID_RUNTIME",
                    event = "HEARTBEAT",
                    details =
                        "session_elapsed_ms=${InteractionLogger.currentSessionElapsedMs()} " +
                            "vest_connected=${isVestConnected()} " +
                            "glasses_connected=${isGlassesConnected()} " +
                            "mode=$mode"
                )
            }
            reconnectHandler.postDelayed(this, EVIDENCE_HEARTBEAT_INTERVAL_MS)
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val btMgr = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btMgr.adapter
        vestHandler   = VestMessageHandler(appContext)
        glassesHandler = GlassesMessageHandler(appContext)

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FindingTheWay::BtWakeLock")

        startEvidenceHeartbeatIfNeeded()
        InteractionLogger.logSessionEvidence(
            source = "VEST_BLE",
            event = "MANAGER_INITIALIZED",
            details = "bluetooth_adapter_ready=${bluetoothAdapter != null}"
        )
    }

    private fun startEvidenceHeartbeatIfNeeded() {
        if (evidenceHeartbeatStarted.compareAndSet(false, true)) {
            reconnectHandler.postDelayed(evidenceHeartbeatRunnable, EVIDENCE_HEARTBEAT_INTERVAL_MS)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Connect the VEST by MAC address (transport resolved from discovered profile when possible). */
    fun connectVest(address: String) {
        require(::appContext.isInitialized) { "Call initialize() first" }
        if (!hasBluetoothPermission()) { Log.e(TAG, "BLUETOOTH_CONNECT missing"); return }
        InteractionLogger.logSessionEvidence("VEST_BLE", "CONNECT_REQUEST", "address=$address")
        val adapter = bluetoothAdapter ?: return
        try {
            connectVest(adapter.getRemoteDevice(address))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve vest device for $address", e)
        }
    }

    /** Connect the VEST using a discovered BluetoothDevice over BLE GATT. */
    fun connectVest(device: BluetoothDevice) {
        require(::appContext.isInitialized) { "Call initialize() first" }
        if (!hasBluetoothPermission()) { Log.e(TAG, "BLUETOOTH_CONNECT missing"); return }

        val address = device.address ?: return
        InteractionLogger.logSessionEvidence("VEST_BLE", "CONNECT_DEVICE", "address=$address")
        if (BleSessionStatePolicy.shouldBlockVestRoleAssignment(GlassesBleManager.isConnected(address))) {
            Log.w(TAG, "Skipping vest connect for $address because glasses BLE is already connected on that address")
            return
        }

        disconnectVest()

        if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            Log.w(TAG, "Skipping vest connect for $address because the device is Classic-only")
            return
        }

        val conn = VestConnection(
            address = address,
            transport = VestTransport.BLE_GATT,
            device = device
        )
        vestConn.set(conn)
        executor.execute { attemptVestConnect(conn) }
    }

    /** Connect GLASSES via BLE — delegates to GlassesBleManager. */
    fun connectGlasses(address: String) {
        GlassesBleManager.connect(address)
    }

    /** Connect GLASSES via BLE using a discovered BluetoothDevice instance. */
    fun connectGlasses(device: BluetoothDevice) {
        GlassesBleManager.connect(device)
    }

    /** Generic entry point used by BluetoothActivity. */
    fun connectDevice(address: String, type: DeviceType) = when (type) {
        DeviceType.VEST    -> connectVest(address)
        DeviceType.GLASSES -> connectGlasses(address)
    }

    /** Generic entry point using discovered BluetoothDevice metadata. */
    fun connectDevice(device: BluetoothDevice, type: DeviceType) = when (type) {
        DeviceType.VEST    -> connectVest(device)
        DeviceType.GLASSES -> connectGlasses(device)
    }

    fun disconnectVest() {
        val conn = vestConn.getAndSet(null) ?: return
        conn.shouldReconnect.set(false)
        vestSystemEnabled.set(false)
        val wasConnected = conn.isConnected.getAndSet(false)
        val connectedMs = consumeVestConnectedDurationMs()
        closeVestConnection(conn)
        broadcastConnection(DeviceType.VEST, false, 0)
        if (wasConnected) {
            InteractionLogger.logConnection(
                "VEST",
                false,
                "manual_disconnect connected_ms=$connectedMs"
            )
            InteractionLogger.logInterruption("VEST_BLE", "manual_disconnect")
            vestHandler?.handleMessage("ESP_DISCONNECTED")
        }
        if (connections_none_connected() && wakeLock?.isHeld == true) wakeLock?.release()
    }

    fun disconnectGlasses() = GlassesBleManager.disconnect()

    fun disconnectDevice(type: DeviceType) = when (type) {
        DeviceType.VEST    -> disconnectVest()
        DeviceType.GLASSES -> disconnectGlasses()
    }

    fun isVestConnected()    = vestConn.get()?.isConnected?.get() == true
    fun isVestSystemEnabled() = vestSystemEnabled.get()
    fun setVestSystemEnabled(enabled: Boolean) {
        vestSystemEnabled.set(enabled)
    }

    fun isGlassesConnected() = GlassesBleManager.isConnected()
    fun isConnected(type: DeviceType) = when (type) {
        DeviceType.VEST    -> isVestConnected()
        DeviceType.GLASSES -> isGlassesConnected()
    }

    fun sendToVest(message: String): Boolean {
        val conn = vestConn.get() ?: return false
        if (!conn.isConnected.get()) { Log.w(TAG, "Vest not connected"); return false }

        val payload = message.trim()
        if (payload.isEmpty()) return false

        val normalized = normalizedVestCommand(payload)
        if (shouldBlockVestCommand(normalized)) {
            Log.w(TAG, "Blocking vest command while system power is off: $payload")
            return false
        }

        if (normalized == CMD_TURN_ALL_OFF || normalized == CMD_TURN_SYSTEM_OFF) {
            vestSystemEnabled.set(false)
        }
        if (normalized == CMD_TURN_ALL_ON || normalized == CMD_TURN_SYSTEM_ON) {
            vestSystemEnabled.set(true)
        }

        val sent = sendToVestBle(conn, normalized)
        if (sent) InteractionLogger.logCommand(normalized, "APP→VEST")
        return sent
    }

    // ── Internal — vest transport ─────────────────────────────────────────────

    private fun attemptVestConnect(conn: VestConnection) {
        attemptVestConnectBle(conn)
    }

    private fun attemptVestConnectBle(conn: VestConnection) {
        if (!hasBluetoothPermission()) { Log.e(TAG, "BLUETOOTH_CONNECT missing"); return }

        val device = conn.device ?: try {
            bluetoothAdapter?.getRemoteDevice(conn.address)
        } catch (_: Exception) {
            null
        }

        if (device == null) {
            Log.e(TAG, "Unable to resolve BLE vest device for ${conn.address}")
            scheduleVestReconnect(conn)
            return
        }
        conn.device = device

        val callback = createVestGattCallback(conn.address)
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, callback)
        }

        conn.gatt = gatt
        conn.ioCharacteristic = null
        conn.isConnected.set(false)
        InteractionLogger.logSessionEvidence("VEST_BLE", "CONNECT_ATTEMPT", "address=${conn.address}")
        Log.d(TAG, "Connecting vest BLE to ${device.address}")
    }

    private fun createVestGattCallback(address: String) = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val conn = vestConn.get()
            if (conn == null || conn.address != address || conn.transport != VestTransport.BLE_GATT) {
                safeCloseGatt(gatt)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Vest BLE connected — requesting MTU $VEST_BLE_TARGET_MTU")
                if (!hasBluetoothPermission()) return
                val mtuRequested = gatt.requestMtu(VEST_BLE_TARGET_MTU)
                if (!mtuRequested) gatt.discoverServices()
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Vest BLE disconnected (status=$status)")
                handleVestDisconnection(conn, reason = "gatt_state_disconnected_status=$status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!hasBluetoothPermission()) return
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val conn = vestConn.get()
            if (conn == null || conn.address != address || conn.transport != VestTransport.BLE_GATT) {
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Vest BLE service discovery failed: $status")
                handleVestDisconnection(conn, reason = "services_discovered_failed_status=$status")
                return
            }

            val ioCharacteristic = gatt.getService(VEST_BLE_SERVICE_UUID)
                ?.getCharacteristic(VEST_BLE_CHAR_UUID)

            if (ioCharacteristic == null) {
                Log.e(TAG, "Vest BLE characteristic not found (service=$VEST_BLE_SERVICE_UUID char=$VEST_BLE_CHAR_UUID)")
                handleVestDisconnection(conn, reason = "io_characteristic_missing")
                return
            }

            conn.gatt = gatt
            conn.ioCharacteristic = ioCharacteristic
            conn.isConnected.set(true)
            vestSystemEnabled.set(true)
            vestConnectedSinceMs.set(SystemClock.elapsedRealtime())

            acquireWakeLockIfNeeded()
            broadcastConnection(DeviceType.VEST, true, 1)
            InteractionLogger.logConnection(
                "VEST",
                true,
                "gatt_ready address=${conn.address} mtu_target=$VEST_BLE_TARGET_MTU"
            )
            InteractionLogger.logSessionEvidence(
                "VEST_BLE",
                "CONNECTED",
                "address=${conn.address}"
            )
            vestHandler?.handleMessage("ESP_CONNECTED")

            conn.deferAppConnectedUntilNotifyEnabled.set(false)
            val hasClientConfigDescriptor = ioCharacteristic.getDescriptor(CLIENT_CONFIG_UUID) != null
            val notificationsInitStarted = enableVestNotifications(gatt, ioCharacteristic)
            if (!notificationsInitStarted) {
                Log.w(TAG, "Vest BLE notification enable failed; continuing command-only mode")
            }

            if (notificationsInitStarted && hasClientConfigDescriptor) {
                conn.deferAppConnectedUntilNotifyEnabled.set(true)
            } else {
                sendToVest(CMD_APP_CONNECTED)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleIncomingVestPayload(value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingVestPayload(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val conn = vestConn.get()
                if (conn != null && conn.address == address && conn.transport == VestTransport.BLE_GATT) {
                    Log.e(TAG, "Vest BLE write failed: $status")
                    handleVestDisconnection(conn, reason = "write_failed_status=$status")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val conn = vestConn.get()
            if (conn == null || conn.address != address || conn.transport != VestTransport.BLE_GATT) {
                return
            }

            if (descriptor.uuid != CLIENT_CONFIG_UUID) return
            if (!conn.deferAppConnectedUntilNotifyEnabled.getAndSet(false)) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Vest BLE notification descriptor write failed: $status")
            }

            sendToVest(CMD_APP_CONNECTED)
        }
    }

    private fun enableVestNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!hasBluetoothPermission()) return false

        val setOk = gatt.setCharacteristicNotification(characteristic, true)
        if (!setOk) return false

        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleVestDisconnection(conn: VestConnection, reason: String) {
        if (vestConn.get() !== conn) {
            closeVestConnection(conn)
            conn.isConnected.set(false)
            return
        }

        val wasConnected = conn.isConnected.getAndSet(false)
        vestSystemEnabled.set(false)
        val connectedMs = consumeVestConnectedDurationMs()
        closeVestConnection(conn)
        broadcastConnection(DeviceType.VEST, false, 0)
        if (wasConnected) {
            InteractionLogger.logConnection(
                "VEST",
                false,
                "reason=$reason connected_ms=$connectedMs"
            )
            InteractionLogger.logInterruption("VEST_BLE", reason)
            InteractionLogger.logSessionEvidence(
                "VEST_BLE",
                "DISCONNECTED",
                "reason=$reason reconnect_enabled=${conn.shouldReconnect.get()}"
            )
            vestHandler?.handleMessage("ESP_DISCONNECTED")
        }
        if (connections_none_connected()) {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        scheduleVestReconnect(conn)
    }

    private fun consumeVestConnectedDurationMs(): Long {
        val since = vestConnectedSinceMs.getAndSet(-1L)
        if (since <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L)
    }

    private fun scheduleVestReconnect(conn: VestConnection) {
        if (!conn.shouldReconnect.get()) return
        InteractionLogger.logSessionEvidence(
            "VEST_BLE",
            "RECONNECT_SCHEDULED",
            "address=${conn.address} delay_ms=$RECONNECT_DELAY_MS"
        )
        reconnectHandler.postDelayed({
            if (conn.shouldReconnect.get() && vestConn.get() === conn) {
                Log.d(TAG, "Vest reconnecting…")
                InteractionLogger.logSessionEvidence(
                    "VEST_BLE",
                    "RECONNECT_ATTEMPT",
                    "address=${conn.address}"
                )
                executor.execute { attemptVestConnect(conn) }
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun closeVestConnection(conn: VestConnection) {
        closeVestGatt(conn)
    }

    private fun closeVestGatt(conn: VestConnection) {
        val gatt = conn.gatt
        conn.gatt = null
        conn.ioCharacteristic = null

        if (gatt == null) return
        if (hasBluetoothPermission()) {
            try { gatt.disconnect() } catch (_: Exception) {}
            safeCloseGatt(gatt)
        }
    }

    private fun safeCloseGatt(gatt: BluetoothGatt) {
        try { gatt.close() } catch (_: Exception) {}
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(30 * 60 * 1000L)
    }

    private fun sendToVestBle(conn: VestConnection, message: String): Boolean {
        if (!hasBluetoothPermission()) return false

        val gatt = conn.gatt ?: return false
        val characteristic = conn.ioCharacteristic ?: return false
        val payload = message.toByteArray()

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = payload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            Log.e(TAG, "Vest BLE write could not be started")
            InteractionLogger.logSessionEvidence(
                "VEST_BLE",
                "WRITE_START_FAILED",
                "message=$message"
            )
            return false
        }
        return true
    }

    private fun handleIncomingVestPayload(payload: ByteArray) {
        val raw = payload.toString(Charsets.UTF_8)
            .replace("\u0000", "")
            .trim()
        if (raw.isEmpty()) return

        raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { handleIncomingVestText(it) }
    }

    private fun handleIncomingVestText(message: String) {
        Log.d(TAG, "VEST ← $message")
        val evidence = SessionEvidenceParser.parseFirmwareEvidenceLine(message)
        if (evidence != null) {
            InteractionLogger.logSessionEvidence(
                source = "VEST_FIRMWARE",
                event = "REPORT",
                details = evidence.payload
            )
        } else if (message.startsWith("SESSION_EVIDENCE:")) {
            InteractionLogger.logSessionEvidence(
                source = "VEST_FIRMWARE",
                event = "REPORT",
                details = message.removePrefix("SESSION_EVIDENCE:")
            )
        }

        val resetCause = SessionEvidenceParser.parseSessionResetCause(message)
        if (resetCause != null) {
            InteractionLogger.logInterruption(
                source = "VEST_FIRMWARE",
                cause = "SESSION_RESET:$resetCause"
            )
        } else if (message.startsWith("SESSION_RESET:")) {
            InteractionLogger.logInterruption(
                source = "VEST_FIRMWARE",
                cause = message
            )
        }
        vestHandler?.handleMessage(message)
    }

    private fun normalizedVestCommand(message: String): String =
        message.trim().uppercase(Locale.US)

    private fun isSubsystemEnableCommand(command: String): Boolean =
        command == CMD_TURN_HAPTICS_ON || command == CMD_TURN_SENSORS_ON

    private fun shouldBlockVestCommand(command: String): Boolean =
        !vestSystemEnabled.get() && isSubsystemEnableCommand(command)

    private fun connections_none_connected(): Boolean {
        val snapshot = BleSessionStatePolicy.snapshot(
            vestConnected = isVestConnected(),
            glassesConnectionCount = if (isGlassesConnected()) 1 else 0
        )
        return !BleSessionStatePolicy.hasAnyConnection(snapshot)
    }

    // ── Broadcasts ────────────────────────────────────────────────────────────

    private fun broadcastConnection(type: DeviceType, connected: Boolean, connectedCount: Int) {
        val modeName = AssistiveRuntimeSettings.getMode(appContext).name
        listOf("BT_CONNECTION_CHANGED", "BLUETOOTH_CONNECTION_CHANGED").forEach { action ->
            appContext.sendBroadcast(Intent(action).apply {
                putExtra("device_type", type.name)
                putExtra("connected", connected)
                putExtra("connected_count", connectedCount)
                putExtra("assistive_mode", modeName)
            })
        }
    }

    // ── Permission helper ─────────────────────────────────────────────────────

    private fun hasBluetoothPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else true
}
