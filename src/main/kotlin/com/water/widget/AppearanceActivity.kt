package com.water.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.water.widget.ui.AppearanceScreen
import com.water.widget.ui.WaterTheme

/** 外观设置：显示模式 + 桌面小部件的透明度与配色。 */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                AppearanceScreen(
                    mode = ThemeSettings.mode(this),
                    onModeSelect = { mode ->
                        ThemeSettings.setMode(this, mode)
                        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
                        recreate()
                    },
                    style = ThemeSettings.widgetStyle(this),
                    onStyleChange = { ThemeSettings.setWidgetStyle(this, it) }
                )
            }
        }
    }
}
