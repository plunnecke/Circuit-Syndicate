package com.circuitsyndicate.findingtheway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * TTS demo. Will be replaced by mobile app class
 * 
 * Logic hierarchy will be retained 
 *
 * Priority order for speech:
 * 1. Moving + nearby objects (highest threat)
 * 2. Stationary nearby objects
 * 3. Moving far objects
 * 4. Stationary far objects
 *
 */
class DetectionSpeaker(context: Context) : TextToSpeech.OnInitListener {

    interface TelemetryListener {
        fun onUtteranceStart(utteranceId: String)
        fun onUtteranceDone(utteranceId: String)
        fun onUtteranceError(utteranceId: String, errorCode: Int?)
    }

    companion object {
        private const val TAG = "DetectionSpeaker"
        private const val MAX_SPOKEN_ITEMS = 7   // Cap speech at 7 items
        private const val SPEECH_RATE = 1.15f    
        private const val PITCH = 1.0f

        internal fun shouldAttemptDetectionSpeech(
            audioEnabled: Boolean,
            mode: AssistiveRuntimeMode = AssistiveRuntimeMode.ASSISTIVE_ACTIVE
        ): Boolean = audioEnabled && mode == AssistiveRuntimeMode.ASSISTIVE_ACTIVE

        internal fun shouldQueueDetectionSpeech(
            audioEnabled: Boolean,
            ttsReady: Boolean,
            mode: AssistiveRuntimeMode = AssistiveRuntimeMode.ASSISTIVE_ACTIVE
        ): Boolean = shouldAttemptDetectionSpeech(audioEnabled, mode) && !ttsReady

        internal fun shouldSpeakDetectionSpeech(
            audioEnabled: Boolean,
            ttsReady: Boolean,
            mode: AssistiveRuntimeMode = AssistiveRuntimeMode.ASSISTIVE_ACTIVE
        ): Boolean = shouldAttemptDetectionSpeech(audioEnabled, mode) && ttsReady

        internal fun shouldSpeakNoObjectsPhrase(
            detectionCount: Int,
            noObjectsSpeechEnabled: Boolean
        ): Boolean = detectionCount > 0 || noObjectsSpeechEnabled
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeech: Pair<List<MotionTracker.TrackedDetection>, Map<Int, DistanceEstimator.DistanceResult>>? = null
    private var pendingLegacySpeech: List<Detection>? = null
    private var modeReceiverRegistered = false
    @Volatile private var telemetryListener: TelemetryListener? = null

    private val modeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AssistiveRuntimeSettings.ACTION_MODE_CHANGED) return
            val mode = AssistiveRuntimeSettings.getMode(appContext)
            if (mode != AssistiveRuntimeMode.ASSISTIVE_ACTIVE) {
                pendingSpeech = null
                pendingLegacySpeech = null
                stop()
            }
        }
    }

    // Backward-compatible speech contract used by existing GlassesImagePipeline.
    enum class MotionState {
        APPROACHING,
        MOVING_AWAY,
        CROSSING_LEFT,
        CROSSING_RIGHT,
        STATIONARY,
        UNKNOWN
    }

    enum class ThreatLevel {
        CRITICAL,
        HIGH,
        MODERATE,
        LOW,
        UNKNOWN
    }

    data class Detection(
        val label: String,
        val motionState: MotionState,
        val distanceFeet: Float? = null,
        val distanceCategory: String = "nearby",
        val threatLevel: ThreatLevel = ThreatLevel.UNKNOWN
    )

    init {
        tts = TextToSpeech(context, this)
        registerModeReceiver()
    }

    private fun registerModeReceiver() {
        if (modeReceiverRegistered) return

        val filter = IntentFilter(AssistiveRuntimeSettings.ACTION_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(modeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(modeReceiver, filter)
        }
        modeReceiverRegistered = true
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(SPEECH_RATE)
            tts?.setPitch(PITCH)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId.isNullOrBlank()) return
                    telemetryListener?.onUtteranceStart(utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId.isNullOrBlank()) return
                    telemetryListener?.onUtteranceDone(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId.isNullOrBlank()) return
                    telemetryListener?.onUtteranceError(utteranceId, null)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId.isNullOrBlank()) return
                    telemetryListener?.onUtteranceError(utteranceId, errorCode)
                }
            })
            isReady = true
            Log.d(TAG, "TTS initialized successfully")

            pendingSpeech?.let { (dets, dists) ->
                pendingSpeech = null
                speak(dets, dists)
            }
            pendingLegacySpeech?.let { dets ->
                pendingLegacySpeech = null
                speak(dets)
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    fun setTelemetryListener(listener: TelemetryListener?) {
        telemetryListener = listener
    }

    /** Backward-compatible overload for legacy Detection payloads. */
    fun speak(detections: List<Detection>): String? {
        val audioEnabled = AudioSettings.isAudioEnabled(appContext)
        val mode = AssistiveRuntimeSettings.getMode(appContext)
        if (!shouldAttemptDetectionSpeech(audioEnabled, mode)) {
            pendingLegacySpeech = null
            Log.d(TAG, "Detection speech blocked by runtime mode or audio setting")
            return null
        }

        val noObjectsSpeechEnabled = NoObjectsSpeechSettings.isEnabled(appContext)
        if (!shouldSpeakNoObjectsPhrase(detections.size, noObjectsSpeechEnabled)) {
            pendingLegacySpeech = null
            Log.d(TAG, "Skipping no-objects speech due to no-objects toggle")
            return null
        }

        if (shouldQueueDetectionSpeech(audioEnabled, isReady, mode)) {
            Log.w(TAG, "TTS not ready yet, queuing compatibility speech")
            pendingLegacySpeech = detections
            return null
        }

        if (!shouldSpeakDetectionSpeech(audioEnabled, isReady, mode)) {
            Log.d(TAG, "Skipping compatibility detection speech")
            return null
        }

        val text = buildSpeechText(detections)
        Log.d(TAG, "Speaking: $text")
        val utteranceId = "detection_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return utteranceId
    }

    /**
     *
     * @param detections Tracked detections with motion state
     * @param distances Distance results indexed by detection position
     */
    fun speak(
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): String? {
        val audioEnabled = AudioSettings.isAudioEnabled(appContext)
        val mode = AssistiveRuntimeSettings.getMode(appContext)
        if (!shouldAttemptDetectionSpeech(audioEnabled, mode)) {
            pendingSpeech = null
            Log.d(TAG, "Detection speech blocked by runtime mode or audio setting")
            return null
        }

        val noObjectsSpeechEnabled = NoObjectsSpeechSettings.isEnabled(appContext)
        if (!shouldSpeakNoObjectsPhrase(detections.size, noObjectsSpeechEnabled)) {
            pendingSpeech = null
            Log.d(TAG, "Skipping no-objects speech due to no-objects toggle")
            return null
        }

        if (shouldQueueDetectionSpeech(audioEnabled, isReady, mode)) {
            Log.w(TAG, "TTS not ready yet, queuing speech")
            pendingSpeech = Pair(detections, distances)
            return null
        }

        if (!shouldSpeakDetectionSpeech(audioEnabled, isReady, mode)) {
            Log.d(TAG, "Skipping detection speech")
            return null
        }

        val text = buildSpeechText(detections, distances)
        Log.d(TAG, "Speaking: $text")

        val utteranceId = "detection_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return utteranceId
    }

    /**
     * Build prioritized natural language text from detections.
     */
    fun buildSpeechText(
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ): String {
        if (detections.isEmpty()) {
            return "No objects detected."
        }

        // Create speech items with priority scores
        // Lower = higher priority. Urgent close-range items (<= 5 ft)
        // are promoted ahead of all non-urgent items.
        data class SpeechItem(
            val label: String,
            val distanceCategory: String,
            val distanceFeet: Float?,
            val motionState: MotionTracker.MotionState,
            val urgent: Boolean,
            val priority: Int,
            val distanceTieBreak: Float,
            val sourceIndex: Int
        )

        val items = detections.mapIndexed { idx, det ->
            val dist = distances[idx]
            val distFeet = dist?.distanceFeet

            // Use the fused threat level directly for priority ordering.
            // This replaces the previous ad-hoc distance+motion heuristic
            // and ensures the classification itself drives prioritisation.
            val basePriority = when (det.threatLevel) {
                MotionTracker.ThreatLevel.CRITICAL -> 0
                MotionTracker.ThreatLevel.HIGH     -> 1
                MotionTracker.ThreatLevel.MODERATE -> 2
                MotionTracker.ThreatLevel.LOW      -> 3
                MotionTracker.ThreatLevel.UNKNOWN  -> 4
            }

            // Build spoken distance text: prefer the fused distanceFeet
            // carried on the detection itself, fall back to estimator result.
            val feet = det.distanceFeet ?: distFeet
            val urgent = SpeechPriorityPolicy.isUrgentDistance(feet)
            val spokenDist = SpeechPriorityPolicy.spokenDistancePhrase(feet, dist?.distanceCategory ?: "nearby")
            val ordering = SpeechPriorityPolicy.orderingKey(basePriority, feet)

            SpeechItem(
                label = det.label,
                distanceCategory = spokenDist,
                distanceFeet = feet,
                motionState = det.motionState,
                urgent = urgent,
                priority = ordering.effectivePriority,
                distanceTieBreak = ordering.distanceTieBreak,
                sourceIndex = idx
            )
        }.sortedWith(
            compareBy<SpeechItem> { it.priority }
                .thenBy { it.distanceTieBreak }
                .thenBy { it.sourceIndex }
        )


        data class GroupKey(
            val label: String,
            val distCat: String,
            val motion: MotionTracker.MotionState,
            val urgent: Boolean
        )

        val groups = items.groupBy {
            GroupKey(it.label, it.distanceCategory, it.motionState, it.urgent)
        }

        data class PhraseRecord(val text: String, val urgent: Boolean)

        val phraseRecords = mutableListOf<PhraseRecord>()

        for ((key, group) in groups) {
            if (phraseRecords.size >= MAX_SPOKEN_ITEMS) break

            val count = group.size
            val spokenLabel = key.label.lowercase(Locale.US)
            val noun = if (count == 1) {
                articleFor(spokenLabel)
            } else {
                "$count ${pluralize(spokenLabel)}"
            }

            val motionPhrase = when (key.motion) {
                MotionTracker.MotionState.APPROACHING -> "approaching"
                MotionTracker.MotionState.MOVING_AWAY -> "moving away"
                MotionTracker.MotionState.CROSSING_LEFT -> "moving to your left"
                MotionTracker.MotionState.CROSSING_RIGHT -> "moving to your right"
                MotionTracker.MotionState.UNKNOWN -> "with unclear motion"
                else -> ""
            }

            val phrase = if (motionPhrase.isNotEmpty()) {
                "$noun $motionPhrase, ${key.distCat}"
            } else {
                "$noun ${key.distCat}"
            }

            phraseRecords.add(PhraseRecord(phrase, key.urgent))
        }

        val urgentPhrases = phraseRecords.filter { it.urgent }.map { it.text }
        val normalPhrases = phraseRecords.filterNot { it.urgent }.map { it.text }

        return composeSpeech(urgentPhrases, normalPhrases)
    }

    private fun buildSpeechText(detections: List<Detection>): String {
        if (detections.isEmpty()) return "No objects detected."

        data class LegacySpeechItem(
            val phrase: String,
            val urgent: Boolean,
            val priority: Int,
            val distanceFeet: Float?,
            val distanceTieBreak: Float,
            val sourceIndex: Int
        )

        val ordered = detections.mapIndexed { idx, det ->
            val basePriority = when (det.threatLevel) {
                ThreatLevel.CRITICAL -> 0
                ThreatLevel.HIGH -> 1
                ThreatLevel.MODERATE -> 2
                ThreatLevel.LOW -> 3
                ThreatLevel.UNKNOWN -> 4
            }

            val urgent = SpeechPriorityPolicy.isUrgentDistance(det.distanceFeet)
            val ordering = SpeechPriorityPolicy.orderingKey(basePriority, det.distanceFeet)

            val spokenLabel = det.label.lowercase(Locale.US)
            val noun = articleFor(spokenLabel)
            val motionPhrase = when (det.motionState) {
                MotionState.APPROACHING -> "approaching"
                MotionState.MOVING_AWAY -> "moving away"
                MotionState.CROSSING_LEFT -> "moving to your left"
                MotionState.CROSSING_RIGHT -> "moving to your right"
                MotionState.STATIONARY -> ""
                MotionState.UNKNOWN -> "with unclear motion"
            }
            val distanceText = SpeechPriorityPolicy.spokenDistancePhrase(det.distanceFeet, det.distanceCategory)

            val phrase = if (motionPhrase.isNotEmpty()) {
                "$noun $motionPhrase, $distanceText"
            } else {
                "$noun $distanceText"
            }

            LegacySpeechItem(
                phrase = phrase,
                urgent = urgent,
                priority = ordering.effectivePriority,
                distanceFeet = det.distanceFeet,
                distanceTieBreak = ordering.distanceTieBreak,
                sourceIndex = idx
            )
        }.sortedWith(
            compareBy<LegacySpeechItem> { it.priority }
                .thenBy { it.distanceTieBreak }
                .thenBy { it.sourceIndex }
        )

        val urgentPhrases = ordered.filter { it.urgent }
            .take(MAX_SPOKEN_ITEMS)
            .map { it.phrase }

        val remainingSlots = (MAX_SPOKEN_ITEMS - urgentPhrases.size).coerceAtLeast(0)
        val normalPhrases = if (remainingSlots > 0) {
            ordered.filterNot { it.urgent }
                .take(remainingSlots)
                .map { it.phrase }
        } else {
            emptyList()
        }

        return composeSpeech(urgentPhrases, normalPhrases)
    }

    private fun composeSpeech(urgentPhrases: List<String>, normalPhrases: List<String>): String {
        return if (urgentPhrases.isNotEmpty()) {
            val urgentSentence = formatPhraseList(urgentPhrases, "Warning:")
            if (normalPhrases.isNotEmpty()) {
                "$urgentSentence ${formatPhraseList(normalPhrases, "I also see")}".trim()
            } else {
                urgentSentence
            }
        } else {
            formatPhraseList(normalPhrases, "I see")
        }
    }

    private fun formatPhraseList(phrases: List<String>, intro: String): String {
        if (phrases.isEmpty()) return "No objects detected."
        return if (phrases.size == 1) {
            "$intro ${phrases[0]}."
        } else {
            val allButLast = phrases.dropLast(1).joinToString(", ")
            "$intro $allButLast, and ${phrases.last()}."
        }
    }

    // Grammar correction
    private fun articleFor(noun: String): String {
        val vowels = "aeiou"
        val article = if (noun.isNotEmpty() && noun[0].lowercaseChar() in vowels) "an" else "a"
        return "$article $noun"
    }

    
    private fun pluralize(noun: String): String {
        val normalized = noun.lowercase(Locale.US)
        return when {
            normalized.endsWith("s") || normalized.endsWith("sh") || normalized.endsWith("ch") -> "${normalized}es"
            normalized.endsWith("y") && normalized.length > 1 && normalized[normalized.length - 2] !in "aeiou" -> {
                "${normalized.dropLast(1)}ies"
            }
            normalized == "person" -> "people"
            normalized == "mouse" -> "mice"
            normalized == "knife" -> "knives"
            else -> "${normalized}s"
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        telemetryListener = null

        if (modeReceiverRegistered) {
            try {
                appContext.unregisterReceiver(modeReceiver)
            } catch (_: Exception) {
            }
            modeReceiverRegistered = false
        }
    }
}

