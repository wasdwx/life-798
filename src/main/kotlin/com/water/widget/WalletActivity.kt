package com.water.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alipay.sdk.app.PayTask
import com.water.widget.ui.WaterTheme
import java.util.Locale

class WalletActivity : ComponentActivity() {
    private var state = WalletUiState()
    private var hasLoaded = false
    private var lastAccountKey = ""
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        render()
    }

    override fun onResume() {
        super.onResume()
        val account = AccountStore.getCurrent(this)
        val accountKey = "${account?.phone.orEmpty()}\u0000${account?.appToken.orEmpty()}"
        if (!hasLoaded || accountKey != lastAccountKey) loadWallets()
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun loadWallets(messageAfterRefresh: String? = null) {
        hasLoaded = true
        val account = AccountStore.getCurrent(this)
        val appToken = account?.appToken.orEmpty()
        lastAccountKey = "${account?.phone.orEmpty()}\u0000$appToken"
        if (appToken.isBlank()) {
            state = WalletUiState(
                accountName = account?.displayName().orEmpty(),
                missingAppToken = true
            )
            render()
            return
        }

        state = state.copy(
            accountName = account.displayName(),
            missingAppToken = false,
            loading = true,
            errorMessage = null,
            statusMessage = null
        )
        render()
        IlifeApi.walletOwnerWithToken(appToken) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    showError("加载钱包失败：${error ?: "网络错误"}")
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
                            wallets = wallets,
                            selectedEndpointId = null,
                            selectedOwnerId = null,
                            products = emptyList(),
                            selectedProductId = null,
                            statusMessage = messageAfterRefresh,
                            errorMessage = null
                        )
                        render()
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
                        loadProducts(appToken, selectedWallet)
                    }
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "钱包响应格式错误")
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
        state = state.copy(
            selectedEndpointId = wallet.endpointId,
            selectedOwnerId = wallet.ownerId,
            products = emptyList(),
            selectedProductId = null,
            loading = true,
            statusMessage = null,
            errorMessage = null
        )
        render()
        loadProducts(appToken, wallet)
    }

    private fun loadProducts(appToken: String, wallet: RechargeWallet) {
        IlifeApi.rechargeProductsWithToken(appToken, wallet.endpointId) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    showError("加载充值金额失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                try {
                    state = state.copy(
                        loading = false,
                        products = WalletResponseParser.parseProducts(response),
                        errorMessage = null
                    )
                    render()
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "充值产品响应格式错误")
                }
            }
        }
    }

    private fun startRecharge() {
        val account = AccountStore.getCurrent(this) ?: return
        val appToken = account.appToken.orEmpty()
        val wallet = state.wallets.firstOrNull {
            it.endpointId == state.selectedEndpointId && it.ownerId == state.selectedOwnerId
        } ?: return
        val product = state.products.firstOrNull { it.id == state.selectedProductId } ?: return
        state = state.copy(loading = true, statusMessage = "正在创建充值订单…", errorMessage = null)
        render()
        IlifeApi.createRechargeOrderWithToken(
            appToken,
            wallet.endpointId,
            wallet.ownerId,
            product.id
        ) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    showError("创建充值订单失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                try {
                    requestAlipay(appToken, WalletResponseParser.parseOrderId(response))
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "创建充值订单失败")
                }
            }
        }
    }

    private fun requestAlipay(appToken: String, orderId: String) {
        state = state.copy(statusMessage = "正在打开支付宝…")
        render()
        IlifeApi.prepayAlipayWithToken(appToken, orderId) { response, error ->
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                if (response == null) {
                    showError("发起支付宝支付失败：${error ?: "网络错误"}")
                    return@runOnUiThread
                }
                try {
                    launchAlipay(WalletResponseParser.parsePaymentString(response))
                } catch (e: IllegalArgumentException) {
                    showError(e.message ?: "发起支付宝支付失败")
                }
            }
        }
    }

    private fun launchAlipay(paymentString: String) {
        Thread {
            if (!canContinue()) return@Thread
            val result = try {
                AlipayResultParser.parse(PayTask(this).payV2(paymentString, true))
            } catch (e: Exception) {
                AlipayResult(AlipayResultKind.FAILED, "支付失败：${e.message ?: "支付宝调用异常"}")
            }
            runOnUiThread {
                if (!canContinue()) return@runOnUiThread
                when (result.kind) {
                    AlipayResultKind.SUCCESS -> loadWallets("充值成功，余额已刷新")
                    AlipayResultKind.PROCESSING,
                    AlipayResultKind.CANCELLED -> {
                        state = state.copy(
                            loading = false,
                            statusMessage = result.message,
                            errorMessage = null
                        )
                        render()
                    }
                    AlipayResultKind.FAILED -> showError(result.message)
                }
            }
        }.start()
    }

    private fun showError(message: String) {
        state = state.copy(loading = false, statusMessage = null, errorMessage = message)
        render()
    }

    private fun canContinue(): Boolean = !destroyed && !isFinishing

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
                        render()
                    },
                    onRequestRecharge = {
                        state = state.copy(showRechargeConfirmation = true)
                        render()
                    },
                    onDismissRecharge = {
                        state = state.copy(showRechargeConfirmation = false)
                        render()
                    },
                    onConfirmRecharge = {
                        state = state.copy(showRechargeConfirmation = false)
                        render()
                        startRecharge()
                    }
                )
            }
        }
    }
}

private data class WalletUiState(
    val accountName: String = "",
    val missingAppToken: Boolean = false,
    val loading: Boolean = false,
    val wallets: List<RechargeWallet> = emptyList(),
    val selectedEndpointId: String? = null,
    val selectedOwnerId: String? = null,
    val products: List<RechargeProduct> = emptyList(),
    val selectedProductId: String? = null,
    val showRechargeConfirmation: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private fun Account.displayName(): String = name?.takeIf { it.isNotBlank() }
    ?: phone?.takeIf { it.isNotBlank() }
    ?: "当前账户"

@Composable
private fun WalletScreen(
    state: WalletUiState,
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onRefresh: () -> Unit,
    onSelectWallet: (RechargeWallet) -> Unit,
    onSelectProduct: (RechargeProduct) -> Unit,
    onRequestRecharge: () -> Unit,
    onDismissRecharge: () -> Unit,
    onConfirmRecharge: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
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

        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        state.errorMessage?.let { message ->
            item { MessageCard(message, error = true) }
        }
        state.statusMessage?.let { message ->
            item { MessageCard(message, error = false) }
        }

        if (state.missingAppToken) {
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
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
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("选择钱包", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
                }
            }

            if (!state.loading && state.wallets.isEmpty()) {
                item { Text("暂无可充值钱包", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            items(state.wallets) { wallet ->
                WalletCard(
                    wallet = wallet,
                    selected = wallet.endpointId == state.selectedEndpointId &&
                        wallet.ownerId == state.selectedOwnerId,
                    enabled = !state.loading,
                    onClick = { onSelectWallet(wallet) }
                )
            }

            if (state.selectedEndpointId != null) {
                item {
                    Text("选择充值金额", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (!state.loading && state.products.isEmpty()) {
                    item { Text("该钱包暂无充值产品", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(state.products) { product ->
                    ProductCard(
                        product = product,
                        selected = product.id == state.selectedProductId,
                        enabled = !state.loading,
                        onClick = { onSelectProduct(product) }
                    )
                }
            }

            val selectedProduct = state.products.firstOrNull { it.id == state.selectedProductId }
            item {
                Button(
                    onClick = onRequestRecharge,
                    enabled = !state.loading && selectedProduct != null,
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
                    Button(onClick = onConfirmRecharge) { Text("确认并前往支付宝") }
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
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
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
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
