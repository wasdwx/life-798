package com.water.widget

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class AutomationTest {
    @Test fun dailyAlarmChoosesAFutureInstantAndRollsToTomorrowAtTheBoundary() {
        val before = ZonedDateTime.parse("2026-09-30T08:59:59+08:00[Asia/Shanghai]")
        assertEquals("2026-09-30T09:00+08:00[Asia/Shanghai]", AutoTasks.nextRun(before, 540).toString())
        assertEquals(1, AutoTasks.nextRun(before.plusSeconds(1), 540).dayOfMonth)
        val gap = ZonedDateTime.parse("2026-03-08T01:00:00-05:00[America/New_York]")
        assertTrue(AutoTasks.nextRun(gap, 150).isAfter(gap))
    }

    @Test fun stopDeviceRequiresTheRunningSessionOwnerAndStaleCallbacksCannotClearANewSession() {
        val session = WaterControlSession(1, "account-a", "device-a", WaterControlPhase.RUNNING)
        assertTrue(session.canStop("account-a", "device-a"))
        assertFalse(session.canStop("account-b", "device-a"))
        assertFalse(session.canStop("account-a", "device-b"))
        assertFalse(session.copy(phase = WaterControlPhase.STARTING).canStop("account-a", "device-a"))
        assertFalse(session.copy(phase = WaterControlPhase.STOPPING).canStop("account-a", "device-a"))
        try {
            WaterControl.start(2, "account-b", "device-b")
            WaterControl.clear(1)
            WaterControl.setPhase(1, WaterControlPhase.RUNNING)
            assertEquals(2L, WaterControl.current()?.reservationId)
            assertEquals(WaterControlPhase.STARTING, WaterControl.current()?.phase)
        } finally {
            WaterControl.clear(2)
        }
    }
}
