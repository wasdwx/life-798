package com.water.widget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 小部件设备按钮的动作名。
 *
 * 会话属于当前设备时按钮要变成「停止设备」，中间态（启动中/停止中/结算中）则只显示阶段名，
 * 与首页设备列表的按钮保持一致；会话属于别的设备时仍然显示「启动设备」。
 */
class WidgetDeviceActionTest {

    @After
    fun resetSession() {
        WaterControl.current()?.let { WaterControl.clear(it.reservationId) }
    }

    @Test
    fun labelFollowsTheSessionPhase() {
        assertEquals("启动设备", WidgetSupport.deviceActionLabel("dev-1"))

        WaterControl.start(1L, "13800000000", "dev-1")
        assertEquals("启动中", WidgetSupport.deviceActionLabel("dev-1"))
        // 别的设备不该显示「停止设备」，否则会引导用户去停一台没在出水的机器。
        assertEquals("启动设备", WidgetSupport.deviceActionLabel("dev-2"))
        assertEquals("启动设备", WidgetSupport.deviceActionLabel(null))

        WaterControl.setPhase(1L, WaterControlPhase.RUNNING)
        assertEquals("停止设备", WidgetSupport.deviceActionLabel("dev-1"))

        WaterControl.setPhase(1L, WaterControlPhase.STOPPING)
        assertEquals("停止中", WidgetSupport.deviceActionLabel("dev-1"))

        WaterControl.setPhase(1L, WaterControlPhase.SETTLING)
        assertEquals("结算中", WidgetSupport.deviceActionLabel("dev-1"))

        WaterControl.clear(1L)
        assertEquals("启动设备", WidgetSupport.deviceActionLabel("dev-1"))
    }

    @Test
    fun shortLabelDropsTheDeviceSuffix() {
        // 极窄尺寸下只留动词，规则与 readInfo 里写进 Info.deviceActionShort 的一致。
        assertEquals("启动", "启动设备".removeSuffix("设备"))
        assertEquals("停止", "停止设备".removeSuffix("设备"))
        // 阶段名本身不含「设备」，缩写后保持不变。
        listOf("启动中", "停止中", "结算中").forEach { phase ->
            assertEquals(phase, phase.removeSuffix("设备"))
        }
    }
}
