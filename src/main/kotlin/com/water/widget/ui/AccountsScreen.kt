package com.water.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.water.widget.TokenValidity

data class AccountCardState(
    val phone: String,
    val title: String,
    val current: Boolean,
    val alipayToken: String,
    val appToken: String,
    val deviceSummary: String,
    val alipayStatus: TokenValidity = TokenValidity.UNCHECKED,
    val appStatus: TokenValidity = TokenValidity.UNCHECKED
)

@Composable
fun AccountsScreen(
    accounts: List<AccountCardState>,
    onSmsLogin: () -> Unit,
    onAddToken: () -> Unit,
    onSetCurrent: (String) -> Unit,
    onSetAppToken: (String) -> Unit,
    onRevealToken: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onCheckLogin: (String) -> Unit,
    onRelogin: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { AccountsHero(accounts.size, onBack) }
        item {
            Card(
                shape = WaterShapeTokens.card,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(if (accounts.isEmpty()) 18.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (accounts.isEmpty()) {
                        Text("添加账户", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            "设备登录用于使用设备，积分登录可按需补充。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Button(onClick = onSmsLogin, modifier = Modifier.fillMaxWidth()) { Text("短信登录 / 补充登录") }
                        FilledTonalButton(onClick = onAddToken, modifier = Modifier.fillMaxWidth()) { Text("手动添加登录信息") }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onSmsLogin, modifier = Modifier.weight(1f)) { Text("短信登录") }
                            FilledTonalButton(onClick = onAddToken, modifier = Modifier.weight(1f)) { Text("手动添加") }
                        }
                    }
                }
            }
        }
        if (accounts.isEmpty()) {
            item { EmptyAccountsCard() }
        } else {
            item {
                Text(
                    "已保存账户",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            items(accounts, key = { it.phone }) { account ->
                AccountCard(account, onSetCurrent, onSetAppToken, onRevealToken, onDelete, onCheckLogin, onRelogin)
            }
        }
    }
}

@Composable
private fun AccountsHero(accountCount: Int, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("账户管理", modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            if (accountCount == 0) "还没有保存账号，从下方开始添加。" else "已保存 $accountCount 个账号，可分别管理登录信息。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyAccountsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = WaterShapeTokens.card,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("暂无账户", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "可通过短信登录或手动填写登录信息来添加。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AccountCard(
    account: AccountCardState,
    onSetCurrent: (String) -> Unit,
    onSetAppToken: (String) -> Unit,
    onRevealToken: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onCheckLogin: (String) -> Unit,
    onRelogin: (String, Boolean) -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }
    var showManualActions by remember { mutableStateOf(false) }
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(
            containerColor = if (account.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account.title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(account.phone, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = WaterShapeTokens.compact,
                    color = if (account.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        if (account.current) "当前账户" else "备用账户",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (account.current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AccountInfoRow("用水登录", if (account.appToken.isNotBlank()) account.appStatus.label else "未登录")
            AccountInfoRow("积分登录", if (account.alipayToken.isNotBlank()) account.alipayStatus.label else "未登录")
            AccountInfoRow("设备", account.deviceSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onSetCurrent(account.phone) }, enabled = !account.current, modifier = Modifier.weight(1f)) {
                    Text(if (account.current) "正在使用" else "设为当前")
                }
                FilledTonalButton(onClick = { showManualActions = false; showDetails = true }, modifier = Modifier.weight(1f)) {
                    Text("账号详情")
                }
            }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            shape = WaterShapeTokens.prominent,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("账号详情", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${account.title} · ${account.phone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = WaterShapeTokens.compact, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccountInfoRow("用水登录", if (account.appToken.isNotBlank()) account.appStatus.label else "未登录")
                            Text("用于接水、钱包和 App 积分任务", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showDetails = false; onRelogin(account.phone, true) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (account.appToken.isBlank()) "登录用水账号" else "重新登录用水账号")
                            }
                        }
                    }
                    Surface(shape = WaterShapeTokens.compact, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AccountInfoRow("积分登录", if (account.alipayToken.isNotBlank()) account.alipayStatus.label else "未登录")
                            Text("可选，用于领取支付宝积分任务", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { showDetails = false; onRelogin(account.phone, false) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (account.alipayToken.isBlank()) "登录积分账号" else "重新登录积分账号")
                            }
                        }
                    }
                    TextButton(
                        enabled = account.alipayStatus != TokenValidity.CHECKING && account.appStatus != TokenValidity.CHECKING,
                        onClick = { onCheckLogin(account.phone) }, modifier = Modifier.fillMaxWidth()
                    ) { Text("检查登录状态") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { showManualActions = !showManualActions }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showManualActions) "收起手动操作" else "复制或手动填写登录信息")
                    }
                    if (showManualActions) {
                        Surface(shape = WaterShapeTokens.compact, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                                TextButton(enabled = account.appToken.isNotBlank(), onClick = { showDetails = false; onRevealToken(account.phone, true) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("复制用水登录信息")
                                }
                                TextButton(enabled = account.alipayToken.isNotBlank(), onClick = { showDetails = false; onRevealToken(account.phone, false) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("复制积分登录信息")
                                }
                                TextButton(onClick = { showDetails = false; onSetAppToken(account.phone) }, modifier = Modifier.fillMaxWidth()) { Text("手动填写用水登录") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("完成") } },
            dismissButton = {
                TextButton(onClick = { showDetails = false; onDelete(account.phone) }) {
                    Text("删除账号", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun AccountInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TextEntryDialog(
    title: String,
    message: String,
    fields: List<Pair<String, String>>,
    multiline: Boolean = false,
    sensitiveFields: Set<Int> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var values by remember(fields) { mutableStateOf(fields.map { it.second }) }
    BasicAlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss
    ) {
        Surface(shape = WaterShapeTokens.prominent, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp)) {
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    fields.forEachIndexed { index, field ->
                        OutlinedTextField(
                            value = values[index],
                            onValueChange = { new -> values = values.toMutableList().also { it[index] = new } },
                            label = { Text(field.first) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = if (multiline) 5 else 1,
                            singleLine = !multiline,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = if (field.first == "手机号") androidx.compose.ui.text.input.KeyboardType.Phone
                                    else if (index in sensitiveFields) androidx.compose.ui.text.input.KeyboardType.Password
                                    else androidx.compose.ui.text.input.KeyboardType.Text,
                                imeAction = if (index == fields.lastIndex) androidx.compose.ui.text.input.ImeAction.Done
                                    else androidx.compose.ui.text.input.ImeAction.Next
                            ),
                            visualTransformation = if (index in sensitiveFields) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(values) }) { Text("确认") }
                }
            }
        }
    }
}

fun maskToken(token: String): String = when {
    token.isBlank() -> "未登录"
    token.length <= 8 -> "••••••••"
    else -> token.take(4) + "••••••••" + token.takeLast(4)
}
