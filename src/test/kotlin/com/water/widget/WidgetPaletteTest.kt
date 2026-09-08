package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class WidgetPaletteTest {
    @Test
    fun hsvHitsPrimaries() {
        assertEquals(0xFFFF0000.toInt(), WidgetPalette.hsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), WidgetPalette.hsv(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), WidgetPalette.hsv(240f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), WidgetPalette.hsv(360f, 1f, 1f))
    }

    @Test
    fun opacityOnlyTouchesAlpha() {
        val red = 0xFFFF0000.toInt()
        assertEquals(0x00FF0000, WidgetPalette.withOpacity(red, 0))
        assertEquals(red, WidgetPalette.withOpacity(red, 100))
        assertEquals(0x7FFF0000, WidgetPalette.withOpacity(red, 50))
        assertEquals(red, WidgetPalette.withOpacity(red, 250))
    }

    /** 任何预设色相、任何模式下，文字与容器都要过 WCAG AA 的 4.5:1。 */
    @Test
    fun everyPresetKeepsReadableContrast() {
        for (hue in WidgetPalette.PRESET_HUES) for (dark in listOf(false, true)) {
            val ratio = contrast(WidgetPalette.container(hue, dark), WidgetPalette.onContainer(hue, dark))
            assertTrue("hue=$hue dark=$dark ratio=$ratio", ratio >= 4.5)
        }
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun lin(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }
}
