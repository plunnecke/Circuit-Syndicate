package com.circuitsyndicate.findingtheway

object ESP32CommandSender {

    fun sendHapticsOn() {
        VestCommandSender.sendHapticsOn()
    }

    fun sendHapticsOff() {
        VestCommandSender.sendHapticsOff()
    }

    fun sendSensorsOn() {
        VestCommandSender.sendSensorsOn()
    }

    fun sendSensorsOff() {
        VestCommandSender.sendSensorsOff()
    }

    fun sendAllOff() {
        VestCommandSender.sendAllOff()
    }
}
