package com.water.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.water.widget.ui.ScoreDashboardScreen
import com.water.widget.ui.ScoreUiState
import com.water.widget.ui.ScoreUiStateFactory
import com.water.widget.ui.WaterBillLogParser
import com.water.widget.ui.WaterBillLogUiState
import com.water.widget.ui.WaterTheme

/**
 * 积分与消费记录：按账号展示当前积分、积分流水与接水消费账单。
 */
class ScoreActivity : ComponentActivity() {
    private var states by mutableStateOf<List<ScoreUiState>>(emptyList())
    private var accounts: List<Account> = emptyList()
    private var refreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        render()
        refreshScores()
    }

    private fun refreshScores() {
        val generation = ++refreshGeneration
        accounts = AccountStore.list(this).filter { it.hasToken() || it.hasAppToken() }
        states = accounts.map { ScoreUiStateFactory.loading(it).copy(billLoading = it.hasAppToken()) }

        accounts.forEachIndexed { index, account ->
            loadAccountScore(generation, index, account)
            loadBills(generation, index, account)
        }
    }

    private fun loadAccountScore(generation: Int, index: Int, account: Account) {
        val token = account.token?.takeIf { it.isNotBlank() } ?: account.appToken
        if (token.isNullOrBlank()) {
            updateScore(generation, index, ScoreUiStateFactory.from(account, null, null, "缺少可用 Token"))
            return
        }

        val platform = TaskProtocol.preferredPlatform(account)
        IlifeApi.missionLstWithToken(token, platform) { missionJson, missionErr ->
            IlifeApi.scoreLstWithToken(token) { scoreJson, scoreErr ->
                runOnUiThread {
                    if (generation != refreshGeneration || isDestroyed) return@runOnUiThread
                    val error = missionErr ?: scoreErr
                    val state = ScoreUiStateFactory.from(account, missionJson, scoreJson, error)
                    if (state.isReady && state.validScore >= 0 && state.validScore != account.score) {
                        // 顺手缓存，供桌面小部件离线展示（AccountStore 落盘时会通知小部件刷新）
                        AccountStore.updateScore(this, account.phone, token, state.validScore)
                    }
                    if (missionJson?.optInt("code", -999) == -99) {
                        AccountStore.invalidateToken(this, account.phone, token, platform == TaskPlatform.APP)
                    }
                    updateScore(generation, index, state)
                }
            }
        }
    }

    /** 账单只能用设备登录信息查询。 */
    private fun loadBills(generation: Int, index: Int, account: Account) {
        val appToken = account.appToken
        if (appToken.isNullOrBlank()) {
            update(generation, index) { it.copy(billMessage = "需要设备登录信息才能查看接水记录") }
            return
        }
        IlifeApi.billListWithToken(appToken) { json, _ ->
            val result = runCatching { WaterBillLogParser.list(json) }
            runOnUiThread {
                update(generation, index) { old ->
                    result.fold(
                        { bills ->
                            old.copy(
                                bills = bills,
                                billLoading = false,
                                billMessage = if (bills.isEmpty()) "最近账单中暂无已完成的接水记录"
                                else "来自最近 20 笔账单，不含充值、兑换"
                            )
                        },
                        { e ->
                            old.copy(
                                bills = emptyList(),
                                billLoading = false,
                                billMessage = e.message ?: "接水记录获取失败，请下拉重试"
                            )
                        }
                    )
                }
            }
        }
    }

    private fun loadBillPayment(index: Int, billId: String) {
        val generation = refreshGeneration
        val appToken = accounts.getOrNull(index)?.appToken?.takeIf { it.isNotBlank() } ?: return
        updateBill(generation, index, billId) { it.copy(loading = true, error = null) }
        IlifeApi.billViewFullWithToken(appToken, billId) { json, _ ->
            val result = runCatching { WaterBillLogParser.payment(json, billId) }
            runOnUiThread {
                updateBill(generation, index, billId) { bill ->
                    result.fold(
                        { bill.copy(payment = it, loading = false, error = null) },
                        { bill.copy(loading = false, error = it.message ?: "支付明细获取失败，请重试") }
                    )
                }
            }
        }
    }

    /** 积分那一路整份替换，但保留另一路已加载的账单。 */
    private fun updateScore(generation: Int, index: Int, state: ScoreUiState) =
        update(generation, index) { old ->
            state.copy(bills = old.bills, billLoading = old.billLoading, billMessage = old.billMessage)
        }

    private fun updateBill(
        generation: Int,
        index: Int,
        billId: String,
        change: (WaterBillLogUiState) -> WaterBillLogUiState
    ) = update(generation, index) { state ->
        state.copy(bills = state.bills.map { if (it.id == billId) change(it) else it })
    }

    private fun update(generation: Int, index: Int, change: (ScoreUiState) -> ScoreUiState) {
        if (generation != refreshGeneration) return
        states = states.toMutableList().also { list ->
            if (index in list.indices) list[index] = change(list[index])
        }
    }

    private fun render() {
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                val refreshing = states.any {
                    (!it.isReady && it.message == "正在刷新积分...") || it.billLoading
                }
                ScoreDashboardScreen(
                    states = states,
                    refreshing = refreshing,
                    onRefresh = ::refreshScores,
                    onLoadBillPayment = ::loadBillPayment
                )
            }
        }
    }

    override fun onDestroy() {
        refreshGeneration++
        super.onDestroy()
    }
}
