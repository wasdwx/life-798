package com.water.widget.ui

import android.content.Context
import com.water.widget.WaterConsumption
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 将服务端当前可见的积分流水与本地确认的设备账单合并到日汇总。
 * 服务端缩短流水窗口时，已经记录到本地的历史不会随之减少。
 *
 * 同一次接水在两处都有记录：账单（金额支付 + 积分抵扣的合计）和
 * type=107 积分流水（只含积分部分，data.sn 即账单 id）。两者按账单 id 合并、取较大值，
 * 所以台账口径是「账单合计」。
 */
object UsageHistoryStore {
    private const val PREFS = "usage_history_v1"

    fun mergeAndRead(context: Context, accountKey: String, scoreJson: JSONObject?): WaterUsageUiState {
        if (accountKey.isBlank()) return WaterUsageUiState()
        return edit(context, accountKey) { it.merge(scoreJson) }.toUiState()
    }

    fun record(context: Context, accountKey: String, consumption: WaterConsumption) {
        if (accountKey.isBlank()) return
        edit(context, accountKey) {
            it.recordLocal(consumption.historyKey, consumption.occurredAt, consumption.scoreEquivalent)
        }
    }

    /** 还没按账单合计入账的账单 id，首页据此只补拉缺的那几张。 */
    fun missingBillIds(context: Context, accountKey: String, billIds: List<String>): List<String> {
        if (accountKey.isBlank()) return emptyList()
        val ledger = edit(context, accountKey) { false }
        return billIds.filterNot { ledger.hasBillTotal("bill:id:$it") }
    }

    fun markBillWithoutCharge(context: Context, accountKey: String, billId: String) {
        if (accountKey.isBlank()) return
        edit(context, accountKey) { it.markBillWithoutCharge("bill:id:$billId") }
    }

    private fun edit(
        context: Context,
        accountKey: String,
        change: (UsageHistoryLedger) -> Boolean
    ): UsageHistoryLedger {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "account_${digest(accountKey)}"
        val ledger = UsageHistoryLedger.fromJson(
            runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
        )
        if (change(ledger)) {
            prefs.edit().putString(key, ledger.toJson().toString()).apply()
        }
        return ledger
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** 已入账的一张账单：记在哪天、记了多少、是否已是账单合计（而不只是积分部分）。 */
internal data class BillEntry(val day: String, val amount: Int, val isTotal: Boolean)

internal class UsageHistoryLedger(
    private val seen: LinkedHashSet<String> = LinkedHashSet(),
    private val dayTotals: MutableMap<String, Int> = linkedMapOf(),
    private var localThrough: Long = 0L,
    private val bills: MutableMap<String, BillEntry> = linkedMapOf()
) {
    fun merge(scoreJson: JSONObject?): Boolean {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) return false
        val records = scoreJson.optJSONArray("data") ?: return false
        var changed = false
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val data = record.optJSONObject("data")
            val amount = readSpentScore(record, data)
            if (amount <= 0) continue
            val time = normalizeEpochMillis(record.optLong("ctime", 0L))
            if (!isConsumption(record, data)) {
                // 只撤销旧版本确实累计过的同一条流水，不清空窗口外的历史。
                if (seen.remove(fingerprint(recordIdentity(record)))) {
                    val day = dayKey(time)
                    dayTotals[day] = (dayTotals[day] ?: 0) - amount
                    changed = true
                }
                continue
            }
            val billId = data?.optString("sn", "")?.trim().orEmpty()
            if (billId.isNotEmpty()) {
                // 旧版本按流水自身 id 累计过的，撤掉后改挂到账单名下，才能和账单来源去重。
                if (seen.remove(fingerprint(recordIdentity(record)))) {
                    val day = dayKey(time)
                    dayTotals[day] = (dayTotals[day] ?: 0) - amount
                    changed = true
                }
                if (recordBill("bill:id:$billId", time, amount, isTotal = false)) changed = true
                continue
            }
            if (time <= localThrough) continue
            if (record(recordIdentity(record), time, amount)) changed = true
        }
        return changed
    }

    fun recordLocal(historyKey: String, time: Long, amount: Int): Boolean {
        val occurredAt = normalizeEpochMillis(time)
        if (historyKey.isBlank() || occurredAt <= 0L || amount <= 0) return false
        val boundaryChanged = occurredAt > localThrough
        if (boundaryChanged) localThrough = occurredAt
        val recorded = if (historyKey.startsWith(BILL_PREFIX)) {
            recordBill(historyKey, occurredAt, amount, isTotal = true)
        } else {
            record(historyKey, occurredAt, amount)
        }
        return recorded || boundaryChanged
    }

    fun hasBillTotal(billKey: String): Boolean = bills[fingerprint(billKey)]?.isTotal == true

    /** 账单没有产生扣费（启动后没接水）：只登记，免得首页每次都重新拉它的详情。 */
    fun markBillWithoutCharge(billKey: String): Boolean {
        val key = fingerprint(billKey)
        val existing = bills[key]
        if (existing?.isTotal == true) return false
        bills[key] = existing?.copy(isTotal = true) ?: BillEntry("", 0, true)
        return true
    }

    private fun record(historyKey: String, occurredAt: Long, amount: Int): Boolean {
        if (!seen.add(fingerprint(historyKey))) return false
        val day = dayKey(occurredAt)
        dayTotals[day] = (dayTotals[day] ?: 0) + amount
        return true
    }

    /** 同一账单只累计一次；后到的金额更大（账单合计 ≥ 积分部分）就补上差额。 */
    private fun recordBill(billKey: String, occurredAt: Long, amount: Int, isTotal: Boolean): Boolean {
        val key = fingerprint(billKey)
        val existing = bills[key]
        if (existing != null) {
            if (amount <= existing.amount && (existing.isTotal || !isTotal)) return false
            val grow = (amount - existing.amount).coerceAtLeast(0)
            dayTotals[existing.day] = (dayTotals[existing.day] ?: 0) + grow
            bills[key] = BillEntry(existing.day, existing.amount + grow, existing.isTotal || isTotal)
            return true
        }
        val day = dayKey(occurredAt)
        // ponytail: 旧版本已按账单 key 累计过的，记了多少未知，只登记不再加总；
        // 旧的混合支付账单可能少算积分部分，要精确就得重建台账。
        if (!seen.remove(key)) dayTotals[day] = (dayTotals[day] ?: 0) + amount
        bills[key] = BillEntry(day, amount, isTotal)
        return true
    }

    fun toUiState(now: Calendar = Calendar.getInstance()): WaterUsageUiState {
        if (dayTotals.isEmpty()) return WaterUsageUiState()
        val todayKey = dayKey(now.timeInMillis)
        val monthPrefix = "%04d-%02d".format(
            Locale.ROOT,
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH) + 1
        )
        val yearPrefix = "%04d".format(Locale.ROOT, now.get(Calendar.YEAR))
        val today = dayTotals[todayKey] ?: 0
        val month = dayTotals.filterKeys { it.startsWith(monthPrefix) }.values.sum()
        val year = dayTotals.filterKeys { it.startsWith(yearPrefix) }.values.sum()
        return WaterUsageUiState(
            todayCostText = moneyText(today),
            monthCostText = moneyText(month),
            yearCostText = moneyText(year),
            todayWaterText = waterText(today),
            monthWaterText = waterText(month),
            yearWaterText = waterText(year)
        )
    }

    fun toJson(): JSONObject {
        val days = JSONObject()
        dayTotals.forEach { (day, total) -> days.put(day, total) }
        val billJson = JSONObject()
        bills.forEach { (key, entry) ->
            billJson.put(
                key,
                JSONObject().put("day", entry.day).put("amount", entry.amount).put("total", entry.isTotal)
            )
        }
        return JSONObject()
            .put("seen", JSONArray(seen.toList()))
            .put("days", days)
            .put("localThrough", localThrough)
            .put("bills", billJson)
    }

    companion object {
        private const val BILL_PREFIX = "bill:id:"

        fun fromJson(json: JSONObject): UsageHistoryLedger {
            val seen = LinkedHashSet<String>()
            json.optJSONArray("seen")?.let { array ->
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(seen::add)
                }
            }
            val days = linkedMapOf<String, Int>()
            json.optJSONObject("days")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val day = keys.next()
                    values.optInt(day, 0).takeIf { it >= 0 }?.let { days[day] = it }
                }
            }
            val bills = linkedMapOf<String, BillEntry>()
            json.optJSONObject("bills")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    values.optJSONObject(key)?.let { entry ->
                        bills[key] = BillEntry(
                            entry.optString("day"),
                            entry.optInt("amount"),
                            entry.optBoolean("total")
                        )
                    }
                }
            }
            return UsageHistoryLedger(seen, days, json.optLong("localThrough", 0L), bills)
        }

        private fun recordIdentity(record: JSONObject): String {
            return arrayOf("id", "sid", "serialNo", "orderId", "bizId")
                .firstNotNullOfOrNull { key -> record.optString(key, "").takeIf(String::isNotBlank) }
                ?: record.toString()
        }

        private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun dayKey(time: Long): String {
            val date = Calendar.getInstance().apply { timeInMillis = time }
            return "%04d-%02d-%02d".format(
                Locale.ROOT,
                date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1,
                date.get(Calendar.DAY_OF_MONTH)
            )
        }

        private fun readSpentScore(record: JSONObject, data: JSONObject?): Int {
            val keys = arrayOf("spend", "score", "changeScore", "change_score", "amount", "value", "num", "points")
            readNonZero(data, "spend")?.let { return kotlin.math.abs(it) }
            readNonZero(record, "spend")?.let { return kotlin.math.abs(it) }
            val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            if (type != 107) return 0
            for (key in keys) {
                if (key != "spend") readNonZero(record, key)?.let { return kotlin.math.abs(it) }
            }
            if (data != null) {
                for (key in keys) {
                    if (key != "spend") readNonZero(data, key)?.let { return kotlin.math.abs(it) }
                }
            }
            return 0
        }

        private fun isConsumption(record: JSONObject, data: JSONObject?): Boolean {
            val type = record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE)
            return type == 107
        }

        private fun readNonZero(json: JSONObject?, key: String): Int? {
            if (json == null || !json.has(key) || json.isNull(key)) return null
            return json.optInt(key, 0).takeIf { it != 0 }
        }

        private fun normalizeEpochMillis(value: Long): Long = when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }

        private fun moneyText(score: Int): String = "¥${String.format(Locale.CHINA, "%.2f", score / 1000.0)}"

        private fun waterText(score: Int): String {
            val millilitres = score * 500 / 160
            return if (millilitres >= 1000) {
                String.format(Locale.CHINA, "%.1f L", millilitres / 1000.0)
            } else {
                val roundedMillilitres = (millilitres + 5) / 10 * 10
                "$roundedMillilitres ml"
            }
        }
    }
}
