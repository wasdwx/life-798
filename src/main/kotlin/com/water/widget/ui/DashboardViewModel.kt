package com.water.widget.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.water.widget.Account
import com.water.widget.AccountStore
import com.water.widget.LoginStatusStore
import com.water.widget.TaskRunRepository
import com.water.widget.TokenValidity
import com.water.widget.WaterUsageSettings
import com.water.widget.WaterControl
import com.water.widget.WaterControlSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val summary: DashboardSummaryUiState = DashboardUiStateFactory.from(null, 0),
    val accounts: List<DashboardAccountUiState> = emptyList(),
    val tasks: TaskUiState = TaskUiStateFactory.from(emptyList(), false, 0, emptyList()),
    val homeRefreshing: Boolean = false,
    val deviceSyncing: Boolean = false,
    val waterSession: WaterControlSession? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var currentScore: Int? = null
    private var currentWalletBalance: Double? = null
    private var currentScoreLogs: JSONObject? = null
    /** 当前已加载账号的指纹（手机号 + 两端 token），用来判断是否需要清空缓存。 */
    private var currentAccountKey: List<String?> = emptyList()
    /** 钱包刷新失败时保留旧余额并附一句说明，而不是把余额清成"--"。 */
    private var walletMessage: String? = null
    private var historySyncNote = "仅统计已获取的记录"
    private var homeRefreshing = false
    private var deviceSyncing = false

    init {
        reloadAccounts()
        viewModelScope.launch {
            TaskRunRepository.state.collect {
                publish()
            }
        }
        viewModelScope.launch { WaterControl.state.collect { publish() } }
        // 用水量估算比例/开关变化后，首页立即按新比例重算，不必等下一次刷新。
        viewModelScope.launch { com.water.widget.WaterUsageSettings.changes.collect { publish() } }
        // 账户页重新检测登录状态后，首页的登录状态文案随之更新。
        viewModelScope.launch { LoginStatusStore.changes.collect { publish() } }
    }

    /** @return 当前账号是否发生了变化（变化时调用方应重新拉取积分/钱包）。 */
    fun reloadAccounts(resetScore: Boolean = false): Boolean {
        val account = AccountStore.getCurrent(getApplication<Application>())
        val key = listOf(account?.phone, account?.token, account?.appToken)
        val changed = key != currentAccountKey
        currentAccountKey = key
        if (resetScore || changed) {
            currentScore = null
            currentWalletBalance = null
            currentScoreLogs = null
            walletMessage = null
        }
        publish()
        return changed
    }

    fun setCurrentScoreData(
        score: Int? = currentScore,
        logs: JSONObject? = currentScoreLogs,
        walletBalance: Double? = currentWalletBalance
    ) {
        currentWalletBalance = walletBalance
        currentScore = score
        currentScoreLogs = logs
        publish()
    }

    /** 钱包单独刷新成功/失败都走这里：成功更新余额，失败保留旧余额并给出说明。 */
    fun setCurrentWalletData(balance: Double?, message: String?) {
        if (balance != null) currentWalletBalance = balance
        walletMessage = if (message != null && currentWalletBalance != null) {
            "$message（显示上次余额）"
        } else {
            message
        }
        publish()
    }

    fun setHistorySyncNote(note: String) {
        historySyncNote = note
        publish()
    }

    fun setDeviceSyncing(syncing: Boolean) {
        deviceSyncing = syncing
        publish()
    }

    fun setHomeRefreshing(refreshing: Boolean) {
        homeRefreshing = refreshing
        publish()
    }

    fun selectAccount(phone: String): Account? {
        val storeContext = getApplication<Application>()
        val account = AccountStore.get(storeContext, phone) ?: return null
        AccountStore.setCurrent(storeContext, account.phone)
        reloadAccounts(resetScore = true)
        return account
    }

    private fun publish() {
        val storeContext = getApplication<Application>()
        val accounts = AccountStore.list(storeContext)
        val current = AccountStore.getCurrent(storeContext)
        val taskRun = TaskRunRepository.state.value
        val waterEstimate = WaterUsageSettings.read(storeContext)
        val usage = current?.let { account ->
            val accountKey = account.phone?.takeIf(String::isNotBlank)
                ?: account.uid?.takeIf(String::isNotBlank)
                ?: return@let WaterUsageUiState(showWaterEstimate = waterEstimate.visible)
            UsageHistoryStore.mergeAndRead(storeContext, accountKey, currentScoreLogs)
        }
        _uiState.update {
            DashboardUiState(
                summary = DashboardUiStateFactory.from(
                    current,
                    accounts.size,
                    currentScore,
                    currentScoreLogs,
                    usageOverride = usage?.copy(syncNote = historySyncNote),
                    walletBalance = currentWalletBalance,
                    waterEstimate = waterEstimate,
                    walletMessage = walletMessage,
                    appStatus = current?.let { account -> LoginStatusStore.status(account, true) }
                        ?: TokenValidity.UNCHECKED,
                    alipayStatus = current?.let { account -> LoginStatusStore.status(account, false) }
                        ?: TokenValidity.UNCHECKED
                ),
                accounts = DashboardUiStateFactory.accountsFrom(
                    accounts,
                    current?.phone,
                    LoginStatusStore::status
                ),
                tasks = TaskUiStateFactory.from(
                    accounts,
                    taskRun.running,
                    taskRun.totalGained,
                    taskRun.logs,
                    taskRun.lanes,
                    taskRun.result
                ),
                homeRefreshing = homeRefreshing,
                deviceSyncing = deviceSyncing,
                waterSession = WaterControl.state.value?.takeIf {
                    it.accountKey == (current?.phone?.takeIf(String::isNotBlank) ?: current?.uid)
                }
            )
        }
    }

}
