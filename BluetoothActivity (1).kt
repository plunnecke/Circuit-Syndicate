package com.circuitsyndicate.findingtheway

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class BluetoothActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var btnScan: Button
    private lateinit var btnDisconnectGlasses: Button
    private lateinit var btnDisconnectVest: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var glassesStatusText: TextView
    private lateinit var vestStatusText: TextView
    private lateinit var glassesBatteryText: TextView
    private lateinit var vestBatteryText: TextView

    private val discovered = mutableListOf<BluetoothDevice>()
    private var deviceAdapter: BluetoothDeviceAdapter? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tapActionConfirmation = TapActionConfirmation()
    private var scanSessionToken = 0
    private var glassesConnectPollToken = 0
    private var vestConnectPollToken = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "BT_CONNECTION_CHANGED", "BLUETOOTH_CONNECTION_CHANGED" -> {
                    updateDeviceStatus()
                    updateConnectionBanner(intent)
                    tapActionConfirmation.clear()
                }
                AssistiveRuntimeSettings.ACTION_MODE_CHANGED -> {
                    if (AssistiveRuntimeSettings.getMode(this@BluetoothActivity) !=
                        AssistiveRuntimeMode.ASSISTIVE_ACTIVE
                    ) {
                        interruptSpeech()
                    }
                }
                "BATTERY_UPDATE" -> {
                    val type = intent.getStringExtra("deviceType") ?: return
                    val level = intent.getIntExtra("level", -1)
                    val deviceType = runCatching { DeviceType.valueOf(type) }.getOrNull() ?: return
                    if (level in 0..100) {
                        updateBattery(deviceType, level, announceLow = true)
                    } else {
                        showBatteryUnavailable(deviceType)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bluetooth)

        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        bindViews()
        setupRecycler()
        setupScanButton()
        setupDisconnectButtons()
        registerReceivers()
        updateDeviceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateDeviceStatus()
    }

    private fun bindViews() {
        tts = TextToSpeech(this, this)
        btnScan             = findViewById(R.id.btn_scan)
        btnDisconnectGlasses = findViewById(R.id.btn_disconnect_glasses)
        btnDisconnectVest    = findViewById(R.id.btn_disconnect_vest)
        recyclerView        = findViewById(R.id.device_recycler_view)
        progressBar         = findViewById(R.id.progress_bar)
        statusText          = findViewById(R.id.status_text)
        glassesStatusText   = findViewById(R.id.glasses_status_text)
        vestStatusText      = findViewById(R.id.vest_status_text)
        glassesBatteryText  = findViewById(R.id.glasses_battery_text)
        vestBatteryText     = findViewById(R.id.vest_battery_text)
    }

    private fun setupRecycler() {
        deviceAdapter = BluetoothDeviceAdapter(discovered) { device, type ->
            connectDevice(device, type)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = deviceAdapter
    }

    private fun setupScanButton() {
        btnScan.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "scan",
                announcement = "Scan for nearby Bluetooth devices. Tap again to start.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = ::startScan
            )
        }
    }

    private fun setupDisconnectButtons() {
        btnDisconnectGlasses.setOnClickListener {
            requestDisconnect(DeviceType.GLASSES)
        }
        btnDisconnectVest.setOnClickListener {
            requestDisconnect(DeviceType.VEST)
        }
    }

    private fun registerReceivers() {
        registerReceiver(receiver, IntentFilter().apply {
            addAction("BT_CONNECTION_CHANGED")
            addAction("BLUETOOTH_CONNECTION_CHANGED")
            addAction(AssistiveRuntimeSettings.ACTION_MODE_CHANGED)
            addAction("BATTERY_UPDATE")
        },
            Context.RECEIVER_NOT_EXPORTED)
    }

    private fun startScan() {
        tapActionConfirmation.clear()
        if (!bluetoothAdapter.isEnabled) {
            speak("Bluetooth is off. Please enable Bluetooth and try again.")
            return
        }

        discovered.clear()
        deviceAdapter?.notifyDataSetChanged()
        progressBar.visibility = View.VISIBLE
        statusText.text = "Scanning for BLE devices…"
        speak("Scanning for BLE devices")

        scanSessionToken += 1
        val token = scanSessionToken
        GlassesBleManager.stopScan()

        // BLE scan — finds both glasses and vest-capable peripherals.
        GlassesBleManager.startScan { device, name ->
            runOnUiThread {
                if (token != scanSessionToken) return@runOnUiThread
                if (discovered.none { it.address == device.address }) {
                    discovered.add(device)
                    deviceAdapter?.notifyDataSetChanged()
                    speak("BLE device found: $name")
                }
            }
        }

        uiHandler.postDelayed({
            if (token != scanSessionToken) return@postDelayed
            progressBar.visibility = View.GONE
            val count = discovered.size
            statusText.text = "BLE scan complete — $count device${if (count == 1) "" else "s"} found"
            speak("BLE scan complete. $count device${if (count == 1) "" else "s"} found.")
        }, 16_000L)
    }

    private fun connectDevice(device: BluetoothDevice, type: DeviceType) {
        val name = deviceDisplayName(device)
        val role = if (type == DeviceType.GLASSES) "smart glasses" else "navigation vest"
        tapActionConfirmation.confirmOrAnnounce(
            key = "connect:${type.name}:${device.address}",
            announcement = "Connect $name as $role. Tap again to confirm.",
            interruptSpeech = ::interruptSpeech,
            speak = ::speak,
            onConfirmed = { performConnectDevice(device, type) }
        )
    }

    private fun performConnectDevice(device: BluetoothDevice, type: DeviceType) {
        tapActionConfirmation.clear()
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val name = deviceDisplayName(device)

        if (type == DeviceType.VEST && device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            statusText.text = "$name appears Classic-only. Vest pairing now requires BLE."
            speak("$name appears classic only. Vest pairing requires BLE.")
            return
        }

        if (type == DeviceType.GLASSES && GlassesBleManager.isConnected(device.address)) {
            statusText.text = "$name is already connected as Smart Glasses."
            speak("$name is already connected.")
            updateDeviceStatus()
            return
        }

        if (type == DeviceType.VEST && GlassesBleManager.isConnected(device.address)) {
            statusText.text = "$name is already connected as Smart Glasses. Disconnect glasses before assigning vest role."
            speak("$name is already connected as smart glasses. Disconnect glasses before assigning vest role.")
            return
        }

        if (type == DeviceType.VEST) {
            statusText.text = "Connecting to $name as ${type.displayName} over BLE…"
        } else {
            statusText.text = "Connecting to $name as ${type.displayName}…"
        }

        speak("Connecting to $name as ${type.displayName}")
        if (type == DeviceType.GLASSES) {
            DeviceManager.connectDevice(device, type)
            monitorGlassesConnect(device.address)
        } else {
            DeviceManager.connectDevice(device, type)
            monitorVestConnect()
        }
    }

    private fun requestDisconnect(type: DeviceType) {
        val connected = when (type) {
            DeviceType.GLASSES -> DeviceManager.isGlassesConnected()
            DeviceType.VEST -> DeviceManager.isVestConnected()
        }

        val spokenName = if (type == DeviceType.GLASSES) "smart glasses" else "navigation vest"
        if (!connected) {
            interruptSpeech()
            tapActionConfirmation.clear()
            speak("$spokenName is already disconnected.")
            updateDeviceStatus()
            return
        }

        tapActionConfirmation.confirmOrAnnounce(
            key = "disconnect:${type.name}",
            announcement = "Disconnect $spokenName. Tap again to confirm.",
            interruptSpeech = ::interruptSpeech,
            speak = ::speak,
            onConfirmed = {
                tapActionConfirmation.clear()
                statusText.text = "Disconnecting ${type.displayName}…"
                if (type == DeviceType.GLASSES) {
                    GlassesBleManager.suppressAutoConnectForManualDisconnect()
                }
                DeviceManager.disconnectDevice(type)
                updateDeviceStatus()
                speak("$spokenName disconnected.")
            }
        )
    }

    private fun deviceDisplayName(device: BluetoothDevice): String {
        return if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            device.name ?: "Unknown"
        } else {
            "Unknown"
        }
    }

    private fun interruptSpeech() {
        if (::tts.isInitialized) {
            tts.stop()
        }
        GlassesBleManager.stopSpeech()
    }

    private fun monitorGlassesConnect(address: String) {
        glassesConnectPollToken += 1
        val token = glassesConnectPollToken
        val startedAt = SystemClock.elapsedRealtime()

        val poll = object : Runnable {
            override fun run() {
                if (token != glassesConnectPollToken) return

                updateDeviceStatus()
                if (GlassesBleManager.isConnected(address)) {
                    statusText.text = "Smart glasses connected."
                    return
                }

                if (SystemClock.elapsedRealtime() - startedAt < 15_000L) {
                    uiHandler.postDelayed(this, 400L)
                }
            }
        }

        uiHandler.post(poll)
    }

    private fun monitorVestConnect() {
        vestConnectPollToken += 1
        val token = vestConnectPollToken
        val startedAt = SystemClock.elapsedRealtime()

        val poll = object : Runnable {
            override fun run() {
                if (token != vestConnectPollToken) return

                updateDeviceStatus()
                if (DeviceManager.isVestConnected()) {
                    statusText.text = "Navigation vest connected."
                    return
                }

                if (SystemClock.elapsedRealtime() - startedAt < 15_000L) {
                    uiHandler.postDelayed(this, 400L)
                }
            }
        }

        uiHandler.post(poll)
    }

    private fun updateConnectionBanner(intent: Intent) {
        val typeName = intent.getStringExtra("device_type") ?: return
        val connected = intent.getBooleanExtra("connected", false)
        val count = intent.getIntExtra("connected_count", -1)

        when (typeName) {
            DeviceType.GLASSES.name -> {
                statusText.text = if (connected) {
                    if (count > 1) {
                        "Smart glasses connected ($count BLE links active)."
                    } else {
                        "Smart glasses connected."
                    }
                } else {
                    "Smart glasses disconnected."
                }
            }
            DeviceType.VEST.name -> {
                statusText.text = if (connected) {
                    "Navigation vest connected."
                } else {
                    "Navigation vest disconnected."
                }
            }
        }
    }

    private fun updateDeviceStatus() {
        fun setStatus(textView: TextView, batteryView: TextView,
                      connected: Boolean, label: String) {
            if (connected) {
                textView.text = "✓ Connected"
                textView.contentDescription = "$label connected"
                textView.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            } else {
                textView.text = "○ Not connected"
                textView.contentDescription = "$label not connected"
                textView.setTextColor(ContextCompat.getColor(this, R.color.error_red))
                batteryView.text = "--"
            }
        }

        val glassesConnected = DeviceManager.isGlassesConnected()
        val vestConnected = DeviceManager.isVestConnected()

        btnDisconnectGlasses.isEnabled = glassesConnected
        btnDisconnectGlasses.alpha = if (glassesConnected) 1f else 0.55f
        btnDisconnectVest.isEnabled = vestConnected
        btnDisconnectVest.alpha = if (vestConnected) 1f else 0.55f

        setStatus(glassesStatusText, glassesBatteryText, glassesConnected, "Smart Glasses")
        setStatus(vestStatusText, vestBatteryText, vestConnected, "Navigation Vest")

        if (glassesConnected) {
            val level = GlassesBleManager.getBatteryLevel()
            if (level in 0..100) updateBattery(DeviceType.GLASSES, level, announceLow = false)
            else showBatteryUnavailable(DeviceType.GLASSES)
        } else {
            showBatteryUnavailable(DeviceType.GLASSES)
        }

        if (vestConnected) {
            val level = DeviceManager.vestHandler?.getCurrentState()?.batteryLevel ?: -1
            if (level in 0..100) updateBattery(DeviceType.VEST, level, announceLow = false)
            else showBatteryUnavailable(DeviceType.VEST)
        } else {
            showBatteryUnavailable(DeviceType.VEST)
        }
    }

    private fun updateBattery(type: DeviceType, level: Int, announceLow: Boolean) {
        val view = if (type == DeviceType.GLASSES) glassesBatteryText else vestBatteryText
        view.text = "$level%"
        view.contentDescription = "${type.displayName} battery $level percent"
        val color = when {
            level > 50 -> R.color.success_green
            level > 20 -> R.color.warning_orange
            else -> R.color.error_red
        }
        view.setTextColor(ContextCompat.getColor(this, color))
        if (announceLow && level < 20) {
            speak("Warning: ${type.displayName} battery is low at $level percent.")
        }
    }

    private fun showBatteryUnavailable(type: DeviceType) {
        val view = if (type == DeviceType.GLASSES) glassesBatteryText else vestBatteryText
        view.text = "--"
        view.contentDescription = "${type.displayName} battery unavailable"
        view.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun hasPermission(permission: String) =
        ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("Bluetooth page. Tap Scan to find nearby devices, then assign each as Glasses or Vest.")
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized && AssistiveRuntimeSettings.isAssistiveSpeechAllowed(this)) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        scanSessionToken += 1
        glassesConnectPollToken += 1
        vestConnectPollToken += 1
        tapActionConfirmation.clear()
        uiHandler.removeCallbacksAndMessages(null)
        GlassesBleManager.stopScan()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
