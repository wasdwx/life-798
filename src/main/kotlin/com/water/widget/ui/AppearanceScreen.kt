package com.water.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.widget.AppThemeMode
import com.water.widget.R
import com.water.widget.WidgetPalette
import com.water.widget.WidgetStyle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 外观设置页：显示模式 + 桌面小部件的透明度与配色。
 * 拖动过程只改本地草稿驱动预览，松手才落盘并刷新桌面，避免拖动中狂发广播。
 */
@Composable
fun AppearanceScreen(
    mode: AppThemeMode,
    onModeSelect: (AppThemeMode) -> Unit,
    style: WidgetStyle,
    onStyleChange: (WidgetStyle) -> Unit
) {
    var draft by remember { mutableStateOf(style) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("外观设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("应用的显示模式，以及桌面小部件的透明度与配色。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("显示模式") {
            AppThemeMode.entries.forEach { item ->
                val selected = item == mode
                Surface(
                    onClick = { onModeSelect(item) },
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        if (selected) Icon(Icons.Default.Check, contentDescription = "已选中", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        SectionCard("桌面小部件", "小部件跟随系统深浅色。文字始终不透明，只有背景吃透明度。") {
            WidgetPreview(draft)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("背景不透明度", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("${draft.opacity}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Slider(
                value = draft.opacity.toFloat(),
                onValueChange = { draft = draft.copy(opacity = it.roundToInt()) },
                onValueChangeFinished = { onStyleChange(draft) },
                valueRange = 0f..100f
            )
            HueField("启动设备颜色", draft.deviceHue, onChange = { draft = draft.copy(deviceHue = it) }) { onStyleChange(draft) }
            HueField("积分任务颜色", draft.taskHue, onChange = { draft = draft.copy(taskHue = it) }) { onStyleChange(draft) }
            TextButton(
                onClick = { draft = WidgetStyle(); onStyleChange(draft) },
                enabled = draft != WidgetStyle(),
                modifier = Modifier.align(Alignment.End)
            ) { Text("恢复默认") }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            content()
        }
    }
}

/** 一对 2×1 小部件铺在一块壁纸感的渐变上，透明度才看得出来。 */
@Composable
private fun WidgetPreview(style: WidgetStyle) {
    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(scheme.primary.copy(alpha = 0.55f), scheme.tertiary.copy(alpha = 0.45f), scheme.surfaceVariant)
                )
            )
            .padding(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PreviewTile(Modifier.weight(1f), style.deviceHue, style.opacity, dark, R.drawable.ic_water_drop, "启动设备")
            PreviewTile(Modifier.weight(1f), style.taskHue, style.opacity, dark, R.drawable.ic_task_star, "积分任务")
        }
    }
}

@Composable
private fun PreviewTile(modifier: Modifier, hue: Float, opacity: Int, dark: Boolean, iconRes: Int, label: String) {
    val container = Color(WidgetPalette.withOpacity(WidgetPalette.container(hue, dark), opacity))
    val on = Color(WidgetPalette.onContainer(hue, dark))
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).background(container).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = on, modifier = Modifier.size(20.dp))
        Text(label, color = on, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 8 个预设圆点当快捷入口，色相条做微调，两者改的是同一个值。 */
@Composable
private fun HueField(label: String, hue: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            WidgetPalette.PRESET_HUES.forEach { preset ->
                val selected = abs(preset - hue) < 0.5f
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(WidgetPalette.container(preset, dark)))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                            shape = CircleShape
                        )
                        .clickable { onChange(preset); onFinished() },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(Icons.Default.Check, contentDescription = "已选中", tint = Color(WidgetPalette.onContainer(preset, dark)), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        HueSlider(hue, onChange, onFinished)
    }
}

private val RAINBOW = (0..6).map { Color.hsv(it * 60f, 1f, 1f) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Slider(
        value = hue,
        onValueChange = onChange,
        onValueChangeFinished = onFinished,
        valueRange = 0f..360f,
        modifier = Modifier.fillMaxWidth(),
        thumb = {
            Box(
                Modifier
                    .size(24.dp)
                    .shadow(2.dp, CircleShape)
                    .background(Color(WidgetPalette.container(hue, dark)), CircleShape)
                    .border(2.dp, Color.White, CircleShape)
            )
        },
        track = {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(Brush.horizontalGradient(RAINBOW)))
        }
    )
}
