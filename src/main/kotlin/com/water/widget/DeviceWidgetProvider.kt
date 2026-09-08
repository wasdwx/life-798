package com.water.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** 方块小部件：整块点击即启动默认设备。1×1 起步，拉大自动换更详细的档，见 WidgetSupport.tileViews。 */
class DeviceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = push(context, appWidgetManager, appWidgetIds, null)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_START_DEVICE) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        // 服务是异步拉起的，点击瞬间还读不到运行状态，先用返回值顶上，
        // 随后服务自己广播的刷新会覆盖掉它。
        push(
            context, manager,
            manager.getAppWidgetIds(ComponentName(context, DeviceWidgetProvider::class.java)),
            WidgetSupport.startDevice(context)
        )
    }

    private fun push(context: Context, manager: AppWidgetManager, ids: IntArray, override: String?) {
        val info = WidgetSupport.readInfo(context)
        // 空闲时显示设备名比显示「待机」有用；会话进行中才让位给实时状态
        val idle = info.deviceName.takeIf { info.deviceStatus == "待机" } ?: info.deviceStatus
        val views = WidgetSupport.tileViews(
            context, DeviceWidgetProvider::class.java, ACTION_START_DEVICE, REQ,
            iconRes = R.drawable.ic_water_drop,
            title = "启动设备",
            status = override ?: idle,
            compact = override ?: info.deviceCompact,
            hue = ThemeSettings.widgetStyle(context).deviceHue
        )
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        const val ACTION_START_DEVICE = "com.water.widget.square.START_DEVICE"
        private const val REQ = 2101
    }
}
