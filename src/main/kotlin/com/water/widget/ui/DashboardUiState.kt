package com.water.widget.ui

import com.water.widget.Account
import com.water.widget.TokenValidity
import com.water.widget.WaterEstimate
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

data class DashboardSummaryUiState(
    val accountTitle: String,
    val accountSubtitle: String,
    val scoreTitle: String,
    val scoreSubtitle: String,
    val hasAccount: Boolean,
    val hasAppToken: Boolean,
    val hasDevices: Boolean,
    val accountCount: Int,
    val devices: List<DeviceUiState> = emptyList(),
    val usage: WaterUsageUiState = WaterUsageUiState(),
    /** 设备（用水）登录的检测结果文案，未检测过时回落为"已完成/未完成"。 */
    val appLoginStatusText: String = "未完成"
)

data class DeviceUiState(
    val id: String,
    val name: String,
    val isControlCenterDevice: Boolean = false
)

data class WaterUsageUiState(
    val todayCostText: String = "--",
    val monthCostText: String = "--",
    val yearCostText: String = "--",
    val todayWaterText: String = "--",
    val monthWaterText: String = "--",
    val yearWaterText: String = "--",
    val showWaterEstimate: Boolean = true,
    /** 统计口径说明：哪些记录被计入。 */
    val syncNote: String = "仅统计已获取的记录"
)

data class DashboardAccountUiState(
    val phone: String,
    val title: String,
    val subtitle: String,
    val tokenSummary: String,
    val deviceSummary: String,
    val isCurrent: Boolean
)

object DashboardUiStateFactory {
    fun from(
        account: Account?,
        accountCount: Int,
        validScore: Int? = null,
        scoreJson: JSONObject? = null,
        usageOverride: WaterUsageUiState? = null,
        walletBalance: Double? = null,
        waterEstimate: WaterEstimate = WaterEstimate(),
        walletMessage: String? = null,
        appStatus: TokenValidity = TokenValidity.UNCHECKED,
        alipayStatus: TokenValidity = TokenValidity.UNCHECKED
    ): DashboardSummaryUiState {
        if (account == null) {
            return DashboardSummaryUiState(
                accountTitle = "未登录",
                accountSubtitle = "完成设备登录后即可使用设备",
                scoreTitle = "--",
                scoreSubtitle = "登录后查看积分与钱包",
                hasAccount = false,
                hasAppToken = false,
                hasDevices = false,
                accountCount = accountCount,
                devices = emptyList(),
                usage = WaterUsageUiState(showWaterEstimate = waterEstimate.visible)
            )
        }

        val title = account.name?.takeIf { it.isNotBlank() }
            ?: account.phone?.takeIf { it.isNotBlank() }
            ?: "已登录账户"
        val hasAppToken = account.hasAppToken()
        val hasDevices = account.hasDevices()
        return DashboardSummaryUiState(
            accountTitle = title,
            accountSubtitle = loginSummary(account, appStatus, alipayStatus),
            scoreTitle = if (validScore != null && walletBalance != null) {
                "≈¥" + String.format(Locale.CHINA, "%.2f", validScore / 1000.0 + walletBalance)
            } else "--",
            scoreSubtitle = listOf(
                walletBalance?.let { "钱包 ¥" + String.format(Locale.CHINA, "%.2f", it) } ?: "钱包未获取",
                validScore?.let { "积分 $it（≈" + moneyText(it) + "）" } ?: "积分未获取"
            ).joinToString("  +  ") + walletMessage?.let { "\n$it" }.orEmpty(),
            hasAccount = true,
            hasAppToken = hasAppToken,
            hasDevices = hasDevices,
            accountCount = accountCount,
            devices = account.rememberedDevices().map { id ->
                DeviceUiState(
                    id = id,
                    name = account.deviceDisplayName(id),
                    isControlCenterDevice = id == account.selectedDeviceId()
                )
            },
            usage = usageOverride ?: usageFrom(scoreJson, waterEstimate),
            appLoginStatusText = loginState(hasAppToken, appStatus)
        )
    }

    /** 有 token 但还没检测过时，沿用"已完成"的说法，避免把未检测说成异常。 */
    private fun loginState(hasToken: Boolean, status: TokenValidity): String = when {
        !hasToken -> "未完成"
        status == TokenValidity.UNCHECKED || status == TokenValidity.CHECKING -> "已完成"
        else -> status.label
    }

    private fun isChecked(status: TokenValidity): Boolean =
        status != TokenValidity.UNCHECKED && status != TokenValidity.CHECKING

    /**
     * 账号副标题。两端都还没检测过时保持历史文案（"设备与积分登录均已完成"等），
     * 一旦账户页检测出结果，再换成"设备登录有效 · 积分登录需重新验证"这种精确说法。
     */
    private fun loginSummary(
        account: Account,
        appStatus: TokenValidity,
        alipayStatus: TokenValidity
    ): String {
        val hasApp = account.hasAppToken()
        val hasAlipay = account.hasToken()
        val checked = (hasApp && isChecked(appStatus)) || (hasAlipay && isChecked(alipayStatus))
        if (!checked) {
            return when {
                hasAlipay && hasApp -> "设备与积分登录均已完成"
                hasAlipay -> "积分登录已完成 · 设备登录未完成"
                hasApp -> "设备登录已完成 · 可补充积分登录"
                else -> "两项登录均未完成"
            }
        }
        return "设备登录" + loginState(hasApp, appStatus) +
            " · 积分登录" + loginState(hasAlipay, alipayStatus)
    }

    private fun usageFrom(
        scoreJson: JSONObject?,
        estimate: WaterEstimate = WaterEstimate()
    ): WaterUsageUiState {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) {
            return WaterUsageUiState(showWaterEstimate = estimate.visible)
        }
        val records = scoreJson.optJSONArray("data")
            ?: return WaterUsageUiState(showWaterEstimate = estimate.visible)
        val now = Calendar.getInstance()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var todaySpend = 0
        var monthSpend = 0
        var yearSpend = 0
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val time = record.optLong("ctime", 0L)
            if (time <= 0L) continue
            val data = record.optJSONObject("data")
            val amount = readSpentScore(record, data)
            if (amount <= 0 || !isConsumption(record, data)) continue
            if (time >= today) todaySpend += amount
            val recordDate = Calendar.getInstance().apply { timeInMillis = time }
            if (recordDate.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
                yearSpend += amount
                if (recordDate.get(Calendar.MONTH) == now.get(Calendar.MONTH)) monthSpend += amount
            }
        }
        return WaterUsageUiState(
            todayCostText = moneyText(todaySpend),
            monthCostText = moneyText(monthSpend),
            yearCostText = moneyText(yearSpend),
            todayWaterText = estimate.waterText(todaySpend),
            monthWaterText = estimate.waterText(monthSpend),
            yearWaterText = estimate.waterText(yearSpend),
            showWaterEstimate = estimate.visible
        )
    }

    private fun readSpentScore(record: JSONObject, data: JSONObject?): Int {
        val keys = arrayOf("spend", "score", "changeScore", "change_score", "amount", "value", "num", "points")
        for (key in keys) if (record.has(key)) return kotlin.math.abs(record.optInt(key, 0))
        if (data != null) for (key in keys) if (data.has(key)) return kotlin.math.abs(data.optInt(key, 0))
        return 0
    }

    private fun isConsumption(record: JSONObject, data: JSONObject?): Boolean {
        return record.optInt("type", data?.optInt("type", Int.MIN_VALUE) ?: Int.MIN_VALUE) == 107
    }

    private fun moneyText(score: Int): String = "¥${String.format(Locale.CHINA, "%.2f", score / 1000.0)}"

    fun accountsFrom(
        accounts: List<Account>,
        currentPhone: String?,
        loginStatus: (Account, Boolean) -> TokenValidity = { _, _ -> TokenValidity.UNCHECKED }
    ): List<DashboardAccountUiState> =
        accounts.map { account ->
            val summary = loginSummary(account, loginStatus(account, true), loginStatus(account, false))
            DashboardAccountUiState(
                phone = account.phone.orEmpty(),
                title = account.name?.takeIf { it.isNotBlank() } ?: account.phone?.takeIf { it.isNotBlank() } ?: "未命名账号",
                subtitle = summary,
                tokenSummary = summary,
                deviceSummary = if (account.hasDevices()) "${account.rememberedDevices().size} 台设备" else "暂无设备",
                isCurrent = account.phone == currentPhone
            )
        }
}
