package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「会话已结束」信号与「有金额的消费」判定之间的分工。
 *
 * 真机数据：启动后未实际接水时账单 payment 是 0，列表上的积分抵扣也可能读不到，
 * 所以 latestSince 认不出它们；hasSettledRecordSince 必须能认出来，
 * 否则监测会一路空转到十分钟超时。
 */
class WaterBillParserSettlementTest {
    private val since = 1_788_327_983_000L

    private fun bill(
        ctime: Long,
        utime: Long,
        payment: Double,
        status: Int = 3,
        type: Int = 91,
        id: String = "bill-1"
    ) = JSONObject()
        .put("code", 0)
        .put(
            "data",
            JSONArray().put(
                JSONObject()
                    .put("id", id)
                    .put("type", type)
                    .put("status", status)
                    .put("ctime", ctime)
                    .put("utime", utime)
                    .put("payment", payment)
                    .put("msg", "管线/饮水机设备消费")
            )
        )

    @Test
    fun zeroPaymentBillCountsAsSessionEndButNotAsConsumption() {
        // 启动未接水：账单 ctime 回填为会话开始时刻，utime 是 13 秒后的结算时刻
        val json = bill(ctime = since, utime = since + 13_079L, payment = 0.0)

        assertNull(
            "0 元账单不该被当成有金额的消费",
            WaterBillParser.latestSince(json, since)
        )
        assertTrue(
            "0 元账单必须能作为会话结束信号，否则会一直轮询到超时",
            WaterBillParser.hasSettledRecordSince(json, since)
        )
    }

    @Test
    fun ignoresBillsFromBeforeThisSession() {
        val json = bill(ctime = since - 169_000L, utime = since - 156_000L, payment = 0.0)

        assertFalse(WaterBillParser.hasSettledRecordSince(json, since))
    }

    @Test
    fun ignoresBillsAlreadyPresentInBaseline() {
        val json = bill(ctime = since, utime = since + 13_000L, payment = 0.0)
        val baseline = WaterBillParser.recordKeys(json)

        assertFalse(
            "启动前就存在的账单不能被当成本次会话结束",
            WaterBillParser.hasSettledRecordSince(json, since, excludedRecordKeys = baseline)
        )
    }

    @Test
    fun ignoresUnsettledBills() {
        val json = bill(ctime = since, utime = since + 13_000L, payment = 0.0, status = 1)

        assertFalse(
            "待确认订单不能作为会话结束信号",
            WaterBillParser.hasSettledRecordSince(json, since)
        )
    }

    @Test
    fun stillDetectsPaidBillAsConsumption() {
        // 现金支付路径不受影响
        val json = bill(ctime = since, utime = since + 13_000L, payment = 0.38)

        assertTrue(WaterBillParser.hasSettledRecordSince(json, since))
        val consumption = WaterBillParser.latestSince(json, since)
        assertTrue(consumption != null && consumption.paymentYuan == 0.38)
    }

    @Test
    fun pointDiscountCountsTowardTotal() {
        val json = bill(ctime = since, utime = since + 13_000L, payment = 0.0)
        json.getJSONArray("data").getJSONObject(0)
            .put("promo", JSONObject().put("type", 4))
            .put("discount", 0.16)

        assertEquals(0.16, WaterBillParser.latestSince(json, since)!!.paymentYuan!!, 1e-9)
    }

    @Test
    fun discountWithoutPointPromoIsIgnored() {
        val json = bill(ctime = since, utime = since + 13_000L, payment = 0.0)
        json.getJSONArray("data").getJSONObject(0)
            .put("promo", JSONObject().put("type", 1))
            .put("discount", 0.16)

        assertNull(WaterBillParser.latestSince(json, since))
    }

    @Test
    fun viewFullSuppliesAmountMissingFromList() {
        val list = bill(ctime = since, utime = since + 13_000L, payment = 0.0)
        val billId = WaterBillParser.settledBillIdSince(list, since)
        assertEquals("bill-1", billId)

        val full = list.getJSONArray("data").getJSONObject(0)
            .put("payment", 0.1)
            .put("promo", JSONObject().put("type", 4))
            .put("discount", 0.06)
        val view = JSONObject().put("code", 0).put("data", JSONObject().put("bill", full))

        assertEquals(0.16, WaterBillParser.fromViewFull(view, billId!!, since)!!.paymentYuan!!, 1e-9)
        assertNull("账单 id 对不上不能算作本次消费", WaterBillParser.fromViewFull(view, "other", since))
    }

    @Test
    fun nonWaterCategoryIsNotSettlement() {
        val json = bill(ctime = since, utime = since + 13_000L, payment = 0.38)
        json.getJSONArray("data").getJSONObject(0).put("cata", 1)

        assertFalse(WaterBillParser.hasSettledRecordSince(json, since))
        assertNull(WaterBillParser.latestSince(json, since))
    }

    @Test
    fun zeroDetailMustBeExplicitAndMalformedDetailsAreNotFreeWater() {
        val record = bill(since, since + 1_000, 0.0).getJSONArray("data").getJSONObject(0)
        val view = JSONObject().put("code", 0).put("data", JSONObject().put("bill", record))
        assertNull(WaterBillParser.fromViewFull(view, "bill-1", since))
        assertEquals(0.0, WaterBillParser.fromViewFull(view, "bill-1", since, includeZero = true)!!.paymentYuan!!, 0.0)
        record.remove("payment")
        assertNull(WaterBillParser.fromViewFull(view, "bill-1", since, includeZero = true))
        record.put("payment", 0).put("promo", JSONObject().put("type", 4))
        assertNull(WaterBillParser.fromViewFull(view, "bill-1", since, includeZero = true))
        record.put("discount", 0.16)
        assertEquals(0.16, WaterBillParser.fromViewFull(view, "bill-1", since, includeZero = true)!!.paymentYuan!!, 1e-9)
    }
}
