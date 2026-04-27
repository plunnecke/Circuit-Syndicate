package com.circuitsyndicate.findingtheway

/**
 * Pure BLE/background-session state helpers used by runtime surfaces and tests.
 */
object BleSessionStatePolicy {

    data class Snapshot(
        val vestConnected: Boolean,
        val glassesConnectionCount: Int
    )

    fun normalizeGlassesConnectionCount(rawCount: Int): Int = rawCount.coerceAtLeast(0)

    fun snapshot(
        vestConnected: Boolean,
        glassesConnectionCount: Int
    ): Snapshot {
        return Snapshot(
            vestConnected = vestConnected,
            glassesConnectionCount = normalizeGlassesConnectionCount(glassesConnectionCount)
        )
    }

    fun hasAnyConnection(snapshot: Snapshot): Boolean {
        return snapshot.vestConnected || snapshot.glassesConnectionCount > 0
    }

    fun shouldBlockVestRoleAssignment(glassesConnectedAtAddress: Boolean): Boolean {
        return glassesConnectedAtAddress
    }

    fun connectionSummary(snapshot: Snapshot): String {
        return when {
            snapshot.vestConnected && snapshot.glassesConnectionCount > 0 -> {
                "Vest and glasses connected"
            }
            snapshot.vestConnected -> {
                "Vest connected, glasses disconnected"
            }
            snapshot.glassesConnectionCount == 1 -> {
                "Glasses connected, vest disconnected"
            }
            snapshot.glassesConnectionCount > 1 -> {
                "Glasses connected (${snapshot.glassesConnectionCount} links), vest disconnected"
            }
            else -> {
                "Waiting for device connections"
            }
        }
    }

    fun notificationContentText(
        mode: AssistiveRuntimeMode,
        snapshot: Snapshot
    ): String {
        return "${connectionSummary(snapshot)} | Mode: ${mode.displayName}"
    }

    fun notificationExpandedText(
        mode: AssistiveRuntimeMode,
        snapshot: Snapshot
    ): String {
        return "${connectionSummary(snapshot)}. Mode: ${mode.displayName}. Use notification actions to switch mode quickly."
    }
}
