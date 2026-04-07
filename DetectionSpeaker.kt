package com.esp32.camera

import android.content.Context
import android.speech.tts.TextToSpeech
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

    companion object {
        private const val TAG = "DetectionSpeaker"
        private const val MAX_SPOKEN_ITEMS = 7   // Cap speech at 7 items
        private const val SPEECH_RATE = 1.15f    
        private const val PITCH = 1.0f
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingSpeech: Pair<List<MotionTracker.TrackedDetection>, Map<Int, DistanceEstimator.DistanceResult>>? = null

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(SPEECH_RATE)
            tts?.setPitch(PITCH)
            isReady = true
            Log.d(TAG, "TTS initialized successfully")

            pendingSpeech?.let { (dets, dists) ->
                pendingSpeech = null
                speak(dets, dists)
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     *
     * @param detections Tracked detections with motion state
     * @param distances Distance results indexed by detection position
     */
    fun speak(
        detections: List<MotionTracker.TrackedDetection>,
        distances: Map<Int, DistanceEstimator.DistanceResult>
    ) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready yet, queuing speech")
            pendingSpeech = Pair(detections, distances)
            return
        }

        val text = buildSpeechText(detections, distances)
        Log.d(TAG, "Speaking: $text")

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "detection_${System.currentTimeMillis()}")
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
        // Lower = higher priority — now driven by the fused ThreatLevel
        // from MotionTracker which already incorporates distance.
        data class SpeechItem(
            val label: String,
            val distanceCategory: String,
            val motionState: MotionTracker.MotionState,
            val priority: Int  
        )

        val items = detections.mapIndexed { idx, det ->
            val dist = distances[idx]
            val distFeet = dist?.distanceFeet

            // Use the fused threat level directly for priority ordering.
            // This replaces the previous ad-hoc distance+motion heuristic
            // and ensures the classification itself drives prioritisation.
            val priority = when (det.threatLevel) {
                MotionTracker.ThreatLevel.CRITICAL -> 0
                MotionTracker.ThreatLevel.HIGH     -> 1
                MotionTracker.ThreatLevel.MODERATE -> 2
                MotionTracker.ThreatLevel.LOW      -> 3
                MotionTracker.ThreatLevel.UNKNOWN  -> 4
            }

            // Build spoken distance text: prefer the fused distanceFeet
            // carried on the detection itself, fall back to estimator result.
            val feet = det.distanceFeet ?: distFeet
            val spokenDist = if (feet != null) {
                "about ${feet.toInt()} feet away"
            } else {
                dist?.distanceCategory ?: "nearby"
            }

            SpeechItem(det.label, spokenDist, det.motionState, priority)
        }.sortedBy { it.priority }


        data class GroupKey(val label: String, val distCat: String, val motion: MotionTracker.MotionState)

        val groups = items.groupBy { GroupKey(it.label, it.distanceCategory, it.motionState) }

        val phrases = mutableListOf<String>()

        for ((key, group) in groups) {
            if (phrases.size >= MAX_SPOKEN_ITEMS) break

            val count = group.size
            val noun = if (count == 1) {
                articleFor(key.label)
            } else {
                "$count ${pluralize(key.label)}"
            }

            val motionPhrase = when (key.motion) {
                MotionTracker.MotionState.APPROACHING -> "approaching"
                MotionTracker.MotionState.MOVING_AWAY -> "moving away"
                MotionTracker.MotionState.CROSSING_LEFT -> "moving to your left"
                MotionTracker.MotionState.CROSSING_RIGHT -> "moving to your right"
                else -> ""
            }

            val phrase = if (motionPhrase.isNotEmpty()) {
                "$noun $motionPhrase, ${key.distCat}"
            } else {
                "$noun ${key.distCat}"
            }

            phrases.add(phrase)
        }

        return if (phrases.size == 1) {
            "I see ${phrases[0]}."
        } else {
            val allButLast = phrases.dropLast(1).joinToString(", ")
            "I see $allButLast, and ${phrases.last()}."
        }
    }

    // Grammar correction
    private fun articleFor(noun: String): String {
        val vowels = "aeiou"
        val article = if (noun.isNotEmpty() && noun[0].lowercaseChar() in vowels) "an" else "a"
        return "$article $noun"
    }

    
    private fun pluralize(noun: String): String {
        return when {
            noun.endsWith("s") || noun.endsWith("sh") || noun.endsWith("ch") -> "${noun}es"
            noun.endsWith("y") && noun.length > 1 && noun[noun.length - 2] !in "aeiou" -> {
                "${noun.dropLast(1)}ies"
            }
            noun == "person" -> "people"
            noun == "mouse" -> "mice"
            noun == "knife" -> "knives"
            else -> "${noun}s"
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
    }
}
