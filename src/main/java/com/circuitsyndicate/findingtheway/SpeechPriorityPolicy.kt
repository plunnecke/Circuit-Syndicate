package com.circuitsyndicate.findingtheway

/**
 * Speech prioritization policy shared by detection speech paths.
 */
object SpeechPriorityPolicy {

    const val URGENT_DISTANCE_FEET = 5.0f
    const val URGENT_PRIORITY = -1

    data class OrderingKey(
        val effectivePriority: Int,
        val distanceTieBreak: Float
    )

    fun isUrgentDistance(distanceFeet: Float?): Boolean {
        return distanceFeet != null && distanceFeet.isFinite() && distanceFeet <= URGENT_DISTANCE_FEET
    }

    fun withUrgencyPriority(basePriority: Int, distanceFeet: Float?): Int {
        return if (isUrgentDistance(distanceFeet)) URGENT_PRIORITY else basePriority
    }

    fun orderingKey(basePriority: Int, distanceFeet: Float?): OrderingKey {
        val urgent = isUrgentDistance(distanceFeet)
        return OrderingKey(
            effectivePriority = if (urgent) URGENT_PRIORITY else basePriority,
            distanceTieBreak = if (urgent) distanceFeet ?: Float.MAX_VALUE else Float.MAX_VALUE
        )
    }

    fun spokenDistancePhrase(distanceFeet: Float?, fallbackCategory: String = "nearby"): String {
        if (distanceFeet == null || !distanceFeet.isFinite()) {
            return fallbackCategory
        }

        val roundedFeet = distanceFeet.toInt().coerceAtLeast(1)
        return "about $roundedFeet feet away"
    }
}
