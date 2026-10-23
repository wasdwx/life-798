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

/** 账单接口每页固定 20 条：返回满一页就认为可能还有下一页。 */
private const val BILL_PAGE_SIZE = 20

/**
 * 积分与消费记录：按账号展示当前积分、积分流水与接水消费账单。
 * 接水账单按页拉取，滚动到底部再取下一页。
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
        states = accounts.map { account ->
            if (account.hasAppToken()) {
                ScoreUiStateFactory.loading(account).copy(billLoading = true)
            } else {
                ScoreUiStateFactory.loading(account).copy(billMessage = "需要设备登录信息才能查看接水记录")
            }
        }

        accounts.forEachIndexed { index, account ->
            loadAccountScore(generation, index, account)
            if (account.hasAppToken()) loadBillPage(index, generation, 0)
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
                    // 登录态失效（业务码 -99）不再直接清 token：只提示，并把结果记进共享登录状态。
                    val loginError = TokenValidity.expiredMessage(missionJson, !account.hasToken())
                        ?: TokenValidity.expiredMessage(scoreJson, !account.hasToken())
                    val error = loginError ?: missionErr ?: scoreErr
                    val state = ScoreUiStateFactory.from(account, missionJson, scoreJson, error)
                    if (state.isReady && state.validScore >= 0 && state.validScore != account.score) {
                        // 顺手缓存，供桌面小部件离线展示（AccountStore 落盘时会通知小部件刷新）
                        AccountStore.updateScore(this, account.phone, token, state.validScore)
                    }
                    val validity = TokenValidity.from(missionJson ?: scoreJson)
                    if (validity == TokenValidity.VALID || validity == TokenValidity.EXPIRED) {
                        LoginStatusStore.record(account, !account.hasToken(), validity)
                    }
                    updateScore(generation, index, state)
                }
            }
        }
    }

    /** 账单只能用设备登录信息查询；按页拉取，避免一次把全部账单塞进内存。 */
    private fun loadBillPage(index: Int, generation: Int, page: Int) {
        val appToken = accounts.getOrNull(index)?.appToken?.takeIf { it.isNotBlank() } ?: return
        update(generation, index) { it.copy(billLoading = true) }
        IlifeApi.billListWithToken(appToken, page) { json, err ->
            val result = runCatching {
                TokenValidity.expiredMessage(json, true)?.let { throw IllegalStateException(it) }
                if (err != null) throw IllegalStateException("接水记录获取失败，请重试")
                WaterBillLogParser.list(json)
            }
            val hasMore = (json?.optJSONArray("data")?.length() ?: 0) >= BILL_PAGE_SIZE
            runOnUiThread {
                update(generation, index) { old ->
                    result.fold(
                        { records ->
                            val bills = (old.bills + records).distinctBy { it.id }
                            old.copy(
                                bills = bills,
                                billLoading = false,
                                billPage = page,
                                hasMoreBills = hasMore,
                                billMessage = when {
                                    bills.isEmpty() && !hasMore -> "暂无已完成的接水记录"
                                    bills.isEmpty() -> "已读取的账单中暂无接水消费，可继续加载"
                                    hasMore -> "已加载 ${page + 1} 页账单 · 仅显示接水消费"
                                    else -> "已加载全部可查询账单 · 仅显示接水消费"
                                }
                            )
                        },
                        { e ->
                            old.copy(
                                billLoading = false,
                                billMessage = e.message ?: "接水记录获取失败，请下拉重试"
                            )
                        }
                    )
                }
            }
        }
    }

    private fun loadMoreBills(index: Int) {
        val current = states.getOrNull(index) ?: return
        if (current.billLoading || !current.hasMoreBills) return
        loadBillPage(index, refreshGeneration, current.billPage + 1)
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
            state.copy(
                bills = old.bills,
                billLoading = old.billLoading,
                billMessage = old.billMessage,
                billPage = old.billPage,
                hasMoreBills = old.hasMoreBills
            )
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
                    onLoadBillPayment = ::loadBillPayment,
                    onLoadMoreBills = ::loadMoreBills
                )
            }
        }
    }

    override fun onDestroy() {
        refreshGeneration++
        super.onDestroy()
    }
}
