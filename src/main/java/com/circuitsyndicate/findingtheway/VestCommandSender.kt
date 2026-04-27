package com.circuitsyndicate.findingtheway

/**
 * Typed command API for sending messages to the VEST ESP32.
 *
 * All strings here are matched EXACTLY against the firmware's cmd.toUpperCase()
 * check in checkBluetoothSafetyResponse() and the main command handler.
 *
 * Firmware command handler (vest loop):
 *   cmd.toUpperCase() then checks:
 *     "TURN ALL ON"
 *     "TURN HAPTICS ON"   "TURN HAPTICS OFF"
 *     "TURN SENSORS ON"   "TURN SENSORS OFF"
 *     "TURN ALL OFF"
 *
 * Legacy safety response handler (firmware fallback path):
 *   msg.toLowerCase() then checks:
 *     "glasses are on"    -> confirms, executes pending action
 *     "glasses are off"   -> applies vest force-on safety override
 *
 * "TURN ALL ON" and "TURN ALL OFF" are atomic vest-side updates for both
 * subsystems and are preferred for system-level toggles.
 */
object VestCommandSender {

    // ── Haptics ───────────────────────────────────────────────────────────────
    fun sendHapticsOn()  = send("TURN HAPTICS ON")
    fun sendHapticsOff() = send("TURN HAPTICS OFF")

    // ── Sensors ───────────────────────────────────────────────────────────────
    fun sendSensorsOn()  = send("TURN SENSORS ON")
    fun sendSensorsOff() = send("TURN SENSORS OFF")

    // ── System ────────────────────────────────────────────────────────────────
    /** Turns everything off. */
    fun sendAllOff()     = send("TURN ALL OFF")

    /** Turns everything on in a single vest-side state update. */
    fun sendAllOn() = send("TURN ALL ON")

    // ── Safety check responses ────────────────────────────────────────────────
    // Firmware does msg.toLowerCase() then checks == "glasses are on" / "glasses are off"
    fun confirmGlassesOn()  = send("Glasses are on")
    fun confirmGlassesOff() = send("Glasses are off")

    // ── Custom ────────────────────────────────────────────────────────────────
    fun sendCustom(cmd: String) = send(cmd)

    // ── Internal ──────────────────────────────────────────────────────────────
    private fun send(command: String): Boolean {
        InteractionLogger.logCommand(command, "APP→VEST")
        return DeviceManager.sendToVest(command)
    }
}
