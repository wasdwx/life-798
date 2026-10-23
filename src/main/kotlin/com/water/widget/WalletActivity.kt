package com.water.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alipay.sdk.app.PayTask
import com.water.widget.ui.PullRefreshOffsetContent
import com.water.widget.ui.WaterPullRefresh
import com.water.widget.ui.WaterTheme
import java.security.MessageDigest
import java.util.Locale

class WalletActivity : ComponentActivity() {
    private var state by mutableStateOf(WalletUiState())
    private var scoreGeneration = 0
    private var walletGeneration = 0
    private var hasLoaded = false
    private var lastAccountKey = ""
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程被回收后重建：同一账号保留待核验的充值订单，避免用户重复付款。
        val accountKey = walletAccountKey(AccountStore.getCurrent(this))
        if (savedInstanceState?.getString("rechargeAccountKey") == accountKey) {
            lastAccountKey = accountKey
            state = state.copy(pendingRechargeOrderId = savedInstanceState.getString("rechargeOrderId"))
        }
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("rechargeAccountKey", lastAccountKey)
        outState.putString("rechargeOrderId", state.pendingRechargeOrderId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val account = AccountStore.getCurrent(this)
        if (!hasLoaded || walletAccountKey(account) != lastAccountKey) loadWallets()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun loadWallets(messageAfterRefresh: String? = null) {
        hasLoaded = true
        val account = AccountStore.getCurrent(this)
        val appToken = account?.appToken.orEmpty()
        val accountKey = walletAccountKey(account)
        val accountChanged = accountKey != lastAccountKey
        lastAccountKey = accountKey
        val generation = ++walletGeneration
        if (appToken.isBlank()) {
            scoreGeneration++
            state = WalletUiState(
                accountName = account?.displayName().orEmpty(),
                missingAppToken = true
            )
            return
        }

        // 换账号时整页重置，避免把上一个账号的余额/订单带到新账号上。
        if (accountChanged) state = WalletUiState(accountName = account.displayName())

        state = state.copy(
            accountName = account.displayName(),
            missingAppToken = false,
            loading = true,
            refreshing = true,
            errorMessage = null,
            statusMessage = null
        )
        refreshAvailableScore(appToken)
        IlifeApi.walletOwnerWithToken(appToken) { response, error ->
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                // 登录态失效（业务码 -99）时直接提示重新验证，而不是当成普通网络错误。
                TokenValidity.expiredMessage(response, true)?.let { message ->
                    state = WalletUiState(
                        accountName = account.displayName(),
                        missingAppToken = true,
                        errorMessage = message,
                        pendingRechargeOrderId = state.pendingRechargeOrderId
                    )
                    return@runOnUiThread
                }
                if (response == null) {
                    showError(listOfNotNull(messageAfterRefresh, "加载钱包失败：${error ?: "网络错误"}").joinToString("；"))
                    return@runOnUiThread
                }
                try {
                    val wallets = WalletResponseParser.parseWallets(response)
                    val selectedWallet = wallets.firstOrNull {
                        it.endpointId == state.selectedEndpointId &&
                            it.ownerId == state.selectedOwnerId
                    } ?: wallets.firstOrNull()
                    if (selectedWallet == null) {
                        state = state.copy(
                            loading = false,
                            refreshing = false,
                            wallets = wallets,
                            selectedEndpointId = null,
                            selectedOwnerId = null,
                            products = emptyList(),
                            selectedProductId = null,
                            statusMessage = messageAfterRefresh,
                            errorMessage = null
                        )
                    } else {
                        state = state.copy(
                            wallets = wallets,
                            selectedEndpointId = selectedWallet.endpointId,
                            selectedOwnerId = selectedWallet.ownerId,
                            products = emptyList(),
                            selectedProductId = null,
                            loading = true,
                            statusMessage = messageAfterRefresh,
                            errorMessage = null
                        )
                        loadProducts(appToken, selectedWallet, generation, messageAfterRefresh)
                    }
                } catch (e: IllegalArgumentException) {
                    showError(listOfNotNull(messageAfterRefresh, e.message ?: "钱包响应格式错误").joinToString("；"))
                }
            }
        }
    }

    private fun selectWallet(wallet: RechargeWallet) {
        if (
            wallet.endpointId == state.selectedEndpointId &&
            wallet.ownerId == state.selectedOwnerId &&
            state.products.isNotEmpty()
        ) return
        val appToken = AccountStore.getCurrent(this)?.appToken.orEmpty()
        val generation = ++walletGeneration
        state = state.copy(
            selectedEndpointId = wallet.endpointId,
            selectedOwnerId = wallet.ownerId,
            products = emptyList(),
            selectedProductId = null,
            loading = true,
            refreshing = false,
            statusMessage = null,
            errorMessage = null
        )
        loadProducts(appToken, wallet, generation)
    }

    private fun loadProducts(
        appToken: String,
        wallet: RechargeWallet,
        generation: Int,
        resultMessage: String? = null
    ) {
        IlifeApi.rechargeProductsWithToken(appToken, wallet.endpointId) { response, error ->
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                if (response == null) {
                    showError(listOfNotNull(resultMessage, "加载充值金额失败：${error ?: "网络错误"}").joinToString("；"))
                    return@runOnUiThread
                }
                try {
                    state = state.copy(
                        loading = false,
                        refreshing = false,
                        products = WalletResponseParser.parseProducts(response),
                        errorMessage = null
                    )
                } catch (e: IllegalArgumentException) {
                    showError(listOfNotNull(resultMessage, e.message ?: "充值产品响应格式错误").joinToString("；"))
                }
            }
        }
    }

    private fun refreshAvailableScore(appToken: String) {
        val generation = ++scoreGeneration
        state = state.copy(availableScore = null, scoreLoading = true, scoreError = null)
        IlifeApi.accountScoreWithToken(appToken) { response, _ ->
            runOnUiThread {
                // 账号已切换（token 变了）时丢弃这次回包，避免串号。
                if (!canContinue() || generation != scoreGeneration ||
                    AccountStore.getCurrent(this)?.appToken != appToken
                ) return@runOnUiThread
                val score = try {
                    response?.let(ScoreExchangeParser::availableScore)
                } catch (_: IllegalArgumentException) {
                    null
                }
                state = state.copy(
                    availableScore = score,
                    scoreLoading = false,
                    scoreError = TokenValidity.expiredMessage(response, true)
                        ?: if (score == null) "可用积分加载失败，请下拉刷新后重试" else null
                )
            }
        }
    }

    private fun startExchange(quantity: Int) {
        val unitScore = state.exchangeScore ?: return
        state = state.copy(exchangeScore = null)
        if (state.loading || state.exchangeInProgress) return
        val score = ScoreExchangeParser.totalScore(unitScore, quantity, state.availableScore ?: 0)
        if (score == null) {
            state = state.copy(exchangeMessage = "份数无效或积分不足，请重新选择")
            return
        }
        val account = AccountStore.getCurrent(this) ?: return
        val appToken = account.appToken.orEmpty()
        if (walletAccountKey(account) != lastAccountKey) {
            loadWallets("账户已变化，请重新选择兑换钱包")
            return
        }
        val generation = walletGeneration
        val wallet = state.wallets.firstOrNull {
            it.endpointId == state.selectedEndpointId && it.ownerId == state.selectedOwnerId
        } ?: return
        state = state.copy(exchangeInProgress = true, exchangeMessage = "正在兑换并更新余额，请稍候…")
        IlifeApi.exchangeScoreWithToken(appToken, wallet.endpointId, score) { response, _ ->
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                if (response != null && response.has("code") && response.optInt("code", -1) != 0) {
                    refreshAfterExchange(appToken, "兑换未成功：${response.optString("msg", "请刷新后核对积分")}", generation)
                    return@runOnUiThread
                }
                val billId = try {
                    response?.let(ScoreExchangeParser::billId)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (billId == null) {
                    refreshAfterExchange(appToken, "兑换结果待确认，请先核对官方记录，勿重复兑换", generation)
                    return@runOnUiThread
                }
                IlifeApi.billViewFullWithToken(appToken, billId) { billResponse, _ ->
                    runOnUiThread {
                        if (!canContinue(generation)) return@runOnUiThread
                        val completed = try {
                            billResponse?.let { ScoreExchangeParser.isCompleted(it, billId) } == true
                        } catch (_: IllegalArgumentException) {
                            false
                        }
                        refreshAfterExchange(appToken,
                            if (completed) "兑换已完成，¥${(score / 1000.0).money()} 已兑换至「${wallet.name}」"
                            else "兑换结果待确认，请先核对官方记录，勿重复兑换",
                            generation
                        )
                    }
                }
            }
        }
    }

    /** 兑换只更新余额和积分，不重载充值产品，也不改变页面加载布局。 */
    private fun refreshAfterExchange(appToken: String, message: String, generation: Int) {
        IlifeApi.walletOwnerWithToken(appToken) { walletResponse, _ ->
            IlifeApi.accountScoreWithToken(appToken) { scoreResponse, _ ->
                runOnUiThread {
                    if (!canContinue(generation)) return@runOnUiThread
                    val wallets = try {
                        walletResponse?.let(WalletResponseParser::parseWallets)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    val score = try {
                        scoreResponse?.let(ScoreExchangeParser::availableScore)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    val refreshError = if (wallets == null || score == null) "；部分数据未刷新，请下拉刷新" else ""
                    state = state.copy(
                        wallets = wallets ?: state.wallets,
                        availableScore = score,
                        scoreError = if (score == null) "可用积分加载失败，请下拉刷新" else null,
                        exchangeInProgress = false,
                        exchangeMessage = message + refreshError
                    )
                }
            }
        }
    }

    private fun startRecharge() {
        // 有未核验的订单时禁止再下单，否则可能付两次钱。
        if (state.loading || state.exchangeInProgress || state.pendingRechargeOrderId != null) return
        val account = AccountStore.getCurrent(this) ?: return
        val appToken = account.appToken.orEmpty()
        if (walletAccountKey(account) != lastAccountKey) {
            loadWallets("账号已变化，请重新选择充值钱包")
            return
        }
        val wallet = state.wallets.firstOrNull {
            it.endpointId == state.selectedEndpointId && it.ownerId == state.selectedOwnerId
        } ?: return
        val product = state.products.firstOrNull { it.id == state.selectedProductId } ?: return
        val generation = walletGeneration
        state = state.copy(loading = true, refreshing = false, statusMessage = "正在创建充值订单…", errorMessage = null)
        IlifeApi.createRechargeOrderWithToken(
            appToken,
            wallet.endpointId,
            wallet.ownerId,
            product.id
        ) { response, error ->
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                if (response == null) {
                    showError("创建充值订单失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                try {
                    requestAlipay(appToken, WalletResponseParser.parseOrderId(response), generation)
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "创建充值订单失败")
                }
            }
        }
    }

    private fun requestAlipay(appToken: String, orderId: String, generation: Int) {
        state = state.copy(statusMessage = "正在打开支付宝…")
        IlifeApi.prepayAlipayWithToken(appToken, orderId) { response, error ->
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                if (response == null) {
                    showError("发起支付宝支付失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                try {
                    val paymentString = WalletResponseParser.parsePaymentString(response)
                    // 拉起支付宝前先记住订单号：进程被杀或用户切走后，仍能回来核对这一单。
                    state = state.copy(pendingRechargeOrderId = orderId)
                    launchAlipay(appToken, orderId, paymentString, generation)
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "发起支付宝支付失败")
                }
            }
        }
    }

    private fun launchAlipay(appToken: String, orderId: String, paymentString: String, generation: Int) {
        Thread {
            if (!canContinue()) return@Thread
            val result = try {
                AlipayResultParser.parse(PayTask(this).payV2(paymentString, true))
            } catch (e: Exception) {
                // 支付宝抛异常不代表没付成功，按"结果未知"处理并去服务端核对。
                AlipayResult(AlipayResultKind.PROCESSING, "未能确认支付结果，请查询充值订单")
            }
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                when (result.kind) {
                    AlipayResultKind.SUCCESS,
                    AlipayResultKind.PROCESSING -> checkRecharge(appToken, orderId, generation)
                    AlipayResultKind.CANCELLED -> {
                        state = state.copy(
                            loading = false,
                            refreshing = false,
                            pendingRechargeOrderId = null,
                            statusMessage = result.message,
                            errorMessage = null
                        )
                    }
                    AlipayResultKind.FAILED -> {
                        state = state.copy(pendingRechargeOrderId = null)
                        showError(result.message)
                    }
                }
            }
        }.start()
    }

    /** 用户手动查询上一次充值的结果（例如付完款后 App 被系统回收）。 */
    private fun checkRecharge() {
        if (state.loading || state.exchangeInProgress) return
        val orderId = state.pendingRechargeOrderId ?: return
        val account = AccountStore.getCurrent(this) ?: return
        if (walletAccountKey(account) != lastAccountKey) {
            loadWallets("账号已变化，请核对当前账号的充值记录")
            return
        }
        checkRecharge(account.appToken.orEmpty(), orderId, walletGeneration)
    }

    private fun checkRecharge(appToken: String, orderId: String, generation: Int) {
        state = state.copy(loading = true, statusMessage = "正在查询充值订单…", errorMessage = null)
        IlifeApi.billViewFullWithToken(appToken, orderId) { response, error ->
            runOnUiThread {
                if (!canContinue(generation)) return@runOnUiThread
                val status = try {
                    requireNotNull(response) { "查询充值订单失败：${error ?: "网络错误"}" }
                    WalletResponseParser.rechargeStatus(response, orderId)
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "查询充值订单失败，请稍后重试")
                    return@runOnUiThread
                }
                when (status) {
                    3 -> {
                        state = state.copy(pendingRechargeOrderId = null)
                        loadWallets("充值订单已付款")
                    }
                    4, 9 -> state = state.copy(
                        pendingRechargeOrderId = null,
                        loading = false,
                        statusMessage = if (status == 4) "充值订单付款失败" else "充值订单已取消"
                    )
                    else -> state = state.copy(
                        loading = false,
                        statusMessage = "充值订单尚未确认付款，请稍后查询；如已付款，请勿重复充值"
                    )
                }
            }
        }
    }

    private fun showError(message: String) {
        state = state.copy(loading = false, refreshing = false, statusMessage = null, errorMessage = message)
    }

    private fun canContinue(): Boolean = !destroyed && !isFinishing

    /** 回包时同时校验代数与当前账号，避免切换账号后旧请求把数据写到新账号上。 */
    private fun canContinue(generation: Int): Boolean {
        val account = AccountStore.getCurrent(this)
        return canContinue() && generation == walletGeneration &&
            lastAccountKey == walletAccountKey(account)
    }

    private fun render() {
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                WalletScreen(
                    state = state,
                    onBack = ::finish,
                    onOpenAccounts = { startActivity(Intent(this, AccountsActivity::class.java)) },
                    onRefresh = { loadWallets() },
                    onSelectWallet = ::selectWallet,
                    onSelectProduct = { product ->
                        state = state.copy(selectedProductId = product.id, statusMessage = null)
                    },
                    onRequestRecharge = {
                        state = state.copy(showRechargeConfirmation = true)
                    },
                    onDismissRecharge = {
                        state = state.copy(showRechargeConfirmation = false)
                    },
                    onRequestExchange = { score -> state = state.copy(exchangeScore = score) },
                    onDismissExchange = { state = state.copy(exchangeScore = null) },
                    onConfirmExchange = ::startExchange,
                    onConfirmRecharge = {
                        state = state.copy(showRechargeConfirmation = false)
                        startRecharge()
                    },
                    onCheckRecharge = ::checkRecharge
                )
            }
        }
    }
}

internal data class WalletUiState(
    val accountName: String = "",
    val missingAppToken: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val wallets: List<RechargeWallet> = emptyList(),
    val selectedEndpointId: String? = null,
    val selectedOwnerId: String? = null,
    val products: List<RechargeProduct> = emptyList(),
    val selectedProductId: String? = null,
    val availableScore: Int? = null,
    val scoreLoading: Boolean = false,
    val scoreError: String? = null,
    val exchangeScore: Int? = null,
    val exchangeInProgress: Boolean = false,
    val exchangeMessage: String = "选择档位后可调整兑换份数",
    val showRechargeConfirmation: Boolean = false,
    val pendingRechargeOrderId: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

/** 账号指纹：手机号 + 设备 token 的摘要，用于判断"当前页面加载的账号是否还是同一个"。 */
private fun walletAccountKey(account: Account?): String {
    val tokenHash = MessageDigest.getInstance("SHA-256")
        .digest(account?.appToken.orEmpty().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "${account?.phone.orEmpty()}\u0000$tokenHash"
}

private fun Account.displayName(): String = name?.takeIf { it.isNotBlank() }
    ?: phone?.takeIf { it.isNotBlank() }
    ?: "当前账户"

@Composable
internal fun WalletScreen(
    state: WalletUiState,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRefresh: () -> Unit,
    onSelectWallet: (RechargeWallet) -> Unit,
    onSelectProduct: (RechargeProduct) -> Unit,
    onRequestRecharge: () -> Unit,
    onDismissRecharge: () -> Unit,
    onConfirmRecharge: () -> Unit,
    onRequestExchange: (Int) -> Unit,
    onDismissExchange: () -> Unit,
    onConfirmExchange: (Int) -> Unit,
    onCheckRecharge: () -> Unit = {}
) {
    val indicatorTopOffset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        WaterPullRefresh(
            isRefreshing = state.refreshing,
            onRefresh = { if (!state.loading && !state.exchangeInProgress) onRefresh() },
            modifier = Modifier.fillMaxSize(),
            indicatorTopOffset = indicatorTopOffset
        ) { pullOffset ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 12.dp,
                    // 末项是「充值」按钮：targetSdk 35 强制 edge-to-edge，
                    // 底部必须让开系统导航栏，否则按钮被导航栏盖住、点不到。
                    bottom = 12.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                        Spacer(Modifier.width(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("钱包充值", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            if (state.accountName.isNotBlank()) {
                                Text(
                                    state.accountName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                if (state.loading && !state.refreshing) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }

                state.errorMessage?.let { message ->
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            MessageCard(message, error = true)
                        }
                    }
                }
                state.statusMessage?.let { message ->
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            MessageCard(message, error = false)
                        }
                    }
                }

                // 上一次充值还没确认结果时，给一个显式入口去服务端核对，避免用户重复下单。
                if (state.pendingRechargeOrderId != null) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            OutlinedButton(
                                onClick = onCheckRecharge,
                                enabled = !state.loading && !state.exchangeInProgress,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("查询本次充值结果") }
                        }
                    }
                }

                if (state.missingAppToken) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("需要设备登录", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(
                                        "钱包与充值使用设备登录信息，请先在账户管理中为当前账号完成设备登录。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(onClick = onOpenAccounts) { Text("前往账户管理") }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            Text("选择钱包", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (!state.loading && state.wallets.isEmpty()) {
                        item {
                            PullRefreshOffsetContent(pullOffset) {
                                Text("暂无可充值钱包", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    items(state.wallets) { wallet ->
                        PullRefreshOffsetContent(pullOffset) {
                            WalletCard(
                                wallet = wallet,
                                selected = wallet.endpointId == state.selectedEndpointId &&
                                    wallet.ownerId == state.selectedOwnerId,
                                enabled = !state.loading && !state.exchangeInProgress,
                                onClick = { onSelectWallet(wallet) }
                            )
                        }
                    }

                    if (state.selectedEndpointId != null) {
                        item {
                            PullRefreshOffsetContent(pullOffset) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("积分兑换", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (state.scoreLoading) "正在查询可用积分…"
                                            else state.availableScore?.let { "可用积分：$it" }
                                                ?: state.scoreError.orEmpty(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        state.wallets.firstOrNull {
                                            it.endpointId == state.selectedEndpointId && it.ownerId == state.selectedOwnerId
                                        }?.let { Text("兑换到：${it.name}") }
                                        ScoreExchangeParser.amounts.forEach { score ->
                                            Button(
                                                onClick = { onRequestExchange(score) },
                                                enabled = !state.loading && !state.exchangeInProgress && !state.scoreLoading &&
                                                    (state.availableScore ?: 0) >= score,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("$score 积分兑 ¥${(score / 1000.0).money()} / 份")
                                            }
                                        }
                                        Text(
                                            state.exchangeMessage,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (state.selectedEndpointId != null) {
                        item {
                            PullRefreshOffsetContent(pullOffset) {
                                Text("选择充值金额", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (!state.loading && state.products.isEmpty()) {
                            item {
                                PullRefreshOffsetContent(pullOffset) {
                                    Text("该钱包暂无充值产品", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(state.products) { product ->
                            PullRefreshOffsetContent(pullOffset) {
                                ProductCard(
                                    product = product,
                                    selected = product.id == state.selectedProductId,
                                    enabled = !state.loading && !state.exchangeInProgress,
                                    onClick = { onSelectProduct(product) }
                                )
                            }
                        }
                    }

                    val selectedProduct = state.products.firstOrNull { it.id == state.selectedProductId }
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            Button(
                                onClick = onRequestRecharge,
                                enabled = !state.loading && !state.exchangeInProgress && selectedProduct != null &&
                                    state.pendingRechargeOrderId == null,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 16.dp)
                            ) {
                                if (state.loading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        selectedProduct?.let { "充值  ¥${it.price.money()}" }
                                            ?: "请选择充值金额"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.exchangeScore?.let { unitScore ->
        val wallet = state.wallets.firstOrNull {
            it.endpointId == state.selectedEndpointId && it.ownerId == state.selectedOwnerId
        }
        if (wallet != null) {
            var quantityText by rememberSaveable(unitScore) { mutableStateOf("1") }
            val quantity = quantityText.toIntOrNull()
            val available = state.availableScore ?: 0
            val maxQuantity = available / unitScore
            val total = quantity?.let { ScoreExchangeParser.totalScore(unitScore, it, available) }
            AlertDialog(
                onDismissRequest = onDismissExchange,
                title = { Text("确认积分兑换") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("兑换到：${wallet.name}")
                        Text("每份 $unitScore 积分 = ¥${(unitScore / 1000.0).money()}")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { quantityText = ((quantity ?: 1) - 1).toString() },
                                enabled = quantity != null && quantity > 1
                            ) { Text("−") }
                            OutlinedTextField(
                                value = quantityText,
                                onValueChange = { quantityText = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("份数") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = total == null
                            )
                            TextButton(
                                onClick = { quantityText = ((quantity ?: 0) + 1).toString() },
                                enabled = quantity != null && quantity < maxQuantity
                            ) { Text("＋") }
                        }
                        Text("可用积分：$available，最多 $maxQuantity 份")
                        Text(
                            total?.let { "合计消耗 $it 积分，兑换 ¥${(it / 1000.0).money()}" }
                                ?: "请输入 1～$maxQuantity 之间的整数份数",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { quantity?.let(onConfirmExchange) },
                        enabled = total != null && !state.loading && !state.exchangeInProgress
                    ) { Text("确认兑换") }
                },
                dismissButton = { TextButton(onClick = onDismissExchange) { Text("取消") } }
            )
        }
    }

    if (state.showRechargeConfirmation) {
        val wallet = state.wallets.firstOrNull {
            it.endpointId == state.selectedEndpointId && it.ownerId == state.selectedOwnerId
        }
        val product = state.products.firstOrNull { it.id == state.selectedProductId }
        if (wallet != null && product != null) {
            AlertDialog(
                onDismissRequest = onDismissRecharge,
                title = { Text("确认充值") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("钱包：${wallet.name}")
                        Text("充值金额：¥${product.price.money()}", fontWeight = FontWeight.SemiBold)
                        Text("确认后将前往支付宝完成付款。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = onConfirmRecharge,
                        enabled = !state.loading && !state.exchangeInProgress &&
                            state.pendingRechargeOrderId == null
                    ) { Text("确认并前往支付宝") }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRecharge) { Text("取消") }
                }
            )
        }
    }
}

@Composable
private fun WalletCard(
    wallet: RechargeWallet,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(wallet.name, fontWeight = FontWeight.SemiBold)
                Text("余额 ¥${wallet.balance.money()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun ProductCard(
    product: RechargeProduct,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(product.displayName, fontWeight = FontWeight.SemiBold)
                product.displayDescription?.let { description ->
                    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("¥${product.price.money()}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (product.hasDiscount) {
                    Text(
                        "¥${product.originalPrice!!.money()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, error: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    ) {
        Text(message, modifier = Modifier.padding(14.dp))
    }
}

private fun Double.money(): String = String.format(Locale.CHINA, "%.2f", this)
