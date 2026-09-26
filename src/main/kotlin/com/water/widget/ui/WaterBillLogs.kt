package com.water.widget.ui

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

data class PaymentPartUiState(val label: String, val value: String)

data class WaterBillPaymentUiState(val totalText: String, val parts: List<PaymentPartUiState>)

data class WaterBillLogUiState(
    val id: String,
    val timeText: String,
    val title: String,
    val payment: WaterBillPaymentUiState? = null,
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * 接水消费记录。/bill/lst-owner 只说明有哪些接水账单，
 * 金额与支付明细要逐张从 /bill/view-full 取。
 * 解析失败抛 [IllegalStateException]，message 即界面文案。
 */
object WaterBillLogParser {
    fun list(json: JSONObject?): List<WaterBillLogUiState> {
        if (json == null || json.optInt("code", -1) != 0) error("接水记录获取失败，请下拉重试")
        val records = json.optJSONArray("data") ?: error("接水记录格式异常，请稍后重试")
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        return (0 until records.length())
            .mapNotNull(records::optJSONObject)
            .filter { isSettledWaterBill(it) && it.optString("id").isNotBlank() }
            .distinctBy { it.optString("id") }
            .sortedByDescending(::occurredAt)
            .map { bill ->
                WaterBillLogUiState(
                    id = bill.optString("id"),
                    timeText = fmt.format(Date(occurredAt(bill))),
                    title = bill.optJSONObject("dev")?.optString("name")?.takeIf(String::isNotBlank) ?: "接水消费"
                )
            }
    }

    fun payment(json: JSONObject?, billId: String): WaterBillPaymentUiState {
        if (json == null || json.optInt("code", -1) != 0) error("支付明细获取失败，请重试")
        val bill = json.optJSONObject("data")?.optJSONObject("bill") ?: error("支付明细暂不可用，请重试")
        if (bill.optString("id") != billId || !isSettledWaterBill(bill)) error("支付明细暂不可用，请重试")

        // 全部换成「厘」整数再加减，避免浮点误差。
        val payment = mills(bill.optDouble("payment", 0.0))
        val gift = mills(bill.optJSONObject("mk")?.optDouble("olGift", 0.0) ?: 0.0)
        val points = if (bill.optJSONObject("promo")?.optInt("type") == 4) {
            mills(bill.optDouble("discount", 0.0))
        } else {
            0L
        }
        if (payment < 0 || gift < 0 || points < 0 || gift > payment) error("支付明细金额异常，请稍后重试")

        val parts = buildList {
            if (gift > 0) add(PaymentPartUiState("钱包礼品金", money(gift)))
            val cash = payment - gift
            if (cash > 0) add(PaymentPartUiState(if (gift > 0) "其他金额支付" else "金额支付（渠道未标明）", money(cash)))
            // 1 积分 = 1 厘
            if (points > 0) add(PaymentPartUiState("积分抵扣", "$points 积分 · ${money(points)}"))
        }
        return WaterBillPaymentUiState(money(payment + points), parts)
    }

    private fun isSettledWaterBill(bill: JSONObject) =
        bill.optInt("type") == 91 && bill.optInt("cata") == 6 && bill.optInt("status") == 3

    private fun occurredAt(bill: JSONObject): Long {
        val updated = millis(bill.optLong("utime", 0L))
        return if (updated > 0L) updated else millis(bill.optLong("ctime", 0L))
    }

    private fun millis(value: Long) = if (value in 1 until 10_000_000_000L) value * 1000L else value

    /** 非有限值记为 -1，让上面的金额校验报异常。 */
    private fun mills(yuan: Double) = if (yuan.isFinite()) (yuan * 1000).roundToLong() else -1L

    private fun money(mills: Long) = "¥${String.format(Locale.CHINA, "%.2f", mills / 1000.0)}"
}
