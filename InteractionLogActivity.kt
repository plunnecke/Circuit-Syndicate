package com.circuitsyndicate.findingtheway

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class InteractionLogActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: InteractionLogAdapter
    private lateinit var btnClear: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnExportEvidence: Button
    private lateinit var btnStartSession: Button
    private val tapActionConfirmation = TapActionConfirmation()
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_interaction_log)

        recyclerView = findViewById(R.id.log_recycler_view)
        btnClear     = findViewById(R.id.btn_clear_log)
        btnRefresh   = findViewById(R.id.btn_refresh_log)
        btnExportEvidence = findViewById(R.id.btn_export_evidence)
        btnStartSession = findViewById(R.id.btn_start_session)

        adapter = InteractionLogAdapter { clickedLine ->
            speakLine(clickedLine)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        tts = TextToSpeech(this, this)

        btnClear.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "log:clear",
                announcement = "Clear interaction log. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speakAnnouncement,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    InteractionLogger.clear()
                    adapter.submitList(emptyList())
                }
            )
        }
        btnRefresh.setOnClickListener {
            interruptSpeech()
            tapActionConfirmation.clear()
            refresh()
        }
        btnStartSession.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "log:start_session",
                announcement = "Start a new evidence session. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speakAnnouncement,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    val newSessionId = InteractionLogger.startEvidenceSession(
                        reason = "USER_STARTED_SESSION",
                        clearExisting = true
                    )
                    Toast.makeText(this, "Evidence session started: $newSessionId", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            )
        }
        btnExportEvidence.setOnClickListener {
            tapActionConfirmation.confirmOrAnnounce(
                key = "log:export_evidence",
                announcement = "Export session evidence. Tap again to confirm.",
                interruptSpeech = ::interruptSpeech,
                speak = ::speakAnnouncement,
                onConfirmed = {
                    tapActionConfirmation.clear()
                    val exported = InteractionLogger.exportEvidence(this)
                    if (exported != null) {
                        Toast.makeText(this, "Evidence exported: ${exported.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Evidence export failed", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        refresh()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.US
        }
    }

    private fun refresh() {
        adapter.submitList(InteractionLogger.getLogs())
    }

    private fun speakLine(line: String) {
        if (!AssistiveRuntimeSettings.isAssistiveSpeechAllowed(this)) {
            Toast.makeText(
                this,
                "Speech output is disabled in the current assistive mode.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!ttsReady) {
            Toast.makeText(this, "Text-to-speech is not ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(
            line,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "interaction_log_${System.currentTimeMillis()}"
        )
    }

    private fun speakAnnouncement(message: String) {
        if (!AssistiveRuntimeSettings.isAssistiveSpeechAllowed(this) || !ttsReady) {
            return
        }
        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "interaction_log_announce_${System.currentTimeMillis()}"
        )
    }

    private fun interruptSpeech() {
        tts?.stop()
        GlassesBleManager.stopSpeech()
    }

    override fun onDestroy() {
        tapActionConfirmation.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }
}
