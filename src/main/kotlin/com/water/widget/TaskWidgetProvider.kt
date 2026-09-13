package com.water.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** 方块小部件：整块点击即执行积分任务。结构同 DeviceWidgetProvider。 */
class TaskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = push(context, appWidgetManager, appWidgetIds, null)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        onUpdate(context, manager, intArrayOf(id))

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
        ids.forEach { id -> manager.updateAppWidget(id, WidgetSupport.tileViews(
            context, id, TaskWidgetProvider::class.java, ACTION_RUN_TASKS, REQ,
            iconRes = R.drawable.ic_task_star,
            title = "积分任务",
            status = override ?: info.taskStatus,
            compact = override ?: info.taskCompact,
            detail = info.taskDetail,
            hue = ThemeSettings.widgetStyle(context).taskHue
        )) }
    }

    companion object {
        const val ACTION_RUN_TASKS = "com.water.widget.square.RUN_TASKS"
        private const val REQ = 2201
    }
}
