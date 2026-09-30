package com.water.widget

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalTime
import java.time.ZonedDateTime

object AutoTasks {
    const val ACTION = "com.water.widget.AUTO_TASKS"
    private fun prefs(context: Context) = context.getSharedPreferences("auto_tasks", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun minute(context: Context) = prefs(context).getInt("minute", 540).coerceIn(0, 1439)
    fun allowed(context: Context) = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun configure(context: Context, enabled: Boolean, minute: Int) {
        require(minute in 0..1439)
        prefs(context).edit().putBoolean("enabled", enabled).putInt("minute", minute).apply()
        schedule(context)
    }

    fun nextRun(now: ZonedDateTime, minute: Int): ZonedDateTime {
        require(minute in 0..1439)
        val time = LocalTime.of(minute / 60, minute % 60)
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }

    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 2401,
            Intent(context, AutoTaskReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (!enabled(context) || !allowed(context)) return
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                nextRun(ZonedDateTime.now(), minute(context)).toInstant().toEpochMilli(), pending)
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and scheduling; the settings page shows it.
        }
    }

}

class AutoTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(AutoTasks.ACTION, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        AutoTasks.schedule(context)
        // BOOT_COMPLETED only restores the alarm: Android 15 forbids starting dataSync here.
        if (action != AutoTasks.ACTION || !AutoTasks.enabled(context)) return
        val message = when (TaskForegroundService.start(context)) {
            TaskForegroundService.StartResult.STARTED, TaskForegroundService.StartResult.ALREADY_RUNNING -> return
            TaskForegroundService.StartResult.NO_ACCOUNTS -> "请先完成设备或积分登录"
            TaskForegroundService.StartResult.FAILED -> "系统未允许后台启动，请打开应用运行任务"
        }
        AppNotifications.ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(2402, Notification.Builder(context, AppNotifications.CHANNEL_TASK_RESULT)
                    .setSmallIcon(R.drawable.ic_water_drop).setContentTitle("自动积分任务未启动")
                    .setContentText(message).setContentIntent(AppNotifications.openApp(context, false, 2402))
                    .setAutoCancel(true).build())
            } catch (_: SecurityException) { }
        }
    }
}
