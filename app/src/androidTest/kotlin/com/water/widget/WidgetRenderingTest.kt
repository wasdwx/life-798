package com.water.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 检查真正应用后的 RemoteViews，覆盖配色缓存、按钮透明度和非标准尺寸裁切。 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val info = WidgetSupport.Info(
        deviceName = "宿舍楼三层走廊饮水机",
        deviceStatus = "待机",
        deviceCompact = "启动设备",
        taskStatus = "12,345 · 本次 +1,200",
        taskCompact = "积分 12,345 · +1,200",
        taskDetail = "本次任务已完成",
        usageText = "今日 12.3 L · ¥3.94"
    )

    @Test
    fun cachedViewsApplyBothThemesAndKeepButtonOpacity() = onMain {
        val original = ThemeSettings.widgetStyle(context)
        try {
            for (opacity in listOf(0, 42, 100)) {
                val style = WidgetStyle(opacity = opacity)
                ThemeSettings.setWidgetStyle(context, style)
                // 只构建一次；切换主题时使用同一份缓存，不触发 Provider 更新。
                val cached = ComboWidgetProvider().layout(themedContext(false), SizeF(320f, 56f), info)
                for (dark in listOf(false, true, false)) {
                    val host = render(themedContext(dark), cached, SizeF(320f, 56f))
                    savePreview(host, "opacity_${opacity}_dark_$dark", dark)
                    for ((id, hue) in listOf(
                        R.id.combo_btn_device to style.deviceHue,
                        R.id.combo_btn_task to style.taskHue
                    )) {
                        val button = host.findViewById<View>(id)
                        assertEquals(
                            WidgetPalette.withOpacity(WidgetPalette.container(hue, dark), opacity),
                            button.backgroundTintList!!.defaultColor
                        )
                        assertTrue(button.isClickable)
                    }
                    val surface = host.findViewById<View>(android.R.id.background)
                    assertEquals(opacity * 255 / 100, surface.backgroundTintList!!.defaultColor ushr 24)
                    val text = host.findViewById<TextView>(R.id.combo_device_status)
                    assertEquals(WidgetPalette.onContainer(style.deviceHue, dark), text.currentTextColor)
                    assertEquals(1f, text.alpha, 0f)
                }
            }
        } finally {
            ThemeSettings.setWidgetStyle(context, original)
        }
    }

    @Test
    fun resizedWidgetsKeepVisibleContentInsideTheirBounds() = onMain {
        val failures = mutableListOf<String>()
        fun check(host: ViewGroup, description: String) {
            try { assertContentFits(host, description) } catch (error: AssertionError) {
                failures += error.message.orEmpty()
            }
        }
        val tileSizes = listOf(
            SizeF(40f, 40f), SizeF(56f, 64f), SizeF(64f, 160f), SizeF(120f, 40f),
            SizeF(120f, 72f), SizeF(180f, 104f), SizeF(144f, 176f), SizeF(224f, 224f)
        )
        val comboSizes = listOf(
            SizeF(160f, 40f), SizeF(250f, 56f), SizeF(320f, 72f), SizeF(250f, 112f),
            SizeF(180f, 160f), SizeF(160f, 280f), SizeF(300f, 136f), SizeF(420f, 224f)
        )
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            val themed = themedContext(false, fontScale)
            for (size in tileSizes) {
                val views = WidgetSupport.tileLayout(
                    themed, size, R.drawable.ic_water_drop, "启动设备", info.deviceName,
                    info.deviceCompact, info.usageText, WidgetPalette.DEFAULT_DEVICE_HUE
                )
                val host = render(themed, views, size)
                savePreview(host, "tile_${size}_font_$fontScale")
                check(host, "tile $size font=$fontScale")
            }
            for (size in comboSizes) {
                val views = ComboWidgetProvider().layout(themed, size, info)
                val host = render(themed, views, size)
                savePreview(host, "combo_${size}_font_$fontScale")
                check(host, "combo $size font=$fontScale")
                val deviceBounds = bounds(host, host.findViewById(R.id.combo_btn_device))
                val taskBounds = bounds(host, host.findViewById(R.id.combo_btn_task))
                assertFalse("buttons overlap at $size", Rect.intersects(deviceBounds, taskBounds))
            }
            for (size in listOf(SizeF(300f, 110f), SizeF(300f, 180f))) {
                val views = WaterWidgetProvider.buildLayout(themed, 0, "设备启动中…", size)
                val host = render(themed, views, size)
                savePreview(host, "classic_${size}_font_$fontScale")
                check(host, "classic $size font=$fontScale")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun launcherSizesSupportBothModernAndLegacyOptions() {
        val portrait = SizeF(160f, 200f)
        val landscape = SizeF(280f, 56f)
        val legacy = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 56)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 280)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
        assertEquals(listOf(portrait, landscape), WidgetSupport.widgetSizes(legacy))
        legacy.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
            arrayListOf(landscape, landscape, SizeF(0f, 0f)))
        assertEquals(listOf(landscape), WidgetSupport.widgetSizes(legacy))
        assertTrue(WidgetSupport.widgetSizes(Bundle()).isEmpty())
    }

    private fun themedContext(dark: Boolean, fontScale: Float = 1f): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
        })

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    private fun render(context: Context, views: RemoteViews, size: SizeF): FrameLayout {
        val host = FrameLayout(context)
        host.addView(views.apply(context, host), FrameLayout.LayoutParams(-1, -1))
        val density = context.resources.displayMetrics.density
        val widthSpec = View.MeasureSpec.makeMeasureSpec((size.width * density).toInt(), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec((size.height * density).toInt(), View.MeasureSpec.EXACTLY)
        // 自动字号在布局后会再请求测量，模拟窗口后续帧，避免检查尚未稳定的文字高度。
        repeat(3) {
            host.measure(widthSpec, heightSpec)
            host.layout(0, 0, host.measuredWidth, host.measuredHeight)
        }
        return host
    }

    private fun assertContentFits(host: ViewGroup, description: String) {
        fun check(view: View) {
            if (view.visibility != View.VISIBLE) return
            val bounds = bounds(host, view)
            assertTrue("$description: ${view.javaClass.simpleName} $bounds outside ${host.width}x${host.height}",
                bounds.left >= 0 && bounds.top >= 0 && bounds.right <= host.width && bounds.bottom <= host.height)
            if (view is TextView) {
                assertTrue("$description: text has no space", view.width > 0 && view.height > 0)
                // TextView 的 Layout 可能保留 maxLines 之外的行，检查实际可见文字的字形边界。
                val layout = view.layout
                val lastLine = minOf(layout.lineCount, view.maxLines) - 1
                val glyphs = Rect()
                view.paint.getTextBounds(layout.text.toString(), layout.getLineStart(lastLine), layout.getLineVisibleEnd(lastLine), glyphs)
                val bottom = view.baseline + layout.getLineBaseline(lastLine) - layout.getLineBaseline(0) + glyphs.bottom
                assertTrue("$description: text '${view.text}' bottom=$bottom height=${view.height} is vertically clipped",
                    bottom <= view.height - view.compoundPaddingBottom + 1)
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) check(view.getChildAt(index))
        }
        for (index in 0 until host.childCount) check(host.getChildAt(index))
    }

    private fun savePreview(host: View, name: String, dark: Boolean = false) {
        val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (dark) 0xFF364047.toInt() else 0xFFBBCBD4.toInt())
        host.draw(canvas)
        val directory = File(context.filesDir, "widget-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun bounds(host: ViewGroup, view: View): Rect = Rect(0, 0, view.width, view.height).also {
        host.offsetDescendantRectToMyCoords(view, it)
    }
}
