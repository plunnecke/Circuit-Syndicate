package com.circuitsyndicate.findingtheway

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Generic BLE connector for the OmiGlass smart glasses.
 *
 * Any Bluetooth-capable Android device can use this class to scan for,
 * discover, and connect to the smart glasses over BLE.  The caller
 * supplies a [ConnectionListener] to react to lifecycle events and
 * incoming data.
 *
 * Usage:
 * ```
 * val connector = SmartGlassesBleConnector(
 *     context        = this,
 *     config         = SmartGlassesBleConnector.Config(),   // defaults match ESP32-CAM firmware
 *     listener       = myListener
 * )
 * connector.startScan()          // find the glasses
 * connector.connect()            // after onDeviceFound
 * connector.writeCommand(0x01)   // send a single-byte command
 * connector.disconnect()         // when done
 * ```
 *
 * All listener callbacks are delivered on the **main (UI) thread**.
 */
class SmartGlassesBleConnector(
    private val context: Context,
    private val config: Config = Config(),
    private val listener: ConnectionListener
) {

    // ───────────────────────────────── Configuration ─────────────────────────────────

    /**
     * Tuneable connection parameters.
     *
     * @param deviceName        Advertised name to match during scanning.
     *                          Set to `null` to accept any device that
     *                          advertises [serviceUuid].
     * @param serviceUuid       Primary GATT service UUID on the glasses.
     * @param dataCharUuid      Characteristic used for **notifications**
     *                          (incoming data from glasses).
     * @param controlCharUuid   Characteristic used for **writes**
     *                          (commands sent to glasses).
     * @param cccdUuid          Client Characteristic Configuration
     *                          Descriptor UUID (standard BLE value).
     * @param requestedMtu      MTU size to request after connection.
     * @param scanTimeoutMs     How long to scan before giving up (ms).
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

    // ───────────────────────────────── Listener ─────────────────────────────────────

    /**
     * Callback interface for BLE lifecycle events.
     * All methods are called on the **main thread**.
     */
    interface ConnectionListener {
        /** A matching device was found during scanning. */
        fun onDeviceFound(device: BluetoothDevice, name: String)

        /** GATT connection established; MTU negotiation starts next. */
        fun onConnecting()

        /** Services discovered and notifications enabled – ready for commands. */
        fun onConnected(negotiatedMtu: Int)

        /** Connection dropped or intentionally closed. */
        fun onDisconnected()

        /** New data arrived on the data characteristic. */
        fun onDataReceived(data: ByteArray)

        /** Free-form status text (useful for debug / UI status labels). */
        fun onStatusMessage(message: String)

        /** Something went wrong. */
        fun onError(message: String)
    }

    // ───────────────────────────────── State ─────────────────────────────────────────

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

    // ───────────────────────────────── Public API ────────────────────────────────────

    /**
     * Begin scanning for a BLE device matching [Config.deviceName]
     * (or [Config.serviceUuid] if deviceName is null).
     *
     * Scanning stops automatically after [Config.scanTimeoutMs] or
     * when a matching device is found.
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
                    // Accept any device that advertises the target service UUID
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

            // Auto-stop after timeout
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

    /** Stop an in-progress scan early. */
    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanCallback?.let {
            try { scanner.stopScan(it) } catch (_: Exception) {}
        }
        isScanning = false
    }

    /**
     * Connect to the device previously found via [startScan].
     * You may also set [targetDevice] manually before calling this.
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
     * Write an arbitrary payload to the control characteristic.
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

    /** Disconnect and release resources. */
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

    // ───────────────────────────────── GATT Callback ────────────────────────────────

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

            // Subscribe to data notifications
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

    // ───────────────────────────────── Helpers ──────────────────────────────────────

    private fun postOnMain(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    companion object {
        private const val TAG = "BleConnector"
    }
}

