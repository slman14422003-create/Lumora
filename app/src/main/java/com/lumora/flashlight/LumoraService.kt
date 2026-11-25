package com.lumora.flashlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class LumoraService : Service() {
    private lateinit var prefs: SharedPreferences
    private lateinit var manager: CameraManager
    private lateinit var output: TorchOutput
    private lateinit var wake: PowerManager.WakeLock
    private var host: AutoSensorHost? = null
    private var callback: CameraManager.TorchCallback? = null
    private var torchOn = false
    private var strength = 10
    private var autoRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        output = TorchOutput(manager)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumora:auto")
            .apply { setReferenceCounted(false) }
        createChannel()
        registerTorch()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE_TORCH -> toggleTorch()
            ACTION_AUTO_ON -> setBackgroundAuto(true)
            ACTION_AUTO_OFF -> setBackgroundAuto(false)
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> syncHost()
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        stopHost()
        host?.release()
        host = null
        callback?.let { try { manager.unregisterTorchCallback(it) } catch (_: Exception) { } }
        callback = null
        if (wake.isHeld) wake.release()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (_: Exception) {
        false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, LumoraService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val autoWanted = prefs.getBoolean(Prefs.BG_AUTO, false)
        val canAuto = TorchSupport.hasLightSensor(this)
        val text = when {
            autoWanted && canAuto -> R.string.notif_auto_on
            torchOn -> R.string.notif_light_on
            else -> R.string.notif_ready
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lumora)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(text))
            .setColor(getColor(R.color.lumora_cyan))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (output.id != null) {
            builder.addAction(0, getString(R.string.notif_action_light), action(ACTION_TOGGLE_TORCH, 1))
        }
        if (canAuto) {
            builder.addAction(
                0,
                getString(if (autoWanted) R.string.notif_action_auto_off else R.string.notif_action_auto_on),
                action(if (autoWanted) ACTION_AUTO_OFF else ACTION_AUTO_ON, 2)
            )
        }
        builder.addAction(0, getString(R.string.notif_action_close), action(ACTION_STOP, 3))
        return builder.build()
    }

    private fun refresh() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun registerTorch() {
        if (output.id == null) return
        val torch = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != output.id || enabled == torchOn) return
                torchOn = enabled
                if (!enabled) output.forget()
                if (autoRunning) host?.userTorch(enabled)
                refresh()
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId != output.id || !torchOn) return
                torchOn = false
                output.forget()
                if (autoRunning) host?.syncTorch(false)
                refresh()
            }
        }
        try {
            manager.registerTorchCallback(torch, Handler(Looper.getMainLooper()))
            callback = torch
        } catch (_: Exception) { }
    }

    private fun toggleTorch() {
        if (output.id == null) return
        val target = !torchOn
        torchOn = target
        try {
            output.set(target, strength)
        } catch (_: Exception) {
            torchOn = !target
            output.forget()
            return
        }
        if (autoRunning) {
            host?.userTorch(target)
        } else if (target) {
            AutoOffTimer.start(this, prefs.getInt(Prefs.TIMER_MINUTES, 0))
        } else {
            AutoOffTimer.cancel(this)
        }
    }

    private fun setBackgroundAuto(value: Boolean) {
        if (value && !TorchSupport.hasLightSensor(this)) return
        prefs.edit().putBoolean(Prefs.BG_AUTO, value).apply()
        syncHost()
    }

    private fun syncHost() {
        val wanted = prefs.getBoolean(Prefs.BG_AUTO, false) && !MainActivity.visible
        if (wanted) startHost() else stopHost()
    }

    private fun startHost() {
        if (autoRunning || output.id == null || !TorchSupport.hasLightSensor(this)) return
        val sensing = host ?: AutoSensorHost(this, prefs, ::onAutoState).also { host = it }
        sensing.setBackgroundMode(true)
        sensing.setForeground(false)
        sensing.setActive(true)
        autoRunning = true
        wake.acquire(WAKE_MS)
        sensing.start(torchOn, strength)
    }

    private fun stopHost() {
        autoRunning = false
        host?.let {
            it.stop()
            it.setBackgroundMode(false)
        }
        if (wake.isHeld) wake.release()
    }

    private fun onAutoState(lux: Float?, level: Int, reason: Reason, sources: Sources) {
        if (!autoRunning) return
        wake.acquire(WAKE_MS)
        val on = level > 0
        if (on == torchOn && (!on || level == strength)) return
        torchOn = on
        if (on) strength = level
        try {
            output.set(on, strength)
        } catch (_: Exception) {
            torchOn = false
            output.forget()
            host?.syncTorch(false)
        }
        refresh()
    }

    private fun shutdown() {
        prefs.edit().putBoolean(Prefs.STATUS_ICON, false).putBoolean(Prefs.BG_AUTO, false).apply()
        stopHost()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_SYNC = "com.lumora.flashlight.SYNC"
        const val ACTION_TOGGLE_TORCH = "com.lumora.flashlight.TOGGLE_TORCH"
        const val ACTION_AUTO_ON = "com.lumora.flashlight.AUTO_ON"
        const val ACTION_AUTO_OFF = "com.lumora.flashlight.AUTO_OFF"
        const val ACTION_STOP = "com.lumora.flashlight.STOP"
        private const val CHANNEL_ID = "lumora_status"
        private const val NOTIFICATION_ID = 7
        private const val WAKE_MS = 30_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, LumoraService::class.java)
            if (action != null) intent.action = action
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) { }
        }

        fun sync(context: Context) {
            if (running) start(context, ACTION_SYNC)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LumoraService::class.java))
        }
    }
}
