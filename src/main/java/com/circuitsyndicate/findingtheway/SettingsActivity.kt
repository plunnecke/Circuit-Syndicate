package com.circuitsyndicate.findingtheway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * SettingsActivity — two-way sync between the UI and the vest ESP32.
 *
 * Data flow A (user → vest):
 *   User touches switch → listener checks isSuppressed → sends command to vest
 *   → vest echoes back a state message → VestMessageHandler parses it
 *   → onStateChanged() fires on UI thread → applyVestState() updates switches
 *   with suppression active so no command is re-sent.
 *
 * Data flow B (vest physical button → app):
 *   Vest sends HAPTICS_ON / HAPTICS_OFF etc. unprompted → VestMessageHandler
 *   → onStateChanged() → applyVestState() updates switches + announces change via TTS.
 *
 * Volume:
 *   - SeekBar always loads the phone's current STREAM_MUSIC volume (onResume + onCreate).
 *   - Dragging the SeekBar sets system volume immediately with no HUD.
 *   - Physical volume-up/down keys are intercepted via dispatchKeyEvent: super handles
 *     the actual change, then we sync the SeekBar to whatever the system landed on.
 *   - VOLUME_CHANGED_ACTION broadcast catches changes from any other source
 *     (notification shade, headset buttons, other apps) and keeps the SeekBar in sync.
 *
 * [suppressDepth] is an AtomicInteger counter, not a plain Boolean.
 * Nested suppression calls stack correctly; an exception mid-block cannot
 * leave suppression permanently on.
 */
class SettingsActivity : AppCompatActivity(),
    TextToSpeech.OnInitListener,
    VestMessageHandler.VestListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private lateinit var audioManager: AudioManager

    private lateinit var switchSystem:  SwitchCompat
    private lateinit var switchHaptics: SwitchCompat
    private lateinit var switchSensors: SwitchCompat
    private lateinit var switchAudio:   SwitchCompat
    private lateinit var switchNoObjectsSpeech: SwitchCompat
    private lateinit var assistiveModeGroup: RadioGroup
    private lateinit var btnToggleGlassesCapture: Button
    private lateinit var btnBackgroundRuntimeSafeguard: Button
    private lateinit var seekVolume:    SeekBar
    private lateinit var volumeLabel:   TextView
    private lateinit var statusText:    TextView
    private lateinit var glassesCaptureStatusText: TextView
    private lateinit var backgroundRuntimeStatusText: TextView
    private lateinit var connectionStatusText: TextView
    private var lastMismatchStatus: String? = null
    private val tapActionConfirmation = TapActionConfirmation()

    // One-time disconnect override tracking for each dual-connected epoch.
    private var dualConnectionOverrideState = DualConnectionOverridePolicy.State()
    private var pendingVestForceOnOverrideAfterReconnect = false

    // ── Suppression guard ─────────────────────────────────────────────────────
    private val suppressDepth = AtomicInteger(0)

    private inline fun withSuppression(block: () -> Unit) {
        suppressDepth.incrementAndGet()
        try { block() } finally { suppressDepth.decrementAndGet() }
    }

    private val isSuppressed get() = suppressDepth.get() > 0

    // ── Volume broadcast receiver ─────────────────────────────────────────────
    // Keeps SeekBar in sync when volume changes come from outside this activity.
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    runOnUiThread { syncSeekBarToSystem(announce = false) }
                }
            }
        }
    }

    // ── Connection-change receiver ────────────────────────────────────────────
    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val a = intent.action ?: return
            if (a == "BT_CONNECTION_CHANGED" ||
                a == "BLUETOOTH_CONNECTION_CHANGED" ||
                a == AssistiveRuntimeSettings.ACTION_MODE_CHANGED ||
                a == AssistiveRuntimeSettings.ACTION_BACKGROUND_RUNTIME_CHANGED
            ) {
                runOnUiThread {
                    if (a == AssistiveRuntimeSettings.ACTION_MODE_CHANGED ||
                        a == AssistiveRuntimeSettings.ACTION_BACKGROUND_RUNTIME_CHANGED
                    ) {
                        syncAssistiveModeSelection()
                        refreshBackgroundRuntimeSafeguardControls()
                        if (AssistiveRuntimeSettings.getMode(this@SettingsActivity) !=
                            AssistiveRuntimeMode.ASSISTIVE_ACTIVE ||
                            !AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this@SettingsActivity)
                        ) {
                            interruptSpeech()
                        }
                        refreshStatusSummary()
                    } else {
                        handleConnectionTransition()
                        updateConnectionLabel()
                    }
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)

        bindViews()
        setupVolumeControl()
        setupSwitchListeners()
        setupAssistiveModeControl()
        setupBackgroundRuntimeSafeguardControl()
        setupGlassesCaptureControl()
        syncFromVestState()
        updateConnectionLabel()
        initializeConnectionEpochSnapshot()

        registerReceiver(connectionReceiver, IntentFilter().apply {
            addAction("BT_CONNECTION_CHANGED")
            addAction("BLUETOOTH_CONNECTION_CHANGED")
            addAction(AssistiveRuntimeSettings.ACTION_MODE_CHANGED)
            addAction(AssistiveRuntimeSettings.ACTION_BACKGROUND_RUNTIME_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)

        @Suppress("UnspecifiedRegisterReceiverFlag")
        registerReceiver(volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        DeviceManager.vestHandler?.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        tapActionConfirmation.clear()
        syncFromVestState()
        handleConnectionTransition()
        updateConnectionLabel()
        syncAssistiveModeSelection()
        withSuppression {
            switchNoObjectsSpeech.isChecked = NoObjectsSpeechSettings.isEnabled(this)
        }
        refreshBackgroundRuntimeSafeguardControls()
        refreshGlassesCaptureControls()
        refreshStatusSummary()
        syncSeekBarToSystem(announce = false)
    }

    override fun onPause() {
        super.onPause()
        tapActionConfirmation.clear()
    }

    override fun onDestroy() {
        tapActionConfirmation.clear()
        DeviceManager.vestHandler?.removeListener(this)
        try { unregisterReceiver(connectionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(volumeReceiver)     } catch (_: Exception) {}
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    // ── Volume key interception ───────────────────────────────────────────────

    /**
     * Intercept hardware volume-up/down keys.
     * We let super handle the actual system volume change, then immediately
     * sync our SeekBar to whatever level the system settled on.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
            && event.action == KeyEvent.ACTION_DOWN) {
            val result = super.dispatchKeyEvent(event)
            syncSeekBarToSystem(announce = true)
            return result
        }
        return super.dispatchKeyEvent(event)
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        switchSystem  = findViewById(R.id.switch_system)
        switchHaptics = findViewById(R.id.switch_haptics)
        switchSensors = findViewById(R.id.switch_sensors)
        switchAudio   = findViewById(R.id.switch_audio_feedback)
        switchNoObjectsSpeech = findViewById(R.id.switch_no_objects_tts)
        assistiveModeGroup = findViewById(R.id.assistive_mode_group)
        btnToggleGlassesCapture = findViewById(R.id.btn_toggle_glasses_capture)
        btnBackgroundRuntimeSafeguard = findViewById(R.id.btn_background_runtime_safeguard)
        seekVolume    = findViewById(R.id.volume_seekbar)
        volumeLabel   = findViewById(R.id.volume_label)
        statusText    = findViewById(R.id.status_text)
        glassesCaptureStatusText = findViewById(R.id.glasses_capture_status_text)
        backgroundRuntimeStatusText = findViewById(R.id.background_runtime_status_text)
        connectionStatusText = findViewById(R.id.connection_status_text)

        withSuppression {
            switchAudio.isChecked = AudioSettings.isAudioEnabled(this)
            switchNoObjectsSpeech.isChecked = NoObjectsSpeechSettings.isEnabled(this)
        }
        syncAssistiveModeSelection()
        applySubsystemControlLock(switchSystem.isChecked)
        refreshBackgroundRuntimeSafeguardControls()
        refreshGlassesCaptureControls()
    }

    private fun setupGlassesCaptureControl() {
        btnToggleGlassesCapture.setOnClickListener {
            val nextEnabled = !GlassesCommandSender.isCaptureEnabled()
            val actionText = if (nextEnabled) {
                "Enable smart glasses picture taking and inference. Tap again to confirm."
            } else {
                "Disable smart glasses picture taking and inference. Tap again to confirm."
            }
            tapActionConfirmation.confirmOrAnnounce(
                key = "settings:glasses_capture:$nextEnabled",
                announcement = actionText,
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    if (DualConnectionSafetyPolicy.shouldPromptForGlassesCaptureDisable(
                            vestConnected = DeviceManager.isVestConnected(),
                            glassesConnected = DeviceManager.isGlassesConnected(),
                            requestedCaptureEnabled = nextEnabled
                        )) {
                        speak("Safety check. Are haptics and ultrasonic sensors on?")
                        showGlassesDisableSafetyPrompt {
                            applyGlassesCaptureEnabled(enabled = false, source = "APP")
                        }
                        return@confirmOrAnnounce
                    }

                    applyGlassesCaptureEnabled(enabled = nextEnabled, source = "APP")
                }
            )
        }
    }

    private fun applyGlassesCaptureEnabled(
        enabled: Boolean,
        source: String,
        announce: Boolean = true
    ) {
        GlassesBleManager.setCaptureEnabled(enabled)
        InteractionLogger.logStateChange("GLASSES_CAPTURE", enabled, source)
        refreshGlassesCaptureControls()
        refreshStatusSummary()
        if (announce) {
            speak(
                if (enabled) {
                    "Smart glasses picture taking and inference enabled."
                } else {
                    "Smart glasses picture taking and inference disabled."
                }
            )
        }
    }

    private fun refreshGlassesCaptureControls() {
        val enabled = GlassesCommandSender.isCaptureEnabled()
        glassesCaptureStatusText.text = if (enabled) {
            "Picture taking and inference are enabled"
        } else {
            "Picture taking and inference are disabled"
        }
        glassesCaptureStatusText.contentDescription = if (enabled) {
            "Smart glasses picture taking and inference enabled"
        } else {
            "Smart glasses picture taking and inference disabled"
        }
        glassesCaptureStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.success_green else R.color.error_red
            )
        )

        btnToggleGlassesCapture.text = if (enabled) {
            "Disable picture taking and inference"
        } else {
            "Enable picture taking and inference"
        }
    }

    // ── Volume control ────────────────────────────────────────────────────────

    private fun setupVolumeControl() {
        seekVolume.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        syncSeekBarToSystem(announce = false)

        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Preview the selected volume level; commit only after second confirm gesture.
                refreshVolumeLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                confirmVolumeChange(requestedStep = sb.progress)
            }
        })
    }

    private fun confirmVolumeChange(requestedStep: Int) {
        val currentStep = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (requestedStep == currentStep) {
            tapActionConfirmation.clear()
            syncSeekBarToSystem(announce = false)
            return
        }

        val requestedPct = volumePct(requestedStep)
        var confirmed = false
        tapActionConfirmation.confirmOrAnnounce(
            key = "settings:volume:$requestedStep",
            announcement = "Set volume to $requestedPct percent. Slide again to confirm.",
            interruptSpeech = ::interruptSpeech,
            speak = ::speak,
            onConfirmed = {
                confirmed = true
                // FLAG 0 = no system HUD popup; our label handles feedback.
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, requestedStep, 0)
                syncSeekBarToSystem(announce = false)
                speak("Volume set to $requestedPct percent.")
                InteractionLogger.logStateChange("VOLUME", requestedPct > 0, "APP")
                tapActionConfirmation.clear()
            }
        )

        if (!confirmed) {
            // First gesture only announces; revert visual slider to actual system volume.
            syncSeekBarToSystem(announce = false)
        }
    }

    /** Pull actual system volume into SeekBar + label. */
    private fun syncSeekBarToSystem(announce: Boolean) {
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        seekVolume.progress = cur   // fromUser = false → won't re-trigger setStreamVolume
        refreshVolumeLabel(cur)
        if (announce) speak("Volume ${volumePct(cur)} percent.")
    }

    private fun refreshVolumeLabel(current: Int) {
        val pct = volumePct(current)
        volumeLabel.text = "Volume: $pct%"
        seekVolume.contentDescription =
            "Volume slider. Current value: $pct percent. Swipe right to increase, left to decrease."
    }

    private fun volumePct(steps: Int): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (steps * 100) / max else 0
    }

    // ── Switch listeners (user → vest) ────────────────────────────────────────

    private fun confirmSwitchChange(
        key: String,
        requestedChecked: Boolean,
        control: SwitchCompat,
        announcement: String,
        onConfirmed: (Boolean) -> Unit
    ) {
        var confirmed = false
        tapActionConfirmation.confirmOrAnnounce(
            key = key,
            announcement = announcement,
            interruptSpeech = ::interruptSpeech,
            speak = ::speak,
            onConfirmed = {
                confirmed = true
                onConfirmed(requestedChecked)
            }
        )

        if (!confirmed) {
            withSuppression { control.isChecked = !requestedChecked }
        }
    }

    private fun modeToCheckedId(mode: AssistiveRuntimeMode): Int {
        return when (mode) {
            AssistiveRuntimeMode.ASSISTIVE_ACTIVE -> R.id.mode_assistive_active
            AssistiveRuntimeMode.ASSISTIVE_SILENT -> R.id.mode_assistive_silent
            AssistiveRuntimeMode.PHONE_PRIORITY -> R.id.mode_phone_priority
        }
    }

    private fun modeConfirmationAnnouncement(mode: AssistiveRuntimeMode): String {
        return when (mode) {
            AssistiveRuntimeMode.ASSISTIVE_ACTIVE ->
                "Switch to Assistive Active mode. Tap again to confirm."
            AssistiveRuntimeMode.ASSISTIVE_SILENT ->
                "Switch to Assistive Silent mode. Tap again to confirm."
            AssistiveRuntimeMode.PHONE_PRIORITY ->
                "Switch to Phone Priority mode. Tap again to confirm."
        }
    }

    private fun applyAssistiveModeSelection(selectedMode: AssistiveRuntimeMode) {
        val changed = AssistiveRuntimeSettings.setMode(
            context = this,
            mode = selectedMode,
            source = "SETTINGS"
        )

        if (selectedMode != AssistiveRuntimeMode.ASSISTIVE_ACTIVE) {
            interruptSpeech()
        }

        if (changed) {
            InteractionLogger.log(
                "STATE",
                "APP",
                "ASSISTIVE_MODE = ${selectedMode.name}"
            )
            when (selectedMode) {
                AssistiveRuntimeMode.ASSISTIVE_ACTIVE ->
                    speak("Assistive active mode enabled.")
                AssistiveRuntimeMode.ASSISTIVE_SILENT,
                AssistiveRuntimeMode.PHONE_PRIORITY -> Unit
            }
        }

        refreshStatusSummary()
    }

    private fun setupSwitchListeners() {

        // System Power
        switchSystem.setOnCheckedChangeListener { _, isChecked ->
            if (isSuppressed) return@setOnCheckedChangeListener
            confirmSwitchChange(
                key = "settings:system:$isChecked",
                requestedChecked = isChecked,
                control = switchSystem,
                announcement = if (isChecked) {
                    "Turn vest system power on. Tap again to confirm."
                } else {
                    "Turn vest system power off. Tap again to confirm."
                },
                onConfirmed = { confirmedState ->
                    if (DualConnectionSafetyPolicy.shouldPromptForVestDisable(
                            vestConnected = DeviceManager.isVestConnected(),
                            glassesConnected = DeviceManager.isGlassesConnected(),
                            requestedVestStateOn = confirmedState
                        )) {
                        withSuppression { switchSystem.isChecked = true }
                        speak("Safety check. Are smart glasses connected and taking pictures?")
                        showVestDisableSafetyPrompt {
                            applySystemToggleFromApp(isChecked = false)
                        }
                        return@confirmSwitchChange
                    }

                    applySystemToggleFromApp(confirmedState)
                }
            )
        }

        // Haptic Feedback
        switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            if (isSuppressed) return@setOnCheckedChangeListener
            confirmSwitchChange(
                key = "settings:haptics:$isChecked",
                requestedChecked = isChecked,
                control = switchHaptics,
                announcement = if (isChecked) {
                    "Turn haptic feedback on. Tap again to confirm."
                } else {
                    "Turn haptic feedback off. Tap again to confirm."
                },
                onConfirmed = { confirmedState ->
                    if (DualConnectionSafetyPolicy.shouldPromptForVestDisable(
                            vestConnected = DeviceManager.isVestConnected(),
                            glassesConnected = DeviceManager.isGlassesConnected(),
                            requestedVestStateOn = confirmedState
                        )) {
                        withSuppression { switchHaptics.isChecked = true }
                        speak("Safety check. Are smart glasses connected and taking pictures?")
                        showVestDisableSafetyPrompt {
                            applyHapticsToggleFromApp(isChecked = false)
                        }
                        return@confirmSwitchChange
                    }

                    applyHapticsToggleFromApp(confirmedState)
                }
            )
        }

        // Ultrasonic Sensors
        switchSensors.setOnCheckedChangeListener { _, isChecked ->
            if (isSuppressed) return@setOnCheckedChangeListener
            confirmSwitchChange(
                key = "settings:sensors:$isChecked",
                requestedChecked = isChecked,
                control = switchSensors,
                announcement = if (isChecked) {
                    "Turn ultrasonic sensors on. Tap again to confirm."
                } else {
                    "Turn ultrasonic sensors off. Tap again to confirm."
                },
                onConfirmed = { confirmedState ->
                    if (DualConnectionSafetyPolicy.shouldPromptForVestDisable(
                            vestConnected = DeviceManager.isVestConnected(),
                            glassesConnected = DeviceManager.isGlassesConnected(),
                            requestedVestStateOn = confirmedState
                        )) {
                        withSuppression { switchSensors.isChecked = true }
                        speak("Safety check. Are smart glasses connected and taking pictures?")
                        showVestDisableSafetyPrompt {
                            applySensorsToggleFromApp(isChecked = false)
                        }
                        return@confirmSwitchChange
                    }

                    applySensorsToggleFromApp(confirmedState)
                }
            )
        }

        // Audio Feedback (app-local, no vest command)
        switchAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isSuppressed) return@setOnCheckedChangeListener
            confirmSwitchChange(
                key = "settings:audio_feedback:$isChecked",
                requestedChecked = isChecked,
                control = switchAudio,
                announcement = if (isChecked) {
                    "Turn audio feedback on. Tap again to confirm."
                } else {
                    "Turn audio feedback off. Tap again to confirm."
                },
                onConfirmed = { confirmedState ->
                    AudioSettings.setAudioEnabled(this, confirmedState)
                    InteractionLogger.logStateChange("AUDIO", confirmedState, "APP")

                    if (!confirmedState) {
                        interruptSpeech()
                    }
                    if (confirmedState &&
                        AssistiveRuntimeSettings.getMode(this) == AssistiveRuntimeMode.ASSISTIVE_ACTIVE
                    ) {
                        speak("Audio feedback on.")
                    }
                    refreshStatusSummary()
                }
            )
        }

        // Controls only the spoken "No objects detected" phrase.
        switchNoObjectsSpeech.setOnCheckedChangeListener { _, isChecked ->
            if (isSuppressed) return@setOnCheckedChangeListener
            confirmSwitchChange(
                key = "settings:no_objects_tts:$isChecked",
                requestedChecked = isChecked,
                control = switchNoObjectsSpeech,
                announcement = if (isChecked) {
                    "Turn no objects detected announcements on. Tap again to confirm."
                } else {
                    "Turn no objects detected announcements off. Tap again to confirm."
                },
                onConfirmed = { confirmedState ->
                    NoObjectsSpeechSettings.setEnabled(this, confirmedState)
                    InteractionLogger.logStateChange("NO_OBJECTS_TTS", confirmedState, "APP")
                    speak(
                        if (confirmedState) {
                            "No objects detected announcements on."
                        } else {
                            "No objects detected announcements off."
                        }
                    )
                    refreshStatusSummary()
                }
            )
        }
    }

    private fun applySystemToggleFromApp(isChecked: Boolean) {
        lastMismatchStatus = null
        if (!isChecked) {
            DeviceManager.setVestSystemEnabled(false)
            withSuppression {
                switchSystem.isChecked = false
                switchHaptics.isChecked = false
                switchSensors.isChecked = false
            }
            applySubsystemControlLock(false)
            VestCommandSender.sendAllOff()
            speak("System off. Haptics and sensors disabled.")
        } else {
            DeviceManager.setVestSystemEnabled(true)
            withSuppression { switchSystem.isChecked = true }
            applySubsystemControlLock(true)
            VestCommandSender.sendAllOn()
            speak("System on.")
        }
        InteractionLogger.logStateChange("SYSTEM", isChecked, "APP")
        refreshStatusSummary()
    }

    private fun applyHapticsToggleFromApp(isChecked: Boolean) {
        lastMismatchStatus = null
        if (!switchSystem.isChecked) {
            withSuppression { switchHaptics.isChecked = false }
            applySubsystemControlLock(false)
            speak("System power is off. Turn system power on first.")
            return
        }
        withSuppression { switchHaptics.isChecked = isChecked }
        if (isChecked) VestCommandSender.sendHapticsOn() else VestCommandSender.sendHapticsOff()
        speak("Haptic feedback ${if (isChecked) "on" else "off"}.")
        InteractionLogger.logStateChange("HAPTICS", isChecked, "APP")
        refreshStatusSummary()
    }

    private fun applySensorsToggleFromApp(isChecked: Boolean) {
        lastMismatchStatus = null
        if (!switchSystem.isChecked) {
            withSuppression { switchSensors.isChecked = false }
            applySubsystemControlLock(false)
            speak("System power is off. Turn system power on first.")
            return
        }
        withSuppression { switchSensors.isChecked = isChecked }
        if (isChecked) VestCommandSender.sendSensorsOn() else VestCommandSender.sendSensorsOff()
        speak("Ultrasonic sensors ${if (isChecked) "on" else "off"}.")
        InteractionLogger.logStateChange("SENSORS", isChecked, "APP")
        refreshStatusSummary()
    }

    private fun showVestDisableSafetyPrompt(onProceed: () -> Unit) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Safety check")
            .setMessage(
                "Both the vest and smart glasses are connected. Are the smart glasses connected and actively taking pictures?"
            )
            .setCancelable(false)
            .setPositiveButton("Yes") { d, _ ->
                val validated = validateGlassesCaptureForVestDisable()
                if (validated) {
                    onProceed()
                } else {
                    denyVestDisableWithoutChanges(
                        reason = "Vest disable denied because runtime validation failed for glasses capture",
                        spoken = "Safety validation failed. Smart glasses are not connected and taking pictures. No changes were made."
                    )
                }
                d.dismiss()
            }
            .setNegativeButton("No") { d, _ ->
                denyVestDisableWithoutChanges(
                    reason = "Vest disable denied by user response: glasses capture not confirmed",
                    spoken = "No changes were made. Haptics and sensors remain in their current state."
                )
                d.dismiss()
            }
            .show()
    }

    private fun showGlassesDisableSafetyPrompt(onProceed: () -> Unit) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Safety check")
            .setMessage(
                "Both the vest and smart glasses are connected. Are haptics and ultrasonic sensors currently on?"
            )
            .setCancelable(false)
            .setPositiveButton("Yes") { d, _ ->
                val validated = validateVestSafeguardsForGlassesDisable()
                if (validated) {
                    onProceed()
                } else {
                    denyGlassesDisableWithoutChanges(
                        reason = "Glasses capture disable denied because runtime validation failed for vest safeguards",
                        spoken = "Safety validation failed. Haptics and ultrasonic sensors are not both on. Smart glasses picture taking remains enabled."
                    )
                }
                d.dismiss()
            }
            .setNegativeButton("No") { d, _ ->
                denyGlassesDisableWithoutChanges(
                    reason = "Glasses capture disable denied by user response: vest safeguards not confirmed",
                    spoken = "No changes were made. Smart glasses picture taking remains enabled."
                )
                d.dismiss()
            }
            .show()
    }

    private fun validateGlassesCaptureForVestDisable(): Boolean {
        val glassesConnected = DeviceManager.isGlassesConnected()
        val glassesCaptureEnabled = GlassesCommandSender.isCaptureEnabled()
        val validated = DualConnectionSafetyPolicy.isGlassesCaptureValidatedForVestDisable(
            glassesConnected = glassesConnected,
            glassesCaptureEnabled = glassesCaptureEnabled
        )

        InteractionLogger.log(
            "SAFETY_VALIDATION",
            "APP",
            "target=VEST_DISABLE glasses_connected=$glassesConnected glasses_capture_enabled=$glassesCaptureEnabled validated=$validated"
        )
        return validated
    }

    private fun validateVestSafeguardsForGlassesDisable(): Boolean {
        val vestState = DeviceManager.vestHandler?.getCurrentState()
        val vestConnected = DeviceManager.isVestConnected()
        val vestSystemOn = vestState?.systemOn == true
        val hapticsOn = vestState?.hapticsOn == true
        val sensorsOn = vestState?.sensorsOn == true

        val validated = DualConnectionSafetyPolicy.isVestSafeguardsValidatedForGlassesDisable(
            vestConnected = vestConnected,
            vestSystemOn = vestSystemOn,
            hapticsOn = hapticsOn,
            sensorsOn = sensorsOn
        )

        InteractionLogger.log(
            "SAFETY_VALIDATION",
            "APP",
            "target=GLASSES_DISABLE vest_connected=$vestConnected vest_system_on=$vestSystemOn haptics_on=$hapticsOn sensors_on=$sensorsOn validated=$validated"
        )
        return validated
    }

    private fun denyVestDisableWithoutChanges(reason: String, spoken: String) {
        InteractionLogger.log("AUTO_OVERRIDE", "APP", reason)
        syncFromVestState()
        refreshStatusSummary()
        speak(spoken)
    }

    private fun denyGlassesDisableWithoutChanges(reason: String, spoken: String) {
        InteractionLogger.log("AUTO_OVERRIDE", "APP", reason)
        refreshGlassesCaptureControls()
        refreshStatusSummary()
        speak(spoken)
    }

    private fun forceVestSubsystemsOnForSafety(reason: String, spoken: String) {
        lastMismatchStatus = null
        DeviceManager.setVestSystemEnabled(true)
        withSuppression {
            switchSystem.isChecked = true
            switchHaptics.isChecked = true
            switchSensors.isChecked = true
        }
        applySubsystemControlLock(true)

        if (DeviceManager.isVestConnected()) {
            pendingVestForceOnOverrideAfterReconnect = false
            VestCommandSender.sendAllOn()
        } else {
            pendingVestForceOnOverrideAfterReconnect = true
        }

        InteractionLogger.log("AUTO_OVERRIDE", "APP", reason)
        InteractionLogger.logStateChange("HAPTICS", true, "AUTO")
        InteractionLogger.logStateChange("SENSORS", true, "AUTO")
        refreshStatusSummary()
        speak(spoken)
    }

    private fun forceGlassesCaptureOnForSafety(reason: String, spoken: String) {
        val wasEnabled = GlassesCommandSender.isCaptureEnabled()
        GlassesBleManager.setCaptureEnabled(true)
        InteractionLogger.log("AUTO_OVERRIDE", "APP", reason)
        if (!wasEnabled) {
            InteractionLogger.logStateChange("GLASSES_CAPTURE", true, "AUTO")
        }
        refreshGlassesCaptureControls()
        refreshStatusSummary()
        speak(spoken)
    }

    private fun isDualConnectedNow(): Boolean =
        DualConnectionSafetyPolicy.isDualConnected(
            vestConnected = DeviceManager.isVestConnected(),
            glassesConnected = DeviceManager.isGlassesConnected()
        )

    private fun setupAssistiveModeControl() {
        assistiveModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isSuppressed) return@setOnCheckedChangeListener

            val selectedMode = modeFromCheckedId(checkedId) ?: return@setOnCheckedChangeListener
            val currentMode = AssistiveRuntimeSettings.getMode(this)
            if (selectedMode == currentMode) {
                tapActionConfirmation.clear()
                return@setOnCheckedChangeListener
            }

            var confirmed = false
            tapActionConfirmation.confirmOrAnnounce(
                key = "settings:assistive_mode:${selectedMode.name}",
                announcement = modeConfirmationAnnouncement(selectedMode),
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = {
                    confirmed = true
                    applyAssistiveModeSelection(selectedMode)
                }
            )

            if (!confirmed) {
                withSuppression {
                    assistiveModeGroup.check(modeToCheckedId(currentMode))
                }
            }
        }
    }

    private fun setupBackgroundRuntimeSafeguardControl() {
        btnBackgroundRuntimeSafeguard.setOnClickListener {
            val runtimeEnabled = AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)
            if (runtimeEnabled) {
                interruptSpeech()
                speak("Long press and confirm to disable background runtime.")
                return@setOnClickListener
            }

            tapActionConfirmation.confirmOrAnnounce(
                key = "settings:background_runtime_enable",
                announcement = "Enable background runtime. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speak,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    AssistiveRuntimeService.enableBackgroundRuntime(this, source = "SETTINGS")
                    InteractionLogger.log(
                        "STATE",
                        "APP",
                        "BACKGROUND_RUNTIME = ENABLED"
                    )
                    refreshBackgroundRuntimeSafeguardControls()
                    refreshStatusSummary()
                    speak("Background runtime enabled.")
                }
            )
        }

        btnBackgroundRuntimeSafeguard.setOnLongClickListener {
            if (!AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)) {
                speak("Background runtime is already disabled.")
                return@setOnLongClickListener true
            }

            if (isFinishing || isDestroyed) {
                return@setOnLongClickListener true
            }

            AlertDialog.Builder(this)
                .setTitle("Disable background runtime?")
                .setMessage(
                    "This stops background BLE runtime, disconnects active devices, and removes the runtime notification until you re-enable it."
                )
                .setPositiveButton("Disable") { dialog, _ ->
                    AssistiveRuntimeService.disableBackgroundRuntime(this, source = "SETTINGS")
                    InteractionLogger.log(
                        "STATE",
                        "APP",
                        "BACKGROUND_RUNTIME = DISABLED"
                    )
                    refreshBackgroundRuntimeSafeguardControls()
                    refreshStatusSummary()
                    updateConnectionLabel()
                    interruptSpeech()
                    speak("Background runtime disabled.")
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()

            true
        }
    }

    private fun refreshBackgroundRuntimeSafeguardControls() {
        val runtimeEnabled = AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)
        backgroundRuntimeStatusText.text = if (runtimeEnabled) {
            "Background runtime is enabled"
        } else {
            "Background runtime is disabled"
        }
        backgroundRuntimeStatusText.contentDescription = if (runtimeEnabled) {
            "Background runtime is enabled"
        } else {
            "Background runtime is disabled"
        }
        backgroundRuntimeStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (runtimeEnabled) R.color.success_green else R.color.error_red
            )
        )

        btnBackgroundRuntimeSafeguard.text = if (runtimeEnabled) {
            "Long press to disable background runtime"
        } else {
            "Enable background runtime"
        }
        btnBackgroundRuntimeSafeguard.background = ContextCompat.getDrawable(
            this,
            if (runtimeEnabled) {
                R.drawable.danger_button_background
            } else {
                R.drawable.secondary_button_background
            }
        )
        btnBackgroundRuntimeSafeguard.setTextColor(
            ContextCompat.getColor(
                this,
                if (runtimeEnabled) android.R.color.white else R.color.text_primary
            )
        )
    }

    private fun syncAssistiveModeSelection() {
        val mode = AssistiveRuntimeSettings.getMode(this)
        val id = modeToCheckedId(mode)

        withSuppression {
            if (assistiveModeGroup.checkedRadioButtonId != id) {
                assistiveModeGroup.check(id)
            }
        }
    }

    private fun modeFromCheckedId(checkedId: Int): AssistiveRuntimeMode? {
        return when (checkedId) {
            R.id.mode_assistive_active -> AssistiveRuntimeMode.ASSISTIVE_ACTIVE
            R.id.mode_assistive_silent -> AssistiveRuntimeMode.ASSISTIVE_SILENT
            R.id.mode_phone_priority -> AssistiveRuntimeMode.PHONE_PRIORITY
            else -> null
        }
    }

    // ── Vest → App state application ─────────────────────────────────────────

    private fun applyVestState(newState: VestMessageHandler.VestState) {
        val prevSystem  = switchSystem.isChecked
        val prevHaptics = switchHaptics.isChecked
        val prevSensors = switchSensors.isChecked

        withSuppression {
            switchSystem.isChecked  = newState.systemOn
            switchHaptics.isChecked = newState.hapticsOn
            switchSensors.isChecked = newState.sensorsOn
        }
        DeviceManager.setVestSystemEnabled(newState.systemOn)
        applySubsystemControlLock(newState.systemOn)

        val changes = buildList {
            if (newState.systemOn  != prevSystem)  add("System ${onOff(newState.systemOn)}")
            if (newState.hapticsOn != prevHaptics) add("Haptics ${onOff(newState.hapticsOn)}")
            if (newState.sensorsOn != prevSensors) add("Sensors ${onOff(newState.sensorsOn)}")
        }
        if (changes.isNotEmpty()) speak(changes.joinToString(". ") + ".")

        refreshStatusSummary()
    }

    private fun syncFromVestState() {
        val s = DeviceManager.vestHandler?.getCurrentState() ?: return
        applyVestState(s)
    }

    // ── VestListener callbacks ────────────────────────────────────────────────

    override fun onStateChanged(state: VestMessageHandler.VestState) {
        runOnUiThread { applyVestState(state) }
    }

    override fun onConnectionChanged(connected: Boolean) {
        runOnUiThread {
            updateConnectionLabel()
            if (connected) {
                speak("Vest connected.")
                applyPendingVestForceOnOverrideAfterReconnect()
                syncFromVestState()
            } else {
                speak("Vest disconnected.")
                if (pendingVestForceOnOverrideAfterReconnect) {
                    DeviceManager.setVestSystemEnabled(true)
                    withSuppression {
                        switchSystem.isChecked  = true
                        switchHaptics.isChecked = true
                        switchSensors.isChecked = true
                    }
                    applySubsystemControlLock(true)
                } else {
                    DeviceManager.setVestSystemEnabled(false)
                    withSuppression {
                        switchSystem.isChecked  = false
                        switchHaptics.isChecked = false
                        switchSensors.isChecked = false
                    }
                    applySubsystemControlLock(false)
                }
                refreshStatusSummary()
            }
        }
    }

    override fun onDetection(objectName: String, hapticPattern: String?) {}

    override fun onBatteryWarning(label: String, level: Int) {
        runOnUiThread {
            val msg = when (label) {
                "CRITICAL" -> "Warning: vest battery critically low at $level percent. Please charge soon."
                "LOW"      -> "Vest battery low at $level percent."
                else       -> return@runOnUiThread
            }
            speak(msg)
        }
    }

    override fun onSafetyPrompt(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread

            // Ignore cross-device checks outside dual-connected sessions.
            if (!isDualConnectedNow()) {
                VestCommandSender.confirmGlassesOn()
                return@runOnUiThread
            }

            speak(message)
            AlertDialog.Builder(this)
                .setTitle("Safety check")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Yes, glasses are on") { d, _ ->
                    VestCommandSender.confirmGlassesOn()
                    d.dismiss()
                }
                .setNegativeButton("No, glasses are off") { d, _ ->
                    VestCommandSender.confirmGlassesOff()
                    forceVestSubsystemsOnForSafety(
                        reason = "Legacy vest safety prompt denied in dual-connected state",
                        spoken = "Safety override applied. Haptics and sensors remain on."
                    )
                    d.dismiss()
                }
                .show()
        }
    }

    override fun onToggleMismatch(target: String, requestedStateOn: Boolean, physicalStateOn: Boolean) {
        runOnUiThread {
            val label = when (target.uppercase(Locale.US)) {
                "HAPTICS" -> "Haptic feedback"
                "SENSORS" -> "Ultrasonic sensors"
                else -> target
            }
            val requested = onOff(requestedStateOn)
            val physical = onOff(physicalStateOn)

            lastMismatchStatus = "Mismatch: $label requested $requested, physical switch is $physical"
            refreshStatusSummary()
            speak("$label toggle mismatch. App requested $requested but the physical switch is $physical.")
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun initializeConnectionEpochSnapshot() {
        dualConnectionOverrideState = DualConnectionOverridePolicy.initialize(
            vestConnected = DeviceManager.isVestConnected(),
            glassesConnected = DeviceManager.isGlassesConnected()
        )
        pendingVestForceOnOverrideAfterReconnect = false
    }

    private fun handleConnectionTransition() {
        val vestConnected = DeviceManager.isVestConnected()
        val glassesConnected = DeviceManager.isGlassesConnected()

        val decision = DualConnectionOverridePolicy.transition(
            state = dualConnectionOverrideState,
            vestConnected = vestConnected,
            glassesConnected = glassesConnected
        )
        dualConnectionOverrideState = decision.nextState

        if (decision.applyVestForceOnOverride) {
            applyGlassesInitialDisconnectOverride()
        }
        if (decision.applyGlassesCaptureForceOnOverride) {
            applyVestInitialDisconnectOverride()
        }

        if (vestConnected) {
            applyPendingVestForceOnOverrideAfterReconnect()
        }
    }

    private fun applyGlassesInitialDisconnectOverride() {
        val currentVestState = DeviceManager.vestHandler?.getCurrentState()
        val needsForceOn = currentVestState?.let {
            !it.systemOn || !it.hapticsOn || !it.sensorsOn
        } ?: (!switchSystem.isChecked || !switchHaptics.isChecked || !switchSensors.isChecked)

        if (!needsForceOn) {
            return
        }

        val vestConnected = DeviceManager.isVestConnected()

        lastMismatchStatus = null
        DeviceManager.setVestSystemEnabled(true)
        withSuppression {
            switchSystem.isChecked = true
            switchHaptics.isChecked = true
            switchSensors.isChecked = true
        }
        applySubsystemControlLock(true)

        if (vestConnected) {
            pendingVestForceOnOverrideAfterReconnect = false
            VestCommandSender.sendAllOn()
            InteractionLogger.log(
                "AUTO_OVERRIDE",
                "APP",
                "Glasses disconnected after dual-link epoch; vest force-on override applied"
            )
        } else {
            pendingVestForceOnOverrideAfterReconnect = true
            InteractionLogger.log(
                "AUTO_OVERRIDE",
                "APP",
                "Glasses disconnected after dual-link epoch; vest force-on override deferred until reconnect"
            )
        }

        InteractionLogger.logStateChange("HAPTICS", true, "AUTO")
        InteractionLogger.logStateChange("SENSORS", true, "AUTO")
        refreshStatusSummary()
        speak(
            if (vestConnected) {
                "Smart glasses disconnected. Haptics and sensors forced on."
            } else {
                "Smart glasses disconnected. Haptics and sensors override armed for vest reconnection."
            }
        )
    }

    private fun applyPendingVestForceOnOverrideAfterReconnect() {
        if (!pendingVestForceOnOverrideAfterReconnect || !DeviceManager.isVestConnected()) {
            return
        }

        pendingVestForceOnOverrideAfterReconnect = false
        lastMismatchStatus = null
        DeviceManager.setVestSystemEnabled(true)
        withSuppression {
            switchSystem.isChecked = true
            switchHaptics.isChecked = true
            switchSensors.isChecked = true
        }
        applySubsystemControlLock(true)
        VestCommandSender.sendAllOn()

        InteractionLogger.log(
            "AUTO_OVERRIDE",
            "APP",
            "Applying deferred vest force-on override after reconnect"
        )
        InteractionLogger.logStateChange("HAPTICS", true, "AUTO")
        InteractionLogger.logStateChange("SENSORS", true, "AUTO")
        refreshStatusSummary()
    }

    private fun applyVestInitialDisconnectOverride() {
        if (GlassesCommandSender.isCaptureEnabled()) {
            return
        }

        GlassesBleManager.setCaptureEnabled(true)
        InteractionLogger.log(
            "AUTO_OVERRIDE",
            "APP",
            "Vest disconnected after dual-link epoch; glasses capture force-on override applied"
        )
        InteractionLogger.logStateChange("GLASSES_CAPTURE", true, "AUTO")
        refreshGlassesCaptureControls()
        refreshStatusSummary()
        speak("Vest disconnected. Smart glasses picture taking and inference forced on.")
    }

    private fun updateConnectionLabel() {
        val connected = DeviceManager.isVestConnected()
        connectionStatusText.text =
            if (connected) "Vest connected" else "Vest not connected"
        connectionStatusText.contentDescription =
            if (connected) "Navigation vest is connected" else "Navigation vest is not connected"
        connectionStatusText.setTextColor(
            ContextCompat.getColor(this,
                if (connected) R.color.success_green else R.color.error_red)
        )
    }

    private fun refreshStatusSummary() {
        val mode = AssistiveRuntimeSettings.getMode(this).displayName
        val runtime = onOff(AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this))
        val sys     = onOff(switchSystem.isChecked)
        val haptics = onOff(switchHaptics.isChecked)
        val sensors = onOff(switchSensors.isChecked)
        val audio   = onOff(switchAudio.isChecked)
        val noObjectsSpeech = onOff(NoObjectsSpeechSettings.isEnabled(this))
        val capture = onOff(GlassesCommandSender.isCaptureEnabled())
        val summary = "Mode: $mode  |  Background Runtime: $runtime  |  System: $sys  |  Haptics: $haptics  |  Sensors: $sensors  |  Audio: $audio  |  No Objects TTS: $noObjectsSpeech  |  Glasses Capture: $capture"
        val mismatch = lastMismatchStatus

        statusText.text = if (mismatch.isNullOrBlank()) {
            summary
        } else {
            "$summary\n$mismatch"
        }
        statusText.contentDescription = if (mismatch.isNullOrBlank()) {
            "Current state: mode $mode, background runtime $runtime, System $sys, Haptics $haptics, Sensors $sensors, Audio feedback $audio, no objects announcements $noObjectsSpeech, Glasses capture $capture"
        } else {
            "Current state: mode $mode, background runtime $runtime, System $sys, Haptics $haptics, Sensors $sensors, Audio feedback $audio, no objects announcements $noObjectsSpeech, Glasses capture $capture. $mismatch"
        }
    }

    private fun applySubsystemControlLock(systemOn: Boolean) {
        val enabled = systemOn
        switchHaptics.isEnabled = enabled
        switchSensors.isEnabled = enabled

        val alpha = if (enabled) 1.0f else 0.55f
        switchHaptics.alpha = alpha
        switchSensors.alpha = alpha
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    // ── TTS ───────────────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            speak("Settings page. Adjust assistive mode, system power, haptics, sensors, audio feedback, and glasses picture taking.")
            syncFromVestState()
            refreshStatusSummary()
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
}
