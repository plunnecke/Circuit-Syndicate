package com.circuitsyndicate.findingtheway

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FindingTheWayApp : Application() {

    companion object {
        const val CHANNEL_ID = "ftw_bluetooth_channel"
        const val CHANNEL_NAME = "Assistive Background Runtime"
    }

    override fun onCreate() {
        super.onCreate()
        InteractionLogger.initialize(this)
        createNotificationChannel()

        // Vest: BLE GATT via DeviceManager
        DeviceManager.initialize(this)

        // Glasses: BLE GATT via GlassesBleManager (separate transport)
        GlassesBleManager.initialize(this)

        val backgroundRuntimeEnabled = AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)
        if (backgroundRuntimeEnabled) {
            AssistiveRuntimeService.start(this)
        }
        InteractionLogger.logSessionEvidence(
            source = "ANDROID",
            event = "APP_READY",
            details = "runtime_service_started=$backgroundRuntimeEnabled"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains BLE links and quick controls for assistive runtime mode"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
