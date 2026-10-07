package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class WaterServiceTimingTest {
    @Test
    fun slowScoreBaselineCannotAttributeAnOldSameSecondCharge() {
        val startedAt = 1_788_327_983_000L
        val record = JSONObject().put("id", "previous").put("type", 107)
            .put("ctime", startedAt / 1_000).put("spend", 160)
        val response = JSONObject().put("code", 0).put("data", JSONArray().put(record))
        assertNull(WaterConsumptionParser.latestSince(
            response, WaterService.scoreSinceMillis(startedAt, false)
        ))
        record.put("ctime", startedAt / 1_000 + 1)
        assertEquals(160, WaterConsumptionParser.latestSince(
            response, WaterService.scoreSinceMillis(startedAt, false)
        )!!.spentScore)
        assertEquals(startedAt, WaterService.scoreSinceMillis(startedAt, true))
    }

    @Test
    fun physicalStopGetsFastPollingWithoutAnAppStopRequest() {
        assertEquals(2_000L, WaterService.nextPollDelayMillis(8_000L, 0))
        assertEquals(2_000L, WaterService.nextPollDelayMillis(59_999L, 0))
    }

    @Test
    fun longSessionsBackOffButExplicitStopStillAcceleratesPolling() {
        assertEquals(10_000L, WaterService.nextPollDelayMillis(60_000L, 0))
        assertEquals(10_000L, WaterService.nextPollDelayMillis(300_000L, 0))
        assertEquals(2_000L, WaterService.nextPollDelayMillis(300_000L, 5))
    }
}
