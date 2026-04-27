package com.circuitsyndicate.findingtheway

class ESP32MessageHandler {

    interface ESP32StateListener {
        fun onHapticsChanged(enabled: Boolean)
        fun onSensorsChanged(enabled: Boolean)
        fun onBatteryLevel(level: Int)
        fun onSafetyPrompt()
    }

    private val listeners = mutableListOf<ESP32StateListener>()

    fun addListener(listener: ESP32StateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ESP32StateListener) {
        listeners.remove(listener)
    }

    fun handleMessage(raw: String) {
        val msg = raw.trim()

        when {
            msg.startsWith("BATTERY:") -> {
                val level = msg.substring(8).toIntOrNull() ?: return
                listeners.forEach { it.onBatteryLevel(level) }
            }

            msg == "BOTH_BUTTONS_ON" -> {
                listeners.forEach {
                    it.onHapticsChanged(true)
                    it.onSensorsChanged(true)
                }
            }

            msg == "BOTH_BUTTONS_OFF" -> {
                listeners.forEach {
                    it.onHapticsChanged(false)
                    it.onSensorsChanged(false)
                }
            }

            msg == "HAPTICS_ON_SENSORS_OFF" -> {
                listeners.forEach {
                    it.onHapticsChanged(true)
                    it.onSensorsChanged(false)
                }
            }

            msg == "HAPTICS_OFF_SENSORS_ON" -> {
                listeners.forEach {
                    it.onHapticsChanged(false)
                    it.onSensorsChanged(true)
                }
            }

            msg == "Are Glasses On?" -> {
                listeners.forEach { it.onSafetyPrompt() }
            }
        }
    }
}
