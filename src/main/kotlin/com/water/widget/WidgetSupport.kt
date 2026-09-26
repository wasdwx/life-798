package com.water.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
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
     * compact 用于只够显示一行的尺寸，必须自带动作名；
     * 空间足够时再把动作名、状态和详情分开。
     */
    data class Info(
        val deviceName: String,
        val deviceStatus: String,
        val deviceCompact: String,
        val taskStatus: String,
        val taskCompact: String,
        val taskDetail: String,
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
            taskDetail = when {
                run.running -> run.lanes.firstOrNull {
                    it.phase != TaskLanePhase.COMPLETED && it.phase != TaskLanePhase.FAILED
                }?.currentTask ?: "正在执行积分任务"
                run.result == TaskRunResult.COMPLETED -> "本次任务已完成"
                run.result == TaskRunResult.PARTIAL -> "部分任务未完成，可在应用内查看"
                run.result == TaskRunResult.FAILED -> "任务失败，可在应用内查看"
                run.result == TaskRunResult.CANCELLED -> "任务已停止"
                else -> "点击执行积分任务"
            },
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

    /** 点一下启动积分任务，运行中再点一下停止；返回可直接显示的中文状态。 */
    fun toggleTasks(context: Context): String =
        when (TaskForegroundService.start(context)) {
            TaskForegroundService.StartResult.STARTED -> "正在启动…"
            TaskForegroundService.StartResult.ALREADY_RUNNING -> {
                TaskForegroundService.stop(context)
                "正在停止…"
            }
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

    /**
     * 给一块按钮上色：背景吃色相与不透明度，图标和文字用同色相的前景色，前景始终不透明。
     * 背景 drawable 是纯色圆角形状，SRC_IN 着色后颜色与 alpha 都以这里传入的为准。
     */
    @JvmStatic
    fun tintButton(
        views: RemoteViews,
        hue: Float,
        opacity: Int,
        cardId: Int,
        iconId: Int,
        vararg textIds: Int
    ) {
        // 同时交给启动器深浅两套颜色，系统切换主题时重用 RemoteViews 也能更新。
        views.setColorStateList(
            cardId, "setBackgroundTintList",
            ColorStateList.valueOf(WidgetPalette.withOpacity(WidgetPalette.container(hue, false), opacity)),
            ColorStateList.valueOf(WidgetPalette.withOpacity(WidgetPalette.container(hue, true), opacity))
        )
        val light = WidgetPalette.onContainer(hue, false)
        val dark = WidgetPalette.onContainer(hue, true)
        views.setColorInt(iconId, "setColorFilter", light, dark)
        textIds.forEach { views.setColorInt(it, "setTextColor", light, dark) }
    }

    /** 卡片底只吃不透明度，颜色沿用资源里的深浅两套。 */
    @JvmStatic
    fun tintSurface(views: RemoteViews, context: Context, colorRes: Int, opacity: Int) {
        fun color(nightMode: Int): ColorStateList {
            val config = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            return ColorStateList.valueOf(WidgetPalette.withOpacity(
                context.createConfigurationContext(config).getColor(colorRes), opacity
            ))
        }
        views.setColorStateList(
            android.R.id.background, "setBackgroundTintList",
            color(Configuration.UI_MODE_NIGHT_NO), color(Configuration.UI_MODE_NIGHT_YES)
        )
    }

    /** 优先按启动器报告的实际尺寸排版；旧启动器使用横竖屏的 min/max 尺寸。 */
    @JvmStatic
    fun responsiveViews(
        context: Context,
        widgetId: Int,
        fallbackSizes: List<SizeF>,
        build: (SizeF) -> RemoteViews
    ): RemoteViews {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val sizes = widgetSizes(options).ifEmpty { fallbackSizes }
        return RemoteViews(sizes.associateWith(build))
    }

    internal fun widgetSizes(options: Bundle): List<SizeF> {
        val reported = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            .orEmpty().filter { it.width > 0 && it.height > 0 }.distinct()
        if (reported.isNotEmpty()) return reported.take(16) // RemoteViews 最多接受 16 个尺寸。
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat()
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).toFloat().coerceAtLeast(minWidth)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat().coerceAtLeast(minHeight)
        return listOf(SizeF(minWidth, maxHeight), SizeF(maxWidth, minHeight))
            .filter { it.width > 0 && it.height > 0 }.distinct()
    }

    /** 方块、横条和窄高形状共用内容，剩余空间决定是否显示状态和详情。 */
    fun tileViews(
        context: Context,
        widgetId: Int,
        provider: Class<*>,
        action: String,
        requestCode: Int,
        iconRes: Int,
        title: String,
        status: String,
        compact: String,
        detail: String,
        hue: Float
    ): RemoteViews {
        val click = actionIntent(context, provider, action, requestCode)
        return responsiveViews(context, widgetId, listOf(
            SizeF(40f, 40f), SizeF(56f, 64f), SizeF(120f, 40f), SizeF(120f, 72f),
            SizeF(64f, 132f), SizeF(144f, 176f), SizeF(224f, 224f)
        )) { size ->
            tileLayout(context, size, iconRes, title, status, compact, detail, hue).apply {
                setOnClickPendingIntent(R.id.tile_card, click)
            }
        }
    }

    internal fun tileLayout(
        context: Context,
        size: SizeF,
        iconRes: Int,
        title: String,
        status: String,
        compact: String,
        detail: String,
        hue: Float
    ): RemoteViews {
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val horizontal = size.width >= 120f && (size.width >= size.height * 1.35f || size.height < 80f)
        val expanded = !horizontal && size.height >= maxOf(104f, 80f * fontScale)
        val showStatus = size.height >= (if (horizontal) 48f else 56f) * fontScale
        val showDetail = size.width >= 104f && size.height >=
            if (horizontal) maxOf(88f, 80f * fontScale) else maxOf(156f, 128f * fontScale)
        val padding = if (minOf(size.width, size.height) < 56f) 4 else 8
        val layout = when {
            horizontal -> R.layout.widget_tile_2x1
            expanded -> R.layout.widget_tile_2x2
            else -> R.layout.widget_tile_1x1
        }
        return RemoteViews(context.packageName, layout).apply {
            setImageViewResource(R.id.tile_icon, iconRes)
            setTextViewText(R.id.tile_title, if (horizontal && !showStatus) compact else title)
            setTextViewText(R.id.tile_status, status)
            setTextViewText(R.id.tile_detail, detail)
            setViewVisibility(R.id.tile_status, if (showStatus) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.tile_detail, if (showDetail) View.VISIBLE else View.GONE)
            if (expanded) {
                setInt(R.id.tile_title, "setMaxLines", if (size.width < 120f) 2 else 1)
                setInt(R.id.tile_status, "setMaxLines", if (size.height >= maxOf(128f, 108f * fontScale)) 2 else 1)
            }
            setViewVisibility(R.id.tile_icon, if (horizontal && size.width / fontScale < 110f) View.GONE else View.VISIBLE)
            val iconSize = if (expanded && size.width / fontScale >= 104f) 36f else 24f
            setViewLayoutWidth(R.id.tile_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP)
            setViewLayoutHeight(R.id.tile_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP)
            val px = (padding * context.resources.displayMetrics.density).toInt()
            setViewPadding(R.id.tile_card, px, px, px, px)
            setContentDescription(R.id.tile_card, "$title，$status，$detail")
            tintButton(
                this, hue, ThemeSettings.widgetStyle(context).opacity,
                R.id.tile_card, R.id.tile_icon, R.id.tile_title, R.id.tile_status, R.id.tile_detail
            )
        }
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
