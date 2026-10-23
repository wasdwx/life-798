package com.water.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** 方块小部件：整块点击即启动默认设备。1×1 起步，拉大自动换更详细的档，见 WidgetSupport.tileViews。 */
class DeviceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) = push(context, appWidgetManager, appWidgetIds, null)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        onUpdate(context, manager, intArrayOf(id))

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE_DEVICE && intent.action != LEGACY_ACTION_START_DEVICE) return
        val manager = AppWidgetManager.getInstance(context) ?: return
        // 服务是异步拉起的，点击瞬间还读不到运行状态，先用返回值顶上，
        // 随后服务自己广播的刷新会覆盖掉它。
        push(
            context, manager,
            manager.getAppWidgetIds(ComponentName(context, DeviceWidgetProvider::class.java)),
            WidgetSupport.toggleDevice(context)
        )
    }

    private fun push(context: Context, manager: AppWidgetManager, ids: IntArray, override: String?) {
        val info = WidgetSupport.readInfo(context)
        // 空闲时显示设备名比显示「待机」有用；会话进行中才让位给实时状态
        val idle = info.deviceName.takeIf { info.deviceStatus == "待机" } ?: info.deviceStatus
        ids.forEach { id -> manager.updateAppWidget(id, WidgetSupport.tileViews(
            context, id, DeviceWidgetProvider::class.java, ACTION_TOGGLE_DEVICE, REQ,
            iconRes = R.drawable.ic_water_drop,
            title = info.deviceAction,
            status = override ?: idle,
            compact = override ?: info.deviceCompact,
            detail = if (info.deviceStatus == "待机") info.usageText else "${info.deviceName}\n${info.usageText}",
            hue = ThemeSettings.widgetStyle(context).deviceHue
        )) }
    }

    companion object {
        const val ACTION_TOGGLE_DEVICE = "com.water.widget.square.TOGGLE_DEVICE"

        /** 旧版本留在启动器里的点击动作，升级后仍可能被触发，继续接住。 */
        private const val LEGACY_ACTION_START_DEVICE = "com.water.widget.square.START_DEVICE"
        private const val REQ = 2101
    }
}
