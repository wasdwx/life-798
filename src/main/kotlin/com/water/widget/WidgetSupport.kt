package com.water.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.SizeF
import android.widget.RemoteViews
import com.water.widget.ui.UsageHistoryStore
import java.util.Locale

/**
 * 桌面小部件共用的取数与动作封装。
 *
 * AppWidgetProvider 本质是自己应用进程里的 BroadcastReceiver，所以这里能直接读
 * [TaskRunRepository] 与 [WaterService.activeStatus] 拿到实时运行状态；
 * 但不能做网络 I/O，积分余额之类的数字只能读本地缓存。
 */
object WidgetSupport {

    /**
     * 小部件可展示的信息快照。
     *
     * compact 变体给 4×1 用：那里按钮只有一行，必须自带动作名，
     * 不能像 4×2 那样把动作名和状态分成两行。
     */
    data class Info(
        val deviceName: String,
        val deviceStatus: String,
        val deviceCompact: String,
        val taskStatus: String,
        val taskCompact: String,
        val usageText: String
    )

    fun readInfo(context: Context): Info {
        val account = AccountStore.getCurrent(context)
        val did = account?.selectedDeviceId()

        val deviceName = when {
            account == null -> "未登录"
            did.isNullOrEmpty() -> "未选择设备"
            else -> account.deviceDisplayName(did)
        }

        // 接水会话进行中时，实时状态优先于静态的「待机」
        val session = WaterService.activeStatus()
        val idleDeviceStatus = if (did.isNullOrEmpty()) "未配置设备" else "待机"

        val scoreText = if (account != null && account.score > 0) {
            String.format(Locale.CHINA, "%,d", account.score)
        } else {
            "--"
        }
        val run = TaskRunRepository.state.value
        val gained = run.totalGained
        val finishedWithGain = !run.running && run.result != TaskRunResult.NONE && gained > 0
        val taskStatus = when {
            run.running -> "运行中 +$gained"
            finishedWithGain -> "$scoreText · 本次 +$gained"
            else -> scoreText
        }
        val taskCompact = when {
            run.running -> "运行中 +$gained"
            finishedWithGain -> "积分 $scoreText · +$gained"
            else -> "积分 $scoreText"
        }

        val accountKey = account?.phone?.takeIf { it.isNotEmpty() } ?: account?.uid.orEmpty()
        val usage = UsageHistoryStore.mergeAndRead(context, accountKey, null)
        val usageText = if (usage.todayWaterText == "--") {
            "今日暂无用水"
        } else {
            "今日 ${usage.todayWaterText} · ${usage.todayCostText}"
        }

        return Info(
            deviceName = deviceName,
            deviceStatus = session ?: idleDeviceStatus,
            deviceCompact = session ?: "启动设备",
            taskStatus = taskStatus,
            taskCompact = taskCompact,
            usageText = usageText
        )
    }

    /** 启动当前默认设备，返回可直接显示的中文状态。 */
    fun startDevice(context: Context): String {
        val did = AccountStore.getCurrent(context)?.selectedDeviceId()
        if (did.isNullOrEmpty()) return "未配置设备"
        return when (WaterService.start(context, did, AppWidgetManager.INVALID_APPWIDGET_ID)) {
            WaterService.StartResult.STARTED -> "正在启动…"
            WaterService.StartResult.ALREADY_RUNNING -> "已有接水会话"
            else -> "启动失败，请重试"
        }
    }

    /** 触发积分任务，返回可直接显示的中文状态。 */
    fun runTasks(context: Context): String =
        when (TaskForegroundService.start(context)) {
            TaskForegroundService.StartResult.STARTED -> "正在启动…"
            TaskForegroundService.StartResult.ALREADY_RUNNING -> "任务已在运行"
            TaskForegroundService.StartResult.NO_ACCOUNTS -> "无可用账户"
            TaskForegroundService.StartResult.FAILED -> "启动失败，请重试"
        }

    fun actionIntent(
        context: Context,
        provider: Class<*>,
        action: String,
        requestCode: Int
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, provider).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 小部件跟的是系统夜间模式而非应用内的显示模式开关：启动器渲染布局 XML 时用的就是系统配置。 */
    private fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 给一块按钮上色：背景吃色相与不透明度，图标和文字用同色相的前景色，前景始终不透明。
     * 背景 drawable 是纯色圆角形状，SRC_IN 着色后颜色与 alpha 都以这里传入的为准。
     */
    @JvmStatic
    fun tintButton(
        views: RemoteViews,
        context: Context,
        hue: Float,
        opacity: Int,
        cardId: Int,
        iconId: Int,
        vararg textIds: Int
    ) {
        val dark = isNight(context)
        val container = WidgetPalette.withOpacity(WidgetPalette.container(hue, dark), opacity)
        val on = WidgetPalette.onContainer(hue, dark)
        views.setColorStateList(cardId, "setBackgroundTintList", ColorStateList.valueOf(container))
        views.setInt(iconId, "setColorFilter", on)
        textIds.forEach { views.setTextColor(it, on) }
    }

    /** 卡片底只吃不透明度，颜色沿用资源里的深浅两套。 */
    @JvmStatic
    fun tintSurface(views: RemoteViews, context: Context, colorRes: Int, opacity: Int) {
        val color = WidgetPalette.withOpacity(context.getColor(colorRes), opacity)
        views.setColorStateList(
            android.R.id.background, "setBackgroundTintList", ColorStateList.valueOf(color)
        )
    }

    /**
     * 方块小部件三档布局共用的构建。图标、文字、配色都由代码写入，两个 Provider 只需给内容。
     * 启动器按当前格子大小自动挑一档（API 31+ 尺寸映射），键是该档能用的最小尺寸；
     * 不支持映射的启动器会退回最小那档，功能不丢。
     */
    fun tileViews(
        context: Context,
        provider: Class<*>,
        action: String,
        requestCode: Int,
        iconRes: Int,
        title: String,
        status: String,
        compact: String,
        hue: Float
    ): RemoteViews {
        val opacity = ThemeSettings.widgetStyle(context).opacity
        val click = actionIntent(context, provider, action, requestCode)
        fun layout(layoutRes: Int, vararg texts: Pair<Int, String>) =
            RemoteViews(context.packageName, layoutRes).apply {
                setImageViewResource(R.id.tile_icon, iconRes)
                texts.forEach { (id, text) -> setTextViewText(id, text) }
                setOnClickPendingIntent(R.id.tile_card, click)
                tintButton(
                    this, context, hue, opacity, R.id.tile_card, R.id.tile_icon,
                    *IntArray(texts.size) { texts[it].first }
                )
            }
        return RemoteViews(
            mapOf(
                SizeF(40f, 40f) to layout(R.layout.widget_tile_1x1, R.id.tile_status to status),
                SizeF(100f, 40f) to layout(R.layout.widget_tile_2x1, R.id.tile_title to compact),
                SizeF(100f, 110f) to layout(
                    R.layout.widget_tile_2x2, R.id.tile_title to title, R.id.tile_status to status
                )
            )
        )
    }

    /**
     * 主动刷新全部小部件。
     *
     * widget_info 里 updatePeriodMillis=0，系统不会自行拉起更新；
     * 任务进度、接水状态、积分余额变化后不广播的话会一直停在旧值。
     */
    @JvmStatic
    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        PROVIDERS.forEach { provider ->
            val component = ComponentName(context, provider)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull()
            if (ids == null || ids.isEmpty()) return@forEach
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }

    private val PROVIDERS = listOf(
        WaterWidgetProvider::class.java,
        ComboWidgetProvider::class.java,
        DeviceWidgetProvider::class.java,
        TaskWidgetProvider::class.java
    )
}
