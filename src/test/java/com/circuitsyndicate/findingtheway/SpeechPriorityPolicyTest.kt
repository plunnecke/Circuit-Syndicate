package com.circuitsyndicate.findingtheway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPriorityPolicyTest {

    private data class OrderingProbe(
        val id: String,
        val motion: String,
        val basePriority: Int,
        val distanceFeet: Float?
    )

    private data class RankedProbe(
        val id: String,
        val effectivePriority: Int,
        val distanceTieBreak: Float,
        val sourceIndex: Int
    )

    private fun orderedIds(vararg probes: OrderingProbe): List<String> {
        return probes.mapIndexed { index, probe ->
            val key = SpeechPriorityPolicy.orderingKey(
                basePriority = probe.basePriority,
                distanceFeet = probe.distanceFeet
            )
            RankedProbe(
                id = "${probe.motion}:${probe.id}",
                effectivePriority = key.effectivePriority,
                distanceTieBreak = key.distanceTieBreak,
                sourceIndex = index
            )
        }.sortedWith(
            compareBy<RankedProbe> { it.effectivePriority }
                .thenBy { it.distanceTieBreak }
                .thenBy { it.sourceIndex }
        ).map { it.id }
    }

    @Test
    fun urgent_distance_threshold_is_inclusive_at_five_feet() {
        assertTrue(SpeechPriorityPolicy.isUrgentDistance(5.0f))
        assertTrue(SpeechPriorityPolicy.isUrgentDistance(3.2f))
        assertFalse(SpeechPriorityPolicy.isUrgentDistance(5.01f))
        assertFalse(SpeechPriorityPolicy.isUrgentDistance(null))
    }

    @Test
    fun non_finite_distance_values_are_not_treated_as_urgent() {
        assertFalse(SpeechPriorityPolicy.isUrgentDistance(Float.NaN))
        assertFalse(SpeechPriorityPolicy.isUrgentDistance(Float.POSITIVE_INFINITY))
        assertFalse(SpeechPriorityPolicy.isUrgentDistance(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun urgency_priority_overrides_base_threat_priority() {
        val urgentModerate = SpeechPriorityPolicy.withUrgencyPriority(basePriority = 2, distanceFeet = 4.5f)
        val nonUrgentCritical = SpeechPriorityPolicy.withUrgencyPriority(basePriority = 0, distanceFeet = 9.0f)

        assertTrue(urgentModerate < nonUrgentCritical)
        assertEquals(-1, urgentModerate)
        assertEquals(0, nonUrgentCritical)
    }

    @Test
    fun close_approaching_is_spoken_before_far_critical() {
        val ordered = orderedIds(
            OrderingProbe("far-critical", "stationary", basePriority = 0, distanceFeet = 12.0f),
            OrderingProbe("close-approaching", "approaching", basePriority = 2, distanceFeet = 4.5f)
        )

        assertEquals(
            listOf("approaching:close-approaching", "stationary:far-critical"),
            ordered
        )
    }

    @Test
    fun close_stationary_is_spoken_before_far_critical() {
        val ordered = orderedIds(
            OrderingProbe("far-critical", "approaching", basePriority = 0, distanceFeet = 11.0f),
            OrderingProbe("close-stationary", "stationary", basePriority = 3, distanceFeet = 4.8f)
        )

        assertEquals(
            listOf("stationary:close-stationary", "approaching:far-critical"),
            ordered
        )
    }

    @Test
    fun close_moving_away_is_spoken_before_far_critical() {
        val ordered = orderedIds(
            OrderingProbe("far-critical", "approaching", basePriority = 0, distanceFeet = 10.0f),
            OrderingProbe("close-moving-away", "moving-away", basePriority = 3, distanceFeet = 4.9f)
        )

        assertEquals(
            listOf("moving-away:close-moving-away", "approaching:far-critical"),
            ordered
        )
    }

    @Test
    fun mixed_distance_groups_keep_all_close_items_first() {
        val ordered = orderedIds(
            OrderingProbe("far-critical", "approaching", basePriority = 0, distanceFeet = 12.0f),
            OrderingProbe("close-moving-away", "moving-away", basePriority = 3, distanceFeet = 4.8f),
            OrderingProbe("far-high", "stationary", basePriority = 1, distanceFeet = 8.0f),
            OrderingProbe("close-stationary", "stationary", basePriority = 2, distanceFeet = 5.0f),
            OrderingProbe("close-approaching", "approaching", basePriority = 2, distanceFeet = 2.2f)
        )

        assertEquals(
            listOf(
                "approaching:close-approaching",
                "moving-away:close-moving-away",
                "stationary:close-stationary",
                "approaching:far-critical",
                "stationary:far-high"
            ),
            ordered
        )
    }

    @Test
    fun spoken_distance_phrase_uses_relative_distance_wording() {
        assertEquals(
            "about 4 feet away",
            SpeechPriorityPolicy.spokenDistancePhrase(4.2f, fallbackCategory = "nearby")
        )
        assertEquals(
            "about 6 feet away",
            SpeechPriorityPolicy.spokenDistancePhrase(6.8f, fallbackCategory = "nearby")
        )
    }

    @Test
    fun spoken_distance_phrase_falls_back_for_null_or_non_finite_values() {
        assertEquals(
            "unknown",
            SpeechPriorityPolicy.spokenDistancePhrase(null, fallbackCategory = "unknown")
        )
        assertEquals(
            "unknown",
            SpeechPriorityPolicy.spokenDistancePhrase(Float.NaN, fallbackCategory = "unknown")
        )
    }
}
