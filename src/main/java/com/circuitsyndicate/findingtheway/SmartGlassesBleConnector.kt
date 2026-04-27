package com.circuitsyndicate.findingtheway

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BLE helper for OmiGlass smart glasses.
 *
 * This class handles scanning, connecting, and moving bytes over BLE.
 * The caller passes a [ConnectionListener] to react to connection events
 * and incoming data.
 *
 * Quick usage:
 * ```
 * val connector = SmartGlassesBleConnector(
 *     context = this,
 *     config = SmartGlassesBleConnector.Config(),
 *     listener = myListener
 * )
 * connector.startScan()
 * connector.connect()
 * connector.writeCommand(0x01)
 * connector.disconnect()
 * ```
 *
 * All listener callbacks run on the main (UI) thread.
 */
class SmartGlassesBleConnector(
    private val context: Context,
    private val config: Config = Config(),
    private val listener: ConnectionListener
) {

    // --- Config ---

    /**
        * BLE settings you can tweak.
     *
        * @param deviceName Name to match while scanning.
        *                   Set this to null if you want to match by service UUID instead.
        * @param serviceUuid Primary GATT service UUID on the glasses.
        * @param dataCharUuid Characteristic used for notifications (incoming data).
        * @param controlCharUuid Characteristic used for writes (outgoing commands).
        * @param cccdUuid CCCD descriptor UUID (standard BLE value).
        * @param requestedMtu MTU size to request after connection.
        * @param scanTimeoutMs Scan timeout in milliseconds.
     */
    data class Config(
        val deviceName: String? = "ESP32-CAM",
        val serviceUuid: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0"),
        val dataCharUuid: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1"),
        val controlCharUuid: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef2"),
        val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
        val requestedMtu: Int = 517,
        val scanTimeoutMs: Long = 15_000
    )

    // --- Listener ---

    /**
     * BLE callback interface.
     * Every callback is posted to the main thread.
     */
    interface ConnectionListener {
        /** Called when we find a matching device. */
        fun onDeviceFound(device: BluetoothDevice, name: String)

        /** Called after GATT connects, before service discovery is done. */
        fun onConnecting()

        /** Called when services are ready and notifications are enabled. */
        fun onConnected(negotiatedMtu: Int)

        /** Called when disconnected (expected or unexpected). */
        fun onDisconnected()

        /** Called when data comes in from the data characteristic. */
        fun onDataReceived(data: ByteArray)

        /** Optional status text for logs or UI labels. */
        fun onStatusMessage(message: String)

        /** Called on errors. */
        fun onError(message: String)
    }

    // --- State ---

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    val isConnected: Boolean
        get() = bluetoothGatt != null && controlCharacteristic != null

    val currentMtu: Int
        get() = negotiatedMtu

    val foundDevice: BluetoothDevice?
        get() = targetDevice

    // --- Public API ---

    /**
        * Start scanning for a BLE device.
     *
        * If [Config.deviceName] is set, we match that name.
        * Otherwise, we match by [Config.serviceUuid].
        *
        * Scan stops automatically after [Config.scanTimeoutMs] or when a match is found.
     */
    fun startScan() {
        if (isScanning) return
        isScanning = true

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            postOnMain { listener.onError("BLE not available on this device") }
            isScanning = false
            return
        }

        targetDevice = null
        postOnMain { listener.onStatusMessage("Scanning...") }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = try { device.name } catch (_: SecurityException) { null } ?: return

                val match = if (config.deviceName != null) {
                    name == config.deviceName
                } else {
                    // No name filter: match devices advertising the service UUID.
                    result.scanRecord?.serviceUuids?.any { it.uuid == config.serviceUuid } == true
                }

                if (match) {
                    targetDevice = device
                    isScanning = false
                    try { scanner.stopScan(this) } catch (_: SecurityException) {}
                    postOnMain { listener.onDeviceFound(device, name) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                postOnMain { listener.onError("Scan failed (error $errorCode)") }
            }
        }
        scanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)

            // Auto-stop scanning after the timeout window.
            Handler(Looper.getMainLooper()).postDelayed({
                if (isScanning) {
                    try { scanner.stopScan(callback) } catch (_: Exception) {}
                    isScanning = false
                    if (targetDevice == null) {
                        postOnMain {
                            listener.onStatusMessage(
                                "${config.deviceName ?: "Smart glasses"} not found"
                            )
                        }
                    }
                }
            }, config.scanTimeoutMs)
        } catch (e: SecurityException) {
            isScanning = false
            postOnMain { listener.onError("BLE permission denied") }
        }
    }

    /** Stop a running scan. */
    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let {
            try { scanner.stopScan(it) } catch (_: Exception) {}
        }
        isScanning = false
    }

    /**
        * Connect to a device found by [startScan].
        * You can also pass a [device] directly.
     */
    fun connect(device: BluetoothDevice? = null) {
        val dev = device ?: targetDevice
        if (dev == null) {
            postOnMain { listener.onError("No device to connect to – run startScan() first") }
            return
        }
        targetDevice = dev
        postOnMain { listener.onConnecting() }
        try {
            bluetoothGatt = dev.connectGatt(
                context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            postOnMain { listener.onError("BLE permission denied") }
        }
    }

    /**
        * Write a single-byte command to the control characteristic.
     */
    fun writeCommand(command: Byte): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = controlCharacteristic ?: return false
        return try {
            char.value = byteArrayOf(command)
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {
            postOnMain { listener.onError("BLE permission denied") }
            false
        }
    }

    /**
        * Write a byte array to the control characteristic.
     */
    fun writeCommand(payload: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = controlCharacteristic ?: return false
        return try {
            char.value = payload
            gatt.writeCharacteristic(char)
        } catch (e: SecurityException) {
            postOnMain { listener.onError("BLE permission denied") }
            false
        }
    }

    /** Disconnect and clean up resources. */
    fun disconnect() {
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {}
        bluetoothGatt = null
        controlCharacteristic = null
        negotiatedMtu = 23
    }

    // --- GATT callback ---

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    postOnMain { listener.onStatusMessage("Connected – requesting MTU…") }
                    Log.d(TAG, "Connected, requesting MTU=${config.requestedMtu}")
                    try {
                        gatt.requestMtu(config.requestedMtu)
                    } catch (e: SecurityException) {
                        postOnMain { listener.onError("BLE permission denied") }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    negotiatedMtu = 23
                    controlCharacteristic = null
                    postOnMain { listener.onDisconnected() }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            postOnMain { listener.onStatusMessage("MTU=$mtu – discovering services…") }
            try { gatt.discoverServices() } catch (_: SecurityException) {}
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postOnMain { listener.onError("Service discovery failed (status $status)") }
                return
            }

            val service = gatt.getService(config.serviceUuid)
            if (service == null) {
                postOnMain { listener.onError("Target service not found on device") }
                return
            }

            // Turn on notifications for the data characteristic.
            val dataChar = service.getCharacteristic(config.dataCharUuid)
            controlCharacteristic = service.getCharacteristic(config.controlCharUuid)

            if (dataChar != null) {
                try {
                    gatt.setCharacteristicNotification(dataChar, true)
                    val descriptor = dataChar.getDescriptor(config.cccdUuid)
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                } catch (_: SecurityException) {}
            }

            postOnMain { listener.onConnected(negotiatedMtu) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == config.dataCharUuid) {
                val data = characteristic.value ?: return
                postOnMain { listener.onDataReceived(data) }
            }
        }
    }

    // --- Helpers ---

    private fun postOnMain(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    companion object {
        private const val TAG = "BleConnector"
    }
}

