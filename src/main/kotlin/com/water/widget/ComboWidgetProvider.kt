package com.water.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.widget.RemoteViews

/**
 * 宽条组合小部件：左启动设备、右积分任务。
 *
 * 用 RemoteViews 的尺寸映射（API 31+）做自适应：4×1 只有按钮，
 * 拉高到 4×2 自动多出设备名与今日用水那一行。两套布局共用同一批 id，
 * 所以绑定点击和写文字都不需要按尺寸分支。
 */
class ComboWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = buildViews(context, null, null)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val views = when (intent.action) {
            // 服务是异步拉起的，点击瞬间还读不到运行状态，先用返回值顶上，
            // 随后服务自己广播的刷新会覆盖掉它。
            ACTION_START_DEVICE -> buildViews(context, WidgetSupport.startDevice(context), null)
            ACTION_RUN_TASKS -> buildViews(context, null, WidgetSupport.runTasks(context))
            else -> return
        }
        val manager = AppWidgetManager.getInstance(context) ?: return
        manager.getAppWidgetIds(ComponentName(context, javaClass)).forEach { id ->
            manager.updateAppWidget(id, views)
        }
    }

    private fun buildViews(
        context: Context,
        deviceOverride: String?,
        taskOverride: String?
    ): RemoteViews {
        val info = WidgetSupport.readInfo(context)
        return RemoteViews(
            mapOf(
                SizeF(180f, 40f) to compactViews(context, info, deviceOverride, taskOverride),
                SizeF(180f, 110f) to fullViews(context, info, deviceOverride, taskOverride)
            )
        )
    }

    private fun fullViews(
        context: Context,
        info: WidgetSupport.Info,
        deviceOverride: String?,
        taskOverride: String?
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_combo).apply {
        setTextViewText(R.id.combo_device_name, info.deviceName)
        setTextViewText(R.id.combo_usage_text, info.usageText)
        setTextViewText(R.id.combo_device_status, deviceOverride ?: info.deviceStatus)
        setTextViewText(R.id.combo_task_status, taskOverride ?: info.taskStatus)
        bindClicksAndColors(context)
    }

    private fun compactViews(
        context: Context,
        info: WidgetSupport.Info,
        deviceOverride: String?,
        taskOverride: String?
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_combo_compact).apply {
        setTextViewText(R.id.combo_device_status, deviceOverride ?: info.deviceCompact)
        setTextViewText(R.id.combo_task_status, taskOverride ?: info.taskCompact)
        bindClicksAndColors(context)
    }

    /**
     * 绑定点击并按用户样式上色。两套布局共用 id，4×1 没有标题行，
     * RemoteViews 对找不到的 id 静默跳过，所以不用按尺寸分支。
     * 卡片底只吃不透明度，按钮保持不透明：卡片全透明时就是浮在壁纸上的两颗按钮。
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
            this, context, style.deviceHue, 100, R.id.combo_btn_device, R.id.combo_icon_device,
            R.id.combo_title_device, R.id.combo_device_status
        )
        WidgetSupport.tintButton(
            this, context, style.taskHue, 100, R.id.combo_btn_task, R.id.combo_icon_task,
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
