package com.water.widget.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WaterBillLogParserTest {
    private fun bill(id: String, utime: Long, cata: Int = 6, status: Int = 3, type: Int = 91) = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("cata", cata)
        .put("status", status)
        .put("utime", utime)

    @Test
    fun listKeepsSettledWaterBillsNewestFirstWithoutDuplicates() {
        val json = JSONObject().put("code", 0).put(
            "data",
            JSONArray()
                .put(bill("old", 1_800_000_000_000L).put("dev", JSONObject().put("name", "3 楼饮水机")))
                .put(bill("new", 1_800_000_100_000L))
                .put(bill("new", 1_800_000_100_000L))
                .put(bill("recharge", 1_800_000_200_000L, cata = 1))
                .put(bill("pending", 1_800_000_300_000L, status = 1))
        )

        val bills = WaterBillLogParser.list(json)

        assertEquals(listOf("new", "old"), bills.map { it.id })
        assertEquals("接水消费", bills[0].title)
        assertEquals("3 楼饮水机", bills[1].title)
    }

    @Test
    fun listFailureCarriesUiMessage() {
        val error = assertThrows(IllegalStateException::class.java) {
            WaterBillLogParser.list(JSONObject().put("code", -1))
        }
        assertEquals("接水记录获取失败，请下拉重试", error.message)
    }

    @Test
    fun mixedPaymentSplitsGiftCashAndPoints() {
        val full = bill("b1", 1L)
            .put("payment", 0.30)
            .put("mk", JSONObject().put("olGift", 0.10))
            .put("promo", JSONObject().put("type", 4))
            .put("discount", 0.123)

        val payment = WaterBillLogParser.payment(view(full), "b1")

        assertEquals("¥0.42", payment.totalText)
        assertEquals(
            listOf(
                PaymentPartUiState("钱包礼品金", "¥0.10"),
                PaymentPartUiState("其他金额支付", "¥0.20"),
                PaymentPartUiState("积分抵扣", "123 积分 · ¥0.12")
            ),
            payment.parts
        )
    }

    @Test
    fun cashOnlyAndZeroBills() {
        val cash = WaterBillLogParser.payment(view(bill("b1", 1L).put("payment", 0.16)), "b1")
        assertEquals(listOf(PaymentPartUiState("金额支付（渠道未标明）", "¥0.16")), cash.parts)

        val zero = WaterBillLogParser.payment(view(bill("b2", 1L).put("payment", 0.0)), "b2")
        assertEquals("¥0.00", zero.totalText)
        assertEquals(emptyList<PaymentPartUiState>(), zero.parts)
    }

    @Test
    fun rejectsMismatchedIdAndInconsistentAmounts() {
        assertThrows(IllegalStateException::class.java) {
            WaterBillLogParser.payment(view(bill("b1", 1L).put("payment", 0.1)), "other")
        }
        val error = assertThrows(IllegalStateException::class.java) {
            WaterBillLogParser.payment(
                view(bill("b1", 1L).put("payment", 0.1).put("mk", JSONObject().put("olGift", 0.2))),
                "b1"
            )
        }
        assertEquals("支付明细金额异常，请稍后重试", error.message)
    }

    private fun view(bill: JSONObject) = JSONObject().put("code", 0).put("data", JSONObject().put("bill", bill))
}
