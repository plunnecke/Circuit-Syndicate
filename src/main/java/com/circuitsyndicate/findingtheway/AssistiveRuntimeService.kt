package com.circuitsyndicate.findingtheway

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class AssistiveRuntimeService : Service() {

    companion object {
        private const val TAG = "AssistiveRuntimeService"
        private const val NOTIFICATION_ID = 4105

        private const val ACTION_START_OR_REFRESH =
            "com.circuitsyndicate.findingtheway.action.RUNTIME_START_OR_REFRESH"
        private const val ACTION_SET_MODE =
            "com.circuitsyndicate.findingtheway.action.RUNTIME_SET_MODE"
        private const val ACTION_ARM_BACKGROUND_RUNTIME_STOP =
            "com.circuitsyndicate.findingtheway.action.RUNTIME_ARM_BACKGROUND_STOP"
        private const val ACTION_CONFIRM_BACKGROUND_RUNTIME_STOP =
            "com.circuitsyndicate.findingtheway.action.RUNTIME_CONFIRM_BACKGROUND_STOP"
        private const val ACTION_ENABLE_BACKGROUND_RUNTIME =
            "com.circuitsyndicate.findingtheway.action.RUNTIME_ENABLE_BACKGROUND"
        private const val EXTRA_TARGET_MODE = "target_mode"
        private const val SAFEGUARD_ARM_WINDOW_MS = 8_000L

        fun start(context: Context) {
            dispatchCommand(context.applicationContext, ACTION_START_OR_REFRESH, null)
        }

        fun enableBackgroundRuntime(context: Context, source: String = "APP") {
            val appContext = context.applicationContext
            AssistiveRuntimeSettings.setBackgroundRuntimeEnabled(appContext, true, source)
            start(appContext)
        }

        fun disableBackgroundRuntime(context: Context, source: String = "APP") {
            val appContext = context.applicationContext
            AssistiveRuntimeSettings.setBackgroundRuntimeEnabled(appContext, false, source)
            DeviceManager.disconnectVest()
            GlassesBleManager.disconnect()
            appContext.stopService(Intent(appContext, AssistiveRuntimeService::class.java))
        }

        private fun dispatchCommand(
            context: Context,
            action: String,
            mode: AssistiveRuntimeMode?,
            source: String? = null
        ) {
            val intent = Intent(context, AssistiveRuntimeService::class.java).apply {
                this.action = action
                mode?.let { putExtra(EXTRA_TARGET_MODE, it.name) }
                source?.let { putExtra(AssistiveRuntimeSettings.EXTRA_SOURCE, it) }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start runtime service for action=$action", e)
            }
        }
    }

    private var isForeground = false
    private var receiverRegistered = false
    private var safeguardStopArmedUntilElapsedMs = 0L
    private val notificationHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var notificationRefreshScheduled = false

    private val runtimeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "BT_CONNECTION_CHANGED",
                "BLUETOOTH_CONNECTION_CHANGED",
                AssistiveRuntimeSettings.ACTION_MODE_CHANGED -> scheduleNotificationPublish()
                AssistiveRuntimeSettings.ACTION_BACKGROUND_RUNTIME_CHANGED -> {
                    if (!AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this@AssistiveRuntimeService)) {
                        disableForegroundAndStop()
                    } else {
                        scheduleNotificationPublish()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerRuntimeReceiver()
        if (!AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)) {
            disableForegroundAndStop()
            return
        }
        scheduleNotificationPublish()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OR_REFRESH -> {
                // No-op command used to start or refresh notification state.
            }

            ACTION_SET_MODE -> {
                val targetMode = AssistiveRuntimeMode.fromStoredValue(
                    intent.getStringExtra(EXTRA_TARGET_MODE)
                )
                AssistiveRuntimeSettings.setMode(
                    context = this,
                    mode = targetMode,
                    source = "NOTIFICATION"
                )
            }

            ACTION_ARM_BACKGROUND_RUNTIME_STOP -> armBackgroundRuntimeStop()

            ACTION_CONFIRM_BACKGROUND_RUNTIME_STOP -> {
                if (isBackgroundRuntimeStopArmed()) {
                    disableBackgroundRuntime(this, source = "NOTIFICATION")
                    disableForegroundAndStop()
                    return START_NOT_STICKY
                }
                armBackgroundRuntimeStop()
            }

            ACTION_ENABLE_BACKGROUND_RUNTIME -> {
                AssistiveRuntimeSettings.setBackgroundRuntimeEnabled(
                    context = this,
                    enabled = true,
                    source = "NOTIFICATION"
                )
            }
        }

        if (!AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)) {
            disableForegroundAndStop()
            return START_NOT_STICKY
        }

        scheduleNotificationPublish()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(runtimeReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        notificationHandler.removeCallbacksAndMessages(null)
        notificationRefreshScheduled = false
        isForeground = false
        super.onDestroy()
    }

    private fun registerRuntimeReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction("BT_CONNECTION_CHANGED")
            addAction("BLUETOOTH_CONNECTION_CHANGED")
            addAction(AssistiveRuntimeSettings.ACTION_MODE_CHANGED)
            addAction(AssistiveRuntimeSettings.ACTION_BACKGROUND_RUNTIME_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(runtimeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(runtimeReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun publishNotification() {
        val notification = buildRuntimeNotification()
        try {
            if (!isForeground) {
                startForegroundCompat(notification)
                isForeground = true
            } else {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish runtime notification", e)
        }
    }

    private fun scheduleNotificationPublish() {
        if (notificationRefreshScheduled) return
        notificationRefreshScheduled = true
        notificationHandler.post {
            notificationRefreshScheduled = false
            if (!AssistiveRuntimeSettings.isBackgroundRuntimeEnabled(this)) {
                disableForegroundAndStop()
                return@post
            }
            publishNotification()
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildRuntimeNotification(): Notification {
        val mode = AssistiveRuntimeSettings.getMode(this)
        val snapshot = BleSessionStatePolicy.snapshot(
            vestConnected = DeviceManager.isVestConnected(),
            glassesConnectionCount = if (DeviceManager.isGlassesConnected()) 1 else 0
        )

        val appIntent = PendingIntent.getActivity(
            this,
            2000,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingIntentFlags()
        )

        val builder = NotificationCompat.Builder(this, FindingTheWayApp.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Finding The Way background runtime")
            .setContentText(BleSessionStatePolicy.notificationContentText(mode, snapshot))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    BleSessionStatePolicy.notificationExpandedText(mode, snapshot)
                )
            )
            .setSubText("Tap notification to open full app controls")
            .setContentIntent(appIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(buildModeAction(AssistiveRuntimeMode.ASSISTIVE_ACTIVE, "Active"))
            .addAction(buildModeAction(AssistiveRuntimeMode.ASSISTIVE_SILENT, "Silent"))
            .addAction(buildModeAction(AssistiveRuntimeMode.PHONE_PRIORITY, "Phone"))

        if (isBackgroundRuntimeStopArmed()) {
            builder.addAction(buildConfirmBackgroundStopAction())
        } else {
            builder.addAction(buildArmBackgroundStopAction())
        }

        return builder.build()
    }

    private fun armBackgroundRuntimeStop() {
        safeguardStopArmedUntilElapsedMs = SystemClock.elapsedRealtime() + SAFEGUARD_ARM_WINDOW_MS
        scheduleNotificationPublish()
    }

    private fun clearBackgroundRuntimeStopArm() {
        safeguardStopArmedUntilElapsedMs = 0L
    }

    private fun isBackgroundRuntimeStopArmed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (safeguardStopArmedUntilElapsedMs <= now) {
            safeguardStopArmedUntilElapsedMs = 0L
            return false
        }
        return true
    }

    private fun buildArmBackgroundStopAction(): NotificationCompat.Action {
        val pendingIntent = serviceActionPendingIntent(
            requestCode = 2400,
            action = ACTION_ARM_BACKGROUND_RUNTIME_STOP
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Arm Runtime Stop",
            pendingIntent
        ).build()
    }

    private fun buildConfirmBackgroundStopAction(): NotificationCompat.Action {
        val pendingIntent = serviceActionPendingIntent(
            requestCode = 2401,
            action = ACTION_CONFIRM_BACKGROUND_RUNTIME_STOP
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_delete,
            "Confirm Stop",
            pendingIntent
        ).build()
    }

    private fun disableForegroundAndStop() {
        clearBackgroundRuntimeStopArm()
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun buildModeAction(
        mode: AssistiveRuntimeMode,
        title: String
    ): NotificationCompat.Action {
        val iconRes = when (mode) {
            AssistiveRuntimeMode.ASSISTIVE_ACTIVE -> android.R.drawable.ic_lock_silent_mode_off
            AssistiveRuntimeMode.ASSISTIVE_SILENT -> android.R.drawable.ic_lock_silent_mode
            AssistiveRuntimeMode.PHONE_PRIORITY -> android.R.drawable.ic_menu_call
        }

        val pendingIntent = serviceActionPendingIntent(
            requestCode = 2100 + mode.ordinal,
            action = ACTION_SET_MODE,
            targetMode = mode
        )

        return NotificationCompat.Action.Builder(iconRes, title, pendingIntent).build()
    }

    private fun serviceActionPendingIntent(
        requestCode: Int,
        action: String,
        targetMode: AssistiveRuntimeMode? = null
    ): PendingIntent {
        val intent = Intent(this, AssistiveRuntimeService::class.java).apply {
            this.action = action
            data = Uri.Builder()
                .scheme("ftw")
                .authority("runtime")
                .appendPath(action)
                .appendPath(requestCode.toString())
                .build()
            targetMode?.let { putExtra(EXTRA_TARGET_MODE, it.name) }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, pendingIntentFlags())
        } else {
            PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
        }
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
