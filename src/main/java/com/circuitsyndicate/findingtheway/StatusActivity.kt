package com.circuitsyndicate.findingtheway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class StatusActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private val tapActionConfirmation = TapActionConfirmation()

    private lateinit var glassesStatus: TextView
    private lateinit var vestStatus: TextView
    private lateinit var glassesBattery: TextView
    private lateinit var vestBattery: TextView
    private lateinit var glassesBatteryBar: ProgressBar
    private lateinit var vestBatteryBar: ProgressBar
    private lateinit var lastDetection: TextView
    private lateinit var historyRecycler: RecyclerView
    private lateinit var btnClearHistory: Button
    private lateinit var btnTakePhoto: Button
    private lateinit var btnTakeBurst: Button

    private lateinit var historyAdapter: ObjectHistoryAdapter

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "BT_CONNECTION_CHANGED", "BLUETOOTH_CONNECTION_CHANGED" -> {
                    val typeName = intent.getStringExtra("device_type")
                    val connected = intent.getBooleanExtra("connected", false)
                    if (typeName != null) {
                        runCatching { DeviceType.valueOf(typeName) }.getOrNull()?.let { type ->
                            if (!connected) {
                                showBatteryUnavailable(type)
                            }
                        }
                    }
                    updateStatus()
                }
                AssistiveRuntimeSettings.ACTION_MODE_CHANGED -> {
                    if (AssistiveRuntimeSettings.getMode(this@StatusActivity) !=
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
                        updateBattery(deviceType, level)
                        // Announce low battery via TTS
                        if (level in 1..20 &&
                            AssistiveRuntimeSettings.isAssistiveSpeechAllowed(this@StatusActivity)
                        ) {
                            speak("${if (type == "VEST") "Vest" else "Glasses"} battery low: $level percent.")
                        }
                    } else {
                        showBatteryUnavailable(deviceType)
                    }
                }
                "OBJECT_DETECTED" -> {
                    val obj = intent.getStringExtra("object") ?: "Unknown"
                    val source = intent.getStringExtra("source") ?: "VEST"
                    val label = if (source == "GLASSES") "[Glasses] $obj" else obj
                    lastDetection.text = "Last detected: $label"
                    lastDetection.contentDescription = "Last detected: $label"
                    refreshHistory()
                    // DetectionSpeaker already handles TTS for glasses detections.
                    // Only announce vest detections here.
                    if (source == "VEST" &&
                        AssistiveRuntimeSettings.isAssistiveSpeechAllowed(this@StatusActivity)
                    ) {
                        speak("Obstacle detected: $obj")
                    }
                }
                "GLASSES_IMAGE_SAVED" -> {
                    ObjectHistory.addDetection("[Glasses] Photo saved")
                    refreshHistory()
                }
                "GLASSES_DETECTION_SUMMARY" -> {
                    val summary = intent.getStringExtra("summary") ?: return
                    lastDetection.text = "Last detected: $summary"
                    lastDetection.contentDescription = "Last glasses detection: $summary"
                    ObjectHistory.addDetection("[Glasses] $summary")
                    refreshHistory()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        tts = TextToSpeech(this, this)
        bindViews()
        setupHistory()
        updateStatus()
        refreshHistory()
    }

    private fun bindViews() {
        glassesStatus     = findViewById(R.id.status_glasses)
        vestStatus        = findViewById(R.id.status_vest)
        glassesBattery    = findViewById(R.id.battery_glasses)
        vestBattery       = findViewById(R.id.battery_vest)
        glassesBatteryBar = findViewById(R.id.glasses_battery_progress)
        vestBatteryBar    = findViewById(R.id.vest_battery_progress)
        lastDetection     = findViewById(R.id.last_detection)
        historyRecycler   = findViewById(R.id.history_recycler_view)
        btnClearHistory   = findViewById(R.id.btn_clear_history)
        btnTakePhoto      = findViewById(R.id.btn_take_photo)
        btnTakeBurst      = findViewById(R.id.btn_take_burst)

        btnTakePhoto.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "status:take_photo",
                announcement = "Take photo with smart glasses. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = ::performTakePhoto
            )
        }
        btnTakeBurst.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "status:take_burst",
                announcement = "Start burst capture with smart glasses. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = ::performTakeBurst
            )
        }

        btnClearHistory.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "status:clear_history",
                announcement = "Clear detection history. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    ObjectHistory.clear()
                    refreshHistory()
                    speak("Detection history cleared.")
                }
            )
        }
    }

    private fun performTakePhoto() {
        tapActionConfirmation.clear()
        if (GlassesCommandSender.isConnected()) {
            if (GlassesCommandSender.takePhoto()) {
                speak("Taking photo with smart glasses.")
            } else {
                if (!GlassesCommandSender.isCaptureEnabled()) {
                    speak("Picture taking is disabled in settings.")
                } else {
                    speak("Could not send photo command.")
                }
            }
        } else {
            speak("Smart glasses not connected. Open Bluetooth to connect.")
        }
    }

    private fun performTakeBurst() {
        tapActionConfirmation.clear()
        if (GlassesCommandSender.isConnected()) {
            if (GlassesCommandSender.takeBurst(interruptInFlight = true)) {
                speak("Burst capture started.")
            } else {
                if (!GlassesCommandSender.isCaptureEnabled()) {
                    speak("Picture taking and inference are disabled in settings.")
                } else {
                    speak("Could not start burst capture.")
                }
            }
        } else {
            speak("Smart glasses not connected. Open Bluetooth to connect.")
        }
    }

    private fun setupHistory() {
        historyAdapter = ObjectHistoryAdapter(mutableListOf()) { item ->
            speak(item)
        }
        historyRecycler.layoutManager = LinearLayoutManager(this)
        historyRecycler.adapter = historyAdapter
    }

    private fun refreshHistory() {
        historyAdapter.updateData(ObjectHistory.getHistory().toMutableList())
    }

    private fun updateStatus() {
        fun setStatus(view: TextView, connected: Boolean, label: String) {
            if (connected) {
                view.text = "✓ Connected"
                view.contentDescription = "$label connected"
                view.setTextColor(ContextCompat.getColor(this, R.color.success_green))
            } else {
                view.text = "○ Not Connected"
                view.contentDescription = "$label not connected"
                view.setTextColor(ContextCompat.getColor(this, R.color.error_red))
            }
        }

        val glassesConnected = DeviceManager.isGlassesConnected()
        val vestConnected = DeviceManager.isVestConnected()

        setStatus(glassesStatus, glassesConnected, "Smart Glasses")
        setStatus(vestStatus, vestConnected, "Navigation Vest")

        if (glassesConnected) {
            val level = GlassesBleManager.getBatteryLevel()
            if (level in 0..100) updateBattery(DeviceType.GLASSES, level)
            else showBatteryUnavailable(DeviceType.GLASSES)
        } else {
            showBatteryUnavailable(DeviceType.GLASSES)
        }

        if (vestConnected) {
            val level = DeviceManager.vestHandler?.getCurrentState()?.batteryLevel ?: -1
            if (level in 0..100) updateBattery(DeviceType.VEST, level)
            else showBatteryUnavailable(DeviceType.VEST)
        } else {
            showBatteryUnavailable(DeviceType.VEST)
        }
    }

    private fun updateBattery(type: DeviceType, level: Int) {
        val textView = if (type == DeviceType.GLASSES) glassesBattery else vestBattery
        val barView  = if (type == DeviceType.GLASSES) glassesBatteryBar else vestBatteryBar

        textView.text = "$level%"
        textView.contentDescription = "${type.displayName} battery $level percent"
        barView.progress = level

        val color = when {
            level > 50 -> R.color.success_green
            level > 20 -> R.color.warning_orange
            else       -> R.color.error_red
        }
        barView.progressTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    private fun showBatteryUnavailable(type: DeviceType) {
        val textView = if (type == DeviceType.GLASSES) glassesBattery else vestBattery
        val barView = if (type == DeviceType.GLASSES) glassesBatteryBar else vestBatteryBar
        textView.text = "--"
        textView.contentDescription = "${type.displayName} battery unavailable"
        textView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        barView.progress = 0
        barView.progressTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.text_secondary)
        )
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("Status page. Showing device connection and battery information.")
        }
    }

    private fun speak(text: String) {
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
        registerReceiver(receiver, IntentFilter().apply {
            addAction("BT_CONNECTION_CHANGED")
            addAction("BLUETOOTH_CONNECTION_CHANGED")
            addAction(AssistiveRuntimeSettings.ACTION_MODE_CHANGED)
            addAction("BATTERY_UPDATE")
            addAction("OBJECT_DETECTED")
            addAction("GLASSES_IMAGE_SAVED")
            addAction("GLASSES_DETECTION_SUMMARY")
        },
            Context.RECEIVER_NOT_EXPORTED)
        updateStatus()
        refreshHistory()
    }

    override fun onPause() {
        super.onPause()
        tapActionConfirmation.clear()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        tapActionConfirmation.clear()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
