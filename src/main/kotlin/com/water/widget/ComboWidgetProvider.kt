package com.water.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews

/**
 * 宽条组合小部件：左启动设备、右积分任务。
 *
 * 按实际可用宽高排版：扁条显示操作，拉高补充状态与用水信息，窄高尺寸纵向排列按钮。
 */
class ComboWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = push(context, appWidgetManager, appWidgetIds, null, null)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        onUpdate(context, manager, intArrayOf(id))

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val (deviceOverride, taskOverride) = when (intent.action) {
            // 服务是异步拉起的，点击瞬间还读不到运行状态，先用返回值顶上，
            // 随后服务自己广播的刷新会覆盖掉它。
            ACTION_START_DEVICE -> WidgetSupport.startDevice(context) to null
            ACTION_RUN_TASKS -> null to WidgetSupport.toggleTasks(context)
            else -> return
        }
        val manager = AppWidgetManager.getInstance(context) ?: return
        push(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)), deviceOverride, taskOverride)
    }

    private fun push(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        deviceOverride: String?,
        taskOverride: String?
    ) {
        val info = WidgetSupport.readInfo(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, WidgetSupport.responsiveViews(context, id, listOf(
                SizeF(180f, 40f), SizeF(250f, 72f), SizeF(180f, 160f),
                SizeF(280f, 136f), SizeF(320f, 224f), SizeF(180f, 280f)
            )) { size -> layout(context, size, info, deviceOverride, taskOverride) })
        }
    }

    internal fun layout(
        context: Context,
        size: SizeF,
        info: WidgetSupport.Info,
        deviceOverride: String? = null,
        taskOverride: String? = null
    ): RemoteViews {
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val stacked = size.width < 240f && size.height >= maxOf(120f, 80f * fontScale)
        val full = stacked || size.height >= maxOf(116f, 104f * fontScale)
        val showHeader = full && (!stacked || size.height >= maxOf(184f, 132f * fontScale))
        val showTitles = full || size.height >= 52f * fontScale
        val showIcons = size.width / fontScale >= (if (stacked) 160f else 280f)
        val shortActions = !showTitles && size.width / fontScale < 140f
        val padding = if (size.height < 64f * fontScale || stacked && !showHeader) 4 else 8
        val buttonHeight = (size.height - padding * 2 -
            (if (showHeader) 34f * fontScale + 12f else 0f) - (if (stacked) 8f else 0f)) /
            (if (stacked) 2 else 1)
        val statusLines = if (showTitles && buttonHeight >= 56f * fontScale + 3f) 2 else 1
        val layoutRes = when {
            stacked -> R.layout.widget_combo_stacked
            full -> R.layout.widget_combo
            else -> R.layout.widget_combo_compact
        }
        return RemoteViews(context.packageName, layoutRes).apply {
            setViewVisibility(R.id.combo_header, if (showHeader) View.VISIBLE else View.GONE)
            setTextViewText(R.id.combo_device_name, info.deviceName)
            setTextViewText(R.id.combo_usage_text, info.usageText)
            val deviceStatus = when {
                shortActions && info.deviceCompact == "启动设备" -> "启动"
                !showTitles -> info.deviceCompact
                !showHeader && info.deviceStatus == "待机" -> info.deviceName
                else -> info.deviceStatus
            }
            setTextViewText(R.id.combo_device_status, deviceOverride ?: deviceStatus)
            setTextViewText(R.id.combo_task_status, taskOverride ?: when {
                shortActions -> "任务"
                showTitles -> info.taskStatus
                else -> info.taskCompact
            })
            for (id in listOf(R.id.combo_title_device, R.id.combo_title_task)) {
                setViewVisibility(id, if (showTitles) View.VISIBLE else View.GONE)
            }
            for (id in listOf(R.id.combo_icon_device, R.id.combo_icon_task)) {
                setViewVisibility(id, if (showIcons) View.VISIBLE else View.GONE)
            }
            for (id in listOf(R.id.combo_device_status, R.id.combo_task_status)) {
                setInt(id, "setMaxLines", statusLines)
            }
            val px = (padding * context.resources.displayMetrics.density).toInt()
            setViewPadding(android.R.id.background, px, px, px, px)
            setContentDescription(R.id.combo_btn_device, "启动设备，${info.deviceName}，${deviceOverride ?: info.deviceStatus}，${info.usageText}")
            setContentDescription(R.id.combo_btn_task, "积分任务，${taskOverride ?: info.taskStatus}，${info.taskDetail}")
            bindClicksAndColors(context)
        }
    }

    /**
     * 绑定点击并按用户样式上色。三套布局共用 id，扁条按高度隐藏标题行，
     * RemoteViews 对找不到的 id 静默跳过，所以不用按尺寸分支。
     * 卡片底和按钮背景统一使用用户设置的不透明度，文字和图标保持清晰。
     */
    private fun RemoteViews.bindClicksAndColors(context: Context) {
        setOnClickPendingIntent(
            R.id.combo_btn_device,
            WidgetSupport.actionIntent(
                context,
                ComboWidgetProvider::class.java,
                ACTION_START_DEVICE,
                REQ_START_DEVICE
            )
        )
        setOnClickPendingIntent(
            R.id.combo_btn_task,
            WidgetSupport.actionIntent(
                context,
                ComboWidgetProvider::class.java,
                ACTION_RUN_TASKS,
                REQ_RUN_TASKS
            )
        )
        val style = ThemeSettings.widgetStyle(context)
        WidgetSupport.tintSurface(this, context, R.color.widget_surface, style.opacity)
        WidgetSupport.tintButton(
            this, style.deviceHue, style.opacity, R.id.combo_btn_device, R.id.combo_icon_device,
            R.id.combo_title_device, R.id.combo_device_status
        )
        WidgetSupport.tintButton(
            this, style.taskHue, style.opacity, R.id.combo_btn_task, R.id.combo_icon_task,
            R.id.combo_title_task, R.id.combo_task_status
        )
    }

    companion object {
        const val ACTION_START_DEVICE = "com.water.widget.combo.START_DEVICE"
        const val ACTION_RUN_TASKS = "com.water.widget.combo.RUN_TASKS"
        private const val REQ_START_DEVICE = 2001
        private const val REQ_RUN_TASKS = 2002
    }
}
