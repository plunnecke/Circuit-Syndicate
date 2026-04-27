package com.circuitsyndicate.findingtheway

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.circuitsyndicate.findingtheway.ui.images.ImageHistoryActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        private const val STARTUP_RECONNECT_GRACE_MS = 4_000L
        private const val STARTUP_COOLDOWN_RETRY_FLOOR_MS = 250L
    }

    private lateinit var tts: TextToSpeech
    private lateinit var connectionIndicator: TextView
    private lateinit var btnBluetooth: Button
    private lateinit var btnStatus: Button
    private lateinit var btnSettings: Button
    private lateinit var btnImages: Button
    private lateinit var btnLog: Button
    private val tapActionConfirmation = TapActionConfirmation()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startupAutoConnectAttempted = false
    private var startupAutoConnectRunnable: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filterValues { !it }.keys
            if (denied.isNotEmpty()) {
                Toast.makeText(this,
                    "Some permissions denied — Bluetooth features may be limited.",
                    Toast.LENGTH_LONG).show()
            } else {
                speak("Permissions granted. You can now connect your devices.")
            }
            maybeStartStartupAutoConnect()
        }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "BT_CONNECTION_CHANGED", "BLUETOOTH_CONNECTION_CHANGED" -> {
                    updateConnectionStatus()
                }
                AssistiveRuntimeSettings.ACTION_MODE_CHANGED -> {
                    if (AssistiveRuntimeSettings.getMode(this@MainActivity) !=
                        AssistiveRuntimeMode.ASSISTIVE_ACTIVE
                    ) {
                        interruptSpeech()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupClickListeners()
        checkPermissions()
        maybeStartStartupAutoConnect()
    }

    private fun bindViews() {
        tts = TextToSpeech(this, this)
        connectionIndicator = findViewById(R.id.connection_indicator)
        btnBluetooth = findViewById(R.id.btn_bluetooth)
        btnStatus    = findViewById(R.id.btn_status)
        btnSettings  = findViewById(R.id.btn_settings)
        btnImages    = findViewById(R.id.btn_saved_images)
        btnLog       = findViewById(R.id.btn_log)
    }

    private fun setupClickListeners() {
        setupTapConfirmedNavigation(
            button = btnBluetooth,
            key = "nav:bluetooth",
            announcement = "Bluetooth page. Tap again to open.",
            destination = Intent(this, BluetoothActivity::class.java)
        )
        setupTapConfirmedNavigation(
            button = btnStatus,
            key = "nav:status",
            announcement = "Status page. Tap again to open.",
            destination = Intent(this, StatusActivity::class.java)
        )
        setupTapConfirmedNavigation(
            button = btnSettings,
            key = "nav:settings",
            announcement = "Settings page. Tap again to open.",
            destination = Intent(this, SettingsActivity::class.java)
        )
        setupTapConfirmedNavigation(
            button = btnImages,
            key = "nav:images",
            announcement = "Saved images page. Tap again to open.",
            destination = Intent(this, ImageHistoryActivity::class.java)
        )
        setupTapConfirmedNavigation(
            button = btnLog,
            key = "nav:log",
            announcement = "Interaction log page. Tap again to open.",
            destination = Intent(this, InteractionLogActivity::class.java)
        )
    }

    private fun setupTapConfirmedNavigation(
        button: Button,
        key: String,
        announcement: String,
        destination: Intent
    ) {
        button.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = key,
                announcement = announcement,
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    startActivity(destination)
                }
            )
        }
    }

    private fun updateConnectionStatus() {
        val glassesOk = DeviceManager.isGlassesConnected()
        val vestOk    = DeviceManager.isVestConnected()

        val (text, colorRes, announcement) = when {
            glassesOk && vestOk ->
                Triple("✓ Glasses and vest connected", R.color.success_green,
                    "Both devices connected.")
            glassesOk ->
                Triple("⚠ Glasses connected, vest not connected", R.color.warning_orange,
                    "Smart glasses connected. Vest not connected.")
            vestOk ->
                Triple("⚠ Vest connected, glasses not connected", R.color.warning_orange,
                    "Vest connected. Smart glasses not connected.")
            else ->
                Triple("○ No devices connected", R.color.error_red,
                    "No devices connected. Open Bluetooth to connect.")
        }

        connectionIndicator.text = text
        connectionIndicator.contentDescription = announcement
        connectionIndicator.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun checkPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasStartupBlePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun maybeStartStartupAutoConnect() {
        if (startupAutoConnectAttempted) return
        if (!hasStartupBlePermissions()) return
        if (DeviceManager.isGlassesConnected()) {
            startupAutoConnectAttempted = true
            return
        }

        val cooldownRemainingMs = GlassesBleManager.getAutoConnectSuppressionRemainingMs()
        if (cooldownRemainingMs > 0L) {
            postStartupAutoConnectRunnable(
                delayMs = maxOf(cooldownRemainingMs, STARTUP_COOLDOWN_RETRY_FLOOR_MS)
            ) {
                maybeStartStartupAutoConnect()
            }
            return
        }

        startupAutoConnectAttempted = true

        val lastAddress = GlassesBleManager.getLastSuccessfulAddress()
        if (!lastAddress.isNullOrBlank()) {
            GlassesBleManager.connect(lastAddress)
            postStartupAutoConnectRunnable(STARTUP_RECONNECT_GRACE_MS) {
                if (!hasStartupBlePermissions()) return@postStartupAutoConnectRunnable
                if (DeviceManager.isGlassesConnected()) return@postStartupAutoConnectRunnable
                if (GlassesBleManager.isConnected(lastAddress)) return@postStartupAutoConnectRunnable
                if (GlassesBleManager.isAutoConnectSuppressed()) return@postStartupAutoConnectRunnable
                startStartupScanAutoConnect()
            }
            return
        }

        startStartupScanAutoConnect()
    }

    private fun startStartupScanAutoConnect() {
        if (!hasStartupBlePermissions()) return
        if (GlassesBleManager.isAutoConnectSuppressed()) return
        if (DeviceManager.isGlassesConnected()) return
        GlassesBleManager.startScan { _, _ ->
            // Startup scan runs for auto-connect side effects in GlassesBleManager.
        }
    }

    private fun postStartupAutoConnectRunnable(delayMs: Long, action: () -> Unit) {
        startupAutoConnectRunnable?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            startupAutoConnectRunnable = null
            if (isFinishing || isDestroyed) return@Runnable
            action()
        }
        startupAutoConnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    // TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("Welcome to Finding The Way. Navigation assistance for the blind and visually impaired.")
        }
    }

    fun speak(text: String) {
        if (::tts.isInitialized && AssistiveRuntimeSettings.isAssistiveSpeechAllowed(this)) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun interruptSpeech() {
        if (::tts.isInitialized) {
            tts.stop()
        }
        GlassesBleManager.stopSpeech()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        val filter = IntentFilter().apply {
            addAction("BT_CONNECTION_CHANGED")
            addAction("BLUETOOTH_CONNECTION_CHANGED")
            addAction(AssistiveRuntimeSettings.ACTION_MODE_CHANGED)
        }
        registerReceiver(connectionReceiver, filter,
            Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        tapActionConfirmation.clear()
        try { unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        tapActionConfirmation.clear()
        startupAutoConnectRunnable?.let(mainHandler::removeCallbacks)
        startupAutoConnectRunnable = null
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
