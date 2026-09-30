package com.water.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val INITIAL_LOG_COUNT = 8
private const val LOG_PAGE_SIZE = 12

@Composable
fun ScoreDashboardScreen(
    states: List<ScoreUiState>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onLoadBillPayment: (accountIndex: Int, billId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalAvailableScore = states.filter { it.isReady }.sumOf { it.validScore }
    val readyAccountCount = states.count { it.isReady }
    var headerHeight by remember { mutableIntStateOf(0) }
    val indicatorTopOffset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        16.dp + with(LocalDensity.current) { headerHeight.toDp() }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        WaterPullRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            indicatorTopOffset = indicatorTopOffset
        ) { pullOffset ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(
                    top = 16.dp,
                    // 末项让开系统导航栏，否则列表滚到底也够不出来。
                    bottom = 16.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(Modifier.onSizeChanged { headerHeight = it.height }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "积分与消费记录",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text("积分收支与接水花费，分开查看更清楚", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (states.isNotEmpty()) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            ScoreSummaryCard(
                                accountCount = states.size,
                                readyAccountCount = readyAccountCount,
                                totalAvailableScore = totalAvailableScore
                            )
                        }
                    }
                }

                if (states.isEmpty()) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            Card(
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text("暂无可查询账号", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                    Text(
                                        "请先登录账号，完成后即可刷新并查看积分。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(states, key = { _, state -> state.accountName }) { index, state ->
                        PullRefreshOffsetContent(pullOffset) {
                            ScoreAccountCard(state, onLoadBillPayment = { billId -> onLoadBillPayment(index, billId) })
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ScoreSummaryCard(accountCount: Int, readyAccountCount: Int, totalAvailableScore: Int) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("积分概览", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("可用积分", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f))
                    Text(totalAvailableScore.toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(
                    "${readyAccountCount}/${accountCount} 个账号已就绪",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.80f)
                )
            }
        }
    }
}

@Composable
private fun ScoreAccountCard(state: ScoreUiState, onLoadBillPayment: (String) -> Unit) {
    var showBills by rememberSaveable(state.accountName) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(state.accountName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (state.isReady) state.message else "暂不可查询：${state.message}",
                        fontSize = 12.sp,
                        color = if (state.isReady) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.validScore.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("可用 · ${state.validMoneyText}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (state.isReady) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("累计积分", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text("${state.totalScore} · ${state.totalMoneyText}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showBills, onClick = { showBills = false }, label = { Text("积分明细") })
                FilterChip(selected = showBills, onClick = { showBills = true }, label = { Text("接水消费") })
            }

            if (showBills) WaterBillList(state, onLoadBillPayment) else ScoreLogList(state)
        }
    }
}

@Composable
private fun ScoreLogList(state: ScoreUiState) {
    var visibleLogCount by rememberSaveable(state.accountName, state.logs.size) {
        mutableIntStateOf(INITIAL_LOG_COUNT)
    }
    val visibleLogs = state.logs.take(visibleLogCount)
    val hasMoreLogs = visibleLogCount < state.logs.size

    if (visibleLogs.isNotEmpty()) {
        Column {
            visibleLogs.forEachIndexed { index, log ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(log.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(log.timeText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        log.scoreText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (log.isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    } else if (state.isReady) {
        Text("暂时没有积分变动记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (hasMoreLogs) {
        MoreButton(state.logs.size - visibleLogCount) {
            visibleLogCount = (visibleLogCount + LOG_PAGE_SIZE).coerceAtMost(state.logs.size)
        }
    } else if (state.logs.size > INITIAL_LOG_COUNT) {
        Text("已展示 ${state.logs.size} 条最近记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WaterBillList(state: ScoreUiState, onLoadBillPayment: (String) -> Unit) {
    var visibleCount by rememberSaveable(state.accountName, state.bills.size) {
        mutableIntStateOf(INITIAL_LOG_COUNT)
    }
    Text(
        if (state.billLoading) "正在加载接水记录…" else state.billMessage,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.bills.isEmpty()) return

    Column {
        state.bills.take(visibleCount).forEachIndexed { index, bill ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            WaterBillRow(bill, onLoad = { onLoadBillPayment(bill.id) })
        }
    }
    if (visibleCount < state.bills.size) {
        MoreButton(state.bills.size - visibleCount) {
            visibleCount = (visibleCount + LOG_PAGE_SIZE).coerceAtMost(state.bills.size)
        }
    }
    Text(
        "接水记录包含积分与金额支付，不代表完整钱包收支。",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun WaterBillRow(bill: WaterBillLogUiState, onLoad: () -> Unit) {
    var expanded by rememberSaveable(bill.id) { mutableStateOf(false) }
    // 进入可见范围就预取金额，折叠状态下也能直接看到本次花费。
    LaunchedEffect(bill.id) {
        if (bill.payment == null && !bill.loading && bill.error == null) onLoad()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(bill.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(bill.timeText, fontSize = 11.sp, color = muted)
            }
            val total = bill.payment?.totalText
            if (total != null) {
                Text(total, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(if (bill.error != null) "金额暂不可用" else "金额加载中…", fontSize = 12.sp, color = muted)
            }
        }
        TextButton(
            onClick = {
                expanded = !expanded
                if (expanded && bill.payment == null && !bill.loading) onLoad()
            },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(if (expanded) "收起支付明细" else "查看支付明细", fontSize = 12.sp)
        }
        if (expanded) {
            val payment = bill.payment
            when {
                payment != null -> {
                    if (payment.parts.isEmpty()) {
                        Text("本笔账单未产生扣费", fontSize = 13.sp, color = muted)
                    }
                    payment.parts.forEach { PaymentLine(it.label, it.value, muted) }
                    PaymentLine("本次总花费", payment.totalText, MaterialTheme.colorScheme.onSurface, bold = true)
                }
                bill.error != null -> Text(bill.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                else -> Text("正在加载支付明细…", fontSize = 13.sp, color = muted)
            }
        }
    }
}

@Composable
private fun PaymentLine(label: String, value: String, labelColor: Color, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = labelColor, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun MoreButton(remaining: Int, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Text("查看更多记录（剩余 $remaining 条）")
    }
}
