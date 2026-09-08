package com.water.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 方块小部件：整块点击即执行积分任务。结构同 DeviceWidgetProvider。 */
class TaskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = push(context, appWidgetManager, appWidgetIds, null)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_RUN_TASKS) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        push(
            context, manager,
            manager.getAppWidgetIds(ComponentName(context, TaskWidgetProvider::class.java)),
            WidgetSupport.runTasks(context)
        )
    }

    private fun push(context: Context, manager: AppWidgetManager, ids: IntArray, override: String?) {
        val info = WidgetSupport.readInfo(context)
        val views = WidgetSupport.tileViews(
            context, TaskWidgetProvider::class.java, ACTION_RUN_TASKS, REQ,
            iconRes = R.drawable.ic_task_star,
            title = "积分任务",
            status = override ?: info.taskStatus,
            compact = override ?: info.taskCompact,
            hue = ThemeSettings.widgetStyle(context).taskHue
        )
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        const val ACTION_RUN_TASKS = "com.water.widget.square.RUN_TASKS"
        private const val REQ = 2201
    }
}
