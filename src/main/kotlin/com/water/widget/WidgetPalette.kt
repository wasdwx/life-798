package com.water.widget

import kotlin.math.abs

/**
 * 桌面小部件配色。用户只选色相，饱和度与明度按深浅模式固定，
 * 任何色相下容器与前景的对比度都够用，不需要用户操心。
 *
 * 四组常量反推自原来手写的青色与琥珀色，默认色相下观感与旧版几乎一致。
 * 不依赖 android.graphics，方便在纯 JVM 单元测试里跑。
 */
object WidgetPalette {
    const val DEFAULT_DEVICE_HUE = 190f
    const val DEFAULT_TASK_HUE = 42f

    /** 预设色相：青、琥珀、红、橙、绿、蓝、紫、粉 */
    val PRESET_HUES = floatArrayOf(190f, 42f, 0f, 25f, 140f, 215f, 265f, 330f)

    fun container(hue: Float, dark: Boolean): Int =
        if (dark) hsv(hue, 0.78f, 0.38f) else hsv(hue, 0.22f, 0.98f)

    fun onContainer(hue: Float, dark: Boolean): Int =
        if (dark) hsv(hue, 0.22f, 0.98f) else hsv(hue, 1f, 0.25f)

    /** 把 0..100 的不透明度写进 alpha 通道。 */
    fun withOpacity(color: Int, percent: Int): Int =
        (color and 0x00FFFFFF) or ((percent.coerceIn(0, 100) * 255 / 100) shl 24)

    fun hsv(h: Float, s: Float, v: Float): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1 - abs((hue / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((hue / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
