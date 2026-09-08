package com.water.widget

import android.content.Context
import android.content.SharedPreferences

/** 用户可选的界面模式。默认跟随系统；修改后由 Activity recreate 立即生效。 */
enum class AppThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/** 桌面小部件的用户样式：背景不透明度 0..100，两个按钮各一个色相 0..360。 */
data class WidgetStyle(
    val opacity: Int = 100,
    val deviceHue: Float = WidgetPalette.DEFAULT_DEVICE_HUE,
    val taskHue: Float = WidgetPalette.DEFAULT_TASK_HUE
)

object ThemeSettings {
    private const val PREFS = "appearance_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_WIDGET_OPACITY = "widget_opacity"
    private const val KEY_WIDGET_DEVICE_HUE = "widget_device_hue"
    private const val KEY_WIDGET_TASK_HUE = "widget_task_hue"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): AppThemeMode = AppThemeMode.fromStorage(
        prefs(context).getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storageValue)
    )

    fun setMode(context: Context, mode: AppThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
    }

    fun isDark(context: Context): Boolean = when (mode(context)) {
        AppThemeMode.SYSTEM -> (context.resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    fun widgetStyle(context: Context): WidgetStyle = prefs(context).let {
        WidgetStyle(
            opacity = it.getInt(KEY_WIDGET_OPACITY, 100),
            deviceHue = it.getFloat(KEY_WIDGET_DEVICE_HUE, WidgetPalette.DEFAULT_DEVICE_HUE),
            taskHue = it.getFloat(KEY_WIDGET_TASK_HUE, WidgetPalette.DEFAULT_TASK_HUE)
        )
    }

    /** 保存后顺手刷新桌面，设置页里改完立刻能看到。 */
    fun setWidgetStyle(context: Context, style: WidgetStyle) {
        prefs(context).edit()
            .putInt(KEY_WIDGET_OPACITY, style.opacity)
            .putFloat(KEY_WIDGET_DEVICE_HUE, style.deviceHue)
            .putFloat(KEY_WIDGET_TASK_HUE, style.taskHue)
            .apply()
        WidgetSupport.refreshAll(context)
    }
}
