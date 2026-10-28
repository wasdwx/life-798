package com.water.widget

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.time.LocalTime
import java.time.ZonedDateTime

object AutoTasks {
    const val ACTION = "com.water.widget.AUTO_TASKS"
    const val ACTION_CATCHUP = "com.water.widget.AUTO_TASKS_CATCHUP"
    private const val CATCHUP_REQUEST = 2403
    private const val KEY_CATCHUP_ATTEMPT = "catchup_attempt"

    /**
     * 整批网络失败后的补跑间隔：+15 分、+30 分、+1 时、+2 时、+4 时，共 5 次。
     *
     * 原来的 3 次只覆盖 1 小时：凌晨设备没网（Doze/关 Wi-Fi）时，2 小时都恢复不了，
     * 补跑全部用完就直接放弃到第二天。拉长到约 8 小时，并配合网络恢复回调立即重试。
     */
    private val CATCHUP_DELAYS_MS = longArrayOf(
        15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 2 * 60 * 60_000L, 4 * 60 * 60_000L
    )

    /** 网络恢复回调（一次性）；进程被杀后由 AlarmManager 的补跑兜底。 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkArmedAt = 0L
    /** 刚注册时系统会立刻回报一次「当前已有网络」，这段时间内的回调不算「恢复」。 */
    private const val NETWORK_ARM_GRACE_MS = 90_000L

    private fun prefs(context: Context) = context.getSharedPreferences("auto_tasks", Context.MODE_PRIVATE)

    /** 是否还有未完成的补跑计划（用于进程重启后重新挂上网络回调）。 */
    fun hasPendingCatchUp(context: Context) = prefs(context).getInt(KEY_CATCHUP_ATTEMPT, 0) > 0
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

    private fun catchUpIntent(context: Context) =
        Intent(context, AutoTaskReceiver::class.java).setAction(ACTION_CATCHUP)

    /**
     * 整批任务因为网络失败（超时/断网/DNS）时排一次补跑。
     * 每排一次计数 +1，超过 3 次就不再排，避免断网整夜反复唤醒。
     */
    fun scheduleCatchUp(context: Context) {
        if (!enabled(context)) return
        val attempt = prefs(context).getInt(KEY_CATCHUP_ATTEMPT, 0)
        if (attempt >= CATCHUP_DELAYS_MS.size) {
            DiagLog.info("AUTOTASK catch-up exhausted, giving up until next daily run")
            clearCatchUp(context)
            return
        }
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, CATCHUP_REQUEST, catchUpIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        prefs(context).edit().putInt(KEY_CATCHUP_ATTEMPT, attempt + 1).apply()
        if (!allowed(context)) return
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + CATCHUP_DELAYS_MS[attempt], pending)
            DiagLog.info("AUTOTASK catch-up " + (attempt + 1) + "/" + CATCHUP_DELAYS_MS.size
                + " in " + (CATCHUP_DELAYS_MS[attempt] / 60_000) + " min")
        } catch (_: SecurityException) {
        }
    }

    /**
     * 挂一个一次性网络回调：网络一恢复就立刻触发补跑，不用干等到下一个闹钟点。
     *
     * 只影响本进程存活期间；进程被杀后 AlarmManager 的补跑仍然有效，两者互为兜底。
     */
    fun armNetworkRecovery(context: Context) {
        if (!enabled(context)) return
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val app = context.applicationContext
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val elapsed = System.currentTimeMillis() - networkArmedAt
                if (elapsed < NETWORK_ARM_GRACE_MS) return
                DiagLog.info("AUTOTASK network recovered, retrying now")
                disarmNetworkRecovery(app)
                try {
                    app.sendBroadcast(Intent(app, AutoTaskReceiver::class.java).setAction(ACTION_CATCHUP))
                } catch (_: Throwable) {
                }
            }
        }
        networkArmedAt = System.currentTimeMillis()
        networkCallback = callback
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            DiagLog.info("AUTOTASK network recovery armed")
        } catch (_: Throwable) {
            networkCallback = null
        }
    }

    /** 任务成功或关闭自动任务时撤掉网络回调。 */
    fun disarmNetworkRecovery(context: Context) {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }

    /** 任务正常跑完（没有网络失败）或用户关掉自动任务时调用，取消补跑并清零计数。 */
    fun clearCatchUp(context: Context) {
        disarmNetworkRecovery(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(PendingIntent.getBroadcast(context, CATCHUP_REQUEST, catchUpIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (prefs(context).getInt(KEY_CATCHUP_ATTEMPT, 0) != 0) {
            prefs(context).edit().putInt(KEY_CATCHUP_ATTEMPT, 0).apply()
        }
    }

}

class AutoTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(AutoTasks.ACTION, AutoTasks.ACTION_CATCHUP, Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        AutoTasks.schedule(context)
        // BOOT_COMPLETED only restores the alarm: Android 15 forbids starting dataSync here.
        val triggerRun = action == AutoTasks.ACTION || action == AutoTasks.ACTION_CATCHUP
        if (!triggerRun || !AutoTasks.enabled(context)) return
        val message = when (TaskForegroundService.start(context)) {
            TaskForegroundService.StartResult.STARTED, TaskForegroundService.StartResult.ALREADY_RUNNING -> return
            TaskForegroundService.StartResult.NO_ACCOUNTS -> "请先完成设备或积分登录"
            TaskForegroundService.StartResult.RISK_NOT_CONFIRMED -> "请先进入积分任务页，阅读风险提示并确认"
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
