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

    private const val INTER_COMMAND_DELAY_MS = 180L
    private const val RETRY_DELAY_MS = 220L
    private const val MAX_SEND_ATTEMPTS = 2

    // ── Haptics ───────────────────────────────────────────────────────────────
    fun sendHapticsOn()  = send("TURN HAPTICS ON")
    fun sendHapticsOff() = send("TURN HAPTICS OFF")

    // ── Sensors ───────────────────────────────────────────────────────────────
    fun sendSensorsOn()  = send("TURN SENSORS ON")
    fun sendSensorsOff() = send("TURN SENSORS OFF")

    // ── System ────────────────────────────────────────────────────────────────
    /** Turns everything off. */
    fun sendAllOff()     = send("TURN ALL OFF")

    /**
     * Turns system on with a compatibility-first sequence.
     *
     * 1) Clear any firmware-side system lock.
     * 2) Enable each subsystem explicitly.
     *
     * Some deployed vest builds reject TURN ALL ON, so we avoid using it as the
     * primary path.
     */
    fun sendAllOn(): Boolean {
        sendWithRetry("TURN SYSTEM ON")
        Thread.sleep(INTER_COMMAND_DELAY_MS)

        val hapticsSent = sendWithRetry("TURN HAPTICS ON")
        Thread.sleep(INTER_COMMAND_DELAY_MS)

        val sensorsSent = sendWithRetry("TURN SENSORS ON")
        return hapticsSent && sensorsSent
    }

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

    private fun sendWithRetry(command: String): Boolean {
        repeat(MAX_SEND_ATTEMPTS) { attempt ->
            if (send(command)) {
                return true
            }
            if (attempt < MAX_SEND_ATTEMPTS - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return false
    }
}
