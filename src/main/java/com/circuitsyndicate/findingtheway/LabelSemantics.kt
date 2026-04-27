package com.circuitsyndicate.findingtheway

import java.util.Locale

/**
 * Shared label semantics used by detector ranking and motion matching.
 */
object LabelSemantics {

    enum class Compatibility {
        EXACT,
        HIERARCHY,
        MISMATCH
    }

    private val aliasToCanonical = mapOf(
        "office building" to "building",
        "tree house" to "building",
        "window blind" to "window",
        "door handle" to "door",
        "street light" to "street light",
        "traffic sign" to "traffic sign",
        "stop sign" to "traffic sign",
        "human body" to "person",
        "human head" to "person",
        "human face" to "person",
        "human arm" to "person",
        "human hand" to "person",
        "human leg" to "person",
        "human foot" to "person",
        "human eye" to "person",
        "human ear" to "person",
        "human nose" to "person",
        "human mouth" to "person",
        "human hair" to "person",
        "stationary bicycle" to "bicycle",
        "golf cart" to "cart",
        "houseplant" to "plant",
        "christmas tree" to "tree",
        "palm tree" to "tree",
        "lavender plant" to "plant",
        "training bench" to "bench",
        "wall clock" to "clock",
        "tv" to "television",
        "monitor" to "television"
    )

    private val labelFamily = mapOf(
        "person" to "person",
        "man" to "person",
        "woman" to "person",
        "boy" to "person",
        "girl" to "person",

        "car" to "vehicle",
        "truck" to "vehicle",
        "bus" to "vehicle",
        "motorcycle" to "vehicle",
        "bicycle" to "vehicle",
        "cart" to "vehicle",

        "dog" to "animal",
        "cat" to "animal",
        "bird" to "animal",
        "carnivore" to "animal",
        "cattle" to "animal",

        "building" to "structure",
        "house" to "structure",
        "window" to "structure",
        "door" to "structure",
        "wall" to "structure",
        "roof" to "structure",
        "ceiling" to "structure",
        "floor" to "structure",
        "stairs" to "structure",
        "fence" to "structure",
        "bridge" to "structure",

        "tree" to "flora",
        "plant" to "flora",
        "houseplant" to "flora",

        "bench" to "furniture",
        "chair" to "furniture",
        "couch" to "furniture",
        "sofa bed" to "furniture",
        "table" to "furniture",
        "dining table" to "furniture",
        "coffee table" to "furniture",
        "bookcase" to "furniture",
        "cabinet" to "furniture",
        "desk" to "furniture",

        "street light" to "street_fixture",
        "traffic light" to "street_fixture",
        "traffic sign" to "street_fixture",
        "parking meter" to "street_fixture",
        "lamp" to "street_fixture",
        "pole" to "street_fixture"
    )

    private val stationaryFamilies = setOf("structure", "flora", "street_fixture", "furniture")

    private val stationaryAnchorCanonicalLabels = setOf(
        "building", "house", "window", "door", "wall", "roof", "ceiling", "floor",
        "tree", "plant", "bench", "chair", "couch", "sofa bed", "table", "dining table",
        "coffee table", "desk", "cabinet", "bookcase", "fence", "bridge", "stairs",
        "street light", "traffic light", "traffic sign", "parking meter", "lamp", "pole",
        "clock", "television", "television monitor", "refrigerator", "oven", "sink", "toilet"
    )

    private val stationaryKeywordFragments = listOf(
        "window",
        "building",
        "house",
        "door",
        "wall",
        "roof",
        "ceiling",
        "floor",
        "road",
        "street",
        "traffic",
        "sign",
        "light",
        "pole",
        "tree",
        "plant",
        "bench",
        "chair",
        "table",
        "desk",
        "cabinet",
        "shelf",
        "fence",
        "bridge",
        "stairs"
    )

    private val trustByCanonicalLabel = mapOf(
        "person" to 1.00f,
        "man" to 1.00f,
        "woman" to 1.00f,
        "boy" to 1.00f,
        "girl" to 1.00f,
        "car" to 0.98f,
        "truck" to 0.98f,
        "bus" to 0.98f,
        "motorcycle" to 0.98f,
        "bicycle" to 0.95f,
        "dog" to 0.90f,
        "cat" to 0.90f,
        "bird" to 0.88f,
        "building" to 0.62f,
        "house" to 0.62f,
        "window" to 0.55f,
        "door" to 0.58f,
        "wall" to 0.60f,
        "tree" to 0.58f,
        "plant" to 0.55f,
        "bench" to 0.68f,
        "chair" to 0.70f,
        "table" to 0.70f,
        "traffic sign" to 0.72f,
        "traffic light" to 0.75f,
        "street light" to 0.75f,
        "parking meter" to 0.73f
    )

    private val trustByFamily = mapOf(
        "person" to 1.00f,
        "vehicle" to 0.95f,
        "animal" to 0.88f,
        "structure" to 0.60f,
        "flora" to 0.58f,
        "street_fixture" to 0.72f,
        "furniture" to 0.70f
    )

    fun normalize(label: String): String {
        return label
            .lowercase(Locale.US)
            .replace('_', ' ')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun canonicalLabel(label: String): String {
        val normalized = normalize(label)
        return aliasToCanonical[normalized] ?: normalized
    }

    fun compatibility(
        leftLabel: String,
        leftClassIndex: Int,
        rightLabel: String,
        rightClassIndex: Int
    ): Compatibility {
        if (leftClassIndex == rightClassIndex) return Compatibility.EXACT

        val leftCanonical = canonicalLabel(leftLabel)
        val rightCanonical = canonicalLabel(rightLabel)
        if (leftCanonical == rightCanonical) return Compatibility.HIERARCHY

        val leftFamily = labelFamily[leftCanonical]
        val rightFamily = labelFamily[rightCanonical]
        if (!leftFamily.isNullOrBlank() && leftFamily == rightFamily) {
            return Compatibility.HIERARCHY
        }
        return Compatibility.MISMATCH
    }

    fun areHierarchyCompatible(leftLabel: String, rightLabel: String): Boolean {
        val leftCanonical = canonicalLabel(leftLabel)
        val rightCanonical = canonicalLabel(rightLabel)
        if (leftCanonical == rightCanonical) return true

        val leftFamily = labelFamily[leftCanonical]
        val rightFamily = labelFamily[rightCanonical]
        return !leftFamily.isNullOrBlank() && leftFamily == rightFamily
    }

    fun isStationaryAnchor(label: String): Boolean {
        val canonical = canonicalLabel(label)
        if (canonical in stationaryAnchorCanonicalLabels) return true

        val family = labelFamily[canonical]
        if (!family.isNullOrBlank() && family in stationaryFamilies) return true

        val normalized = normalize(label)
        return stationaryKeywordFragments.any { fragment -> normalized.contains(fragment) }
    }

    fun motionTrust(label: String): Float {
        val canonical = canonicalLabel(label)
        trustByCanonicalLabel[canonical]?.let { return it }
        val familyTrust = labelFamily[canonical]?.let { trustByFamily[it] }
        return familyTrust ?: 0.75f
    }
}