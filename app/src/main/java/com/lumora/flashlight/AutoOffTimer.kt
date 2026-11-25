package com.lumora.flashlight

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object AutoOffTimer {
    private const val ACTION = "com.lumora.flashlight.AUTO_OFF"

    // Set by the visible screen so it can update itself when the timer fires.
    var onExpired: (() -> Unit)? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    private fun alarms(context: Context) =
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(context: Context): PendingIntent {
        val app = context.applicationContext
        val intent = Intent(app, AutoOffReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Wall-clock time the timer fires, or 0 when no timer is running. */
    fun endsAt(context: Context): Long {
        val value = prefs(context).getLong(Prefs.TIMER_ENDS_AT, 0L)
        return if (value > System.currentTimeMillis()) value else 0L
    }

    fun start(context: Context, minutes: Int) {
        cancel(context)
        if (minutes <= 0) return
        val delay = minutes * 60_000L
        try {
            alarms(context).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay,
                pending(context)
            )
            prefs(context).edit().putLong(Prefs.TIMER_ENDS_AT, System.currentTimeMillis() + delay).apply()
        } catch (_: Exception) { }
    }

    fun cancel(context: Context) {
        val stored = prefs(context)
        if (!stored.contains(Prefs.TIMER_ENDS_AT)) return
        try { alarms(context).cancel(pending(context)) } catch (_: Exception) { }
        stored.edit().remove(Prefs.TIMER_ENDS_AT).apply()
    }

    fun expire(context: Context) {
        prefs(context).edit().remove(Prefs.TIMER_ENDS_AT).apply()
        TorchSupport.turnOff(context)
        onExpired?.invoke()
    }
}

class AutoOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutoOffTimer.expire(context)
    }
}
