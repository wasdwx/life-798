package com.water.widget.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val INITIAL_LOG_COUNT = 8
private const val LOG_PAGE_SIZE = 12

/**
 * 积分与消费记录页。
 *
 * 界面沿用上游 v5.5.0 的「积分明细 / 接水消费」顶部分栏；
 * 分页仍用本地实现：接水账单按页从 /bill/lst-owner 拉取，
 * [ScoreUiState.billPage]、[ScoreUiState.hasMoreBills] 决定是否还有下一页，
 * 回调按账号下标区分（本地 ScoreActivity 的 loadBillPage/loadMoreBills 都按下标）。
 */
@Composable
fun ScoreDashboardScreen(
    states: List<ScoreUiState>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onLoadBillPayment: (accountIndex: Int, billId: String) -> Unit,
    onLoadMoreBills: (accountIndex: Int) -> Unit = {},
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    // ScoreActivity 目前没有传 onBack，这里退回直接结束宿主 Activity，保证返回键可用。
    val hostActivity = LocalContext.current as? Activity
    val back: () -> Unit = onBack ?: { hostActivity?.finish() }
    var showingBills by rememberSaveable { mutableStateOf(false) }
    val readyStates = states.filter { it.isReady }
    val totalAvailableScore = readyStates.sumOf { it.validScore }
    val readyAccountCount = readyStates.size
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(LocalDensity.current) { headerHeightPx.toDp() }
    val indicatorTopOffset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp + headerHeight

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
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = back) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Text(
                                "积分与消费记录",
                                modifier = Modifier.weight(1f),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text("积分收支与接水花费，分开查看更清楚", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = !showingBills, onClick = { showingBills = false }, label = { Text("积分明细") })
                            FilterChip(selected = showingBills, onClick = { showingBills = true }, label = { Text("接水消费") })
                        }
                    }
                }

                if (states.isNotEmpty() && !showingBills) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            ScoreSummaryCard(
                                accountCount = states.size,
                                readyAccountCount = readyAccountCount,
                                totalAvailableScore = totalAvailableScore,
                                // 本地没有单独的 scoreLoading 标记：只要有账号还没就绪就按「加载中」处理。
                                loading = states.any { !it.isReady }
                            )
                        }
                    }
                }

                if (states.isEmpty()) {
                    item {
                        PullRefreshOffsetContent(pullOffset) {
                            Card(
                                shape = WaterShapeTokens.card,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text("暂无可查询账号", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                    Text(
                                        "请先登录账号，完成后即可查看积分与接水记录。",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // key 里带上下标：本地没有 accountKey，同名的两个账号不能撞 key。
                    itemsIndexed(states, key = { index, state -> "$index:${state.accountName}" }) { index, state ->
                        PullRefreshOffsetContent(pullOffset) {
                            if (showingBills) {
                                WaterBillAccountCard(state, index, onLoadBillPayment, onLoadMoreBills)
                            } else {
                                ScoreAccountCard(state)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun ScoreSummaryCard(accountCount: Int, readyAccountCount: Int, totalAvailableScore: Int, loading: Boolean) {
    Card(
        shape = WaterShapeTokens.prominent,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("积分概览", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("已获取可用积分合计", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f))
                    Text(
                        when {
                            readyAccountCount > 0 -> totalAvailableScore.toString()
                            loading -> "加载中…"
                            else -> "暂不可用"
                        },
                        fontSize = if (readyAccountCount > 0) 30.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Text(
                    "${readyAccountCount}/${accountCount} 个账号已获取",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.80f)
                )
            }
        }
    }
}

@Composable
private fun ScoreAccountCard(state: ScoreUiState) {
    var visibleLogCount by rememberSaveable(state.accountName, state.logs.size) {
        mutableIntStateOf(INITIAL_LOG_COUNT)
    }
    val visibleLogs = state.logs.take(visibleLogCount)
    val hasMoreLogs = visibleLogCount < state.logs.size

    Card(
        shape = WaterShapeTokens.card,
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
                    when {
                        !state.isReady -> Text("暂不可用", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            Text(state.validScore.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("可用 · ${state.validMoneyText}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (state.isReady) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("累计积分", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text("${state.totalScore} · ${state.totalMoneyText}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
            }

            if (visibleLogs.isNotEmpty()) {
                Text("最近记录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(log.timeText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(state.message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (hasMoreLogs) {
                FilledTonalButton(
                    onClick = { visibleLogCount = (visibleLogCount + LOG_PAGE_SIZE).coerceAtMost(state.logs.size) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = WaterShapeTokens.compact
                ) {
                    Text("查看更多记录（剩余 ${state.logs.size - visibleLogCount} 条）")
                }
            } else if (state.logs.size > INITIAL_LOG_COUNT) {
                Text("已展示 ${state.logs.size} 条最近记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaterBillAccountCard(
    state: ScoreUiState,
    accountIndex: Int,
    onLoadBillPayment: (Int, String) -> Unit,
    onLoadMoreBills: (Int) -> Unit
) {
    var visibleCount by rememberSaveable(state.accountName) { mutableIntStateOf(INITIAL_LOG_COUNT) }
    // 翻页新到的记录直接展开，否则刚加载完又要再点一次「查看更多接水记录」。
    var knownSize by remember(state.accountName) { mutableIntStateOf(state.bills.size) }
    LaunchedEffect(state.bills.size) {
        if (state.bills.size > knownSize) {
            visibleCount += state.bills.size - knownSize
            knownSize = state.bills.size
        }
    }
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.accountName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(state.billMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.bills.take(visibleCount).forEach { bill ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                WaterBillRow(bill, onLoad = { onLoadBillPayment(accountIndex, bill.id) })
            }
            if (visibleCount < state.bills.size) {
                FilledTonalButton(
                    onClick = { visibleCount = (visibleCount + LOG_PAGE_SIZE).coerceAtMost(state.bills.size) },
                    modifier = Modifier.fillMaxWidth(), shape = WaterShapeTokens.compact
                ) { Text("查看更多接水记录") }
            } else if (state.hasMoreBills || state.billLoading) {
                // 本地已取到的账单都展示完了，才向后端要下一页（billPage 由 ScoreActivity 维护）。
                FilledTonalButton(
                    onClick = { onLoadMoreBills(accountIndex) },
                    enabled = !state.billLoading,
                    modifier = Modifier.fillMaxWidth(), shape = WaterShapeTokens.compact
                ) { Text(if (state.billLoading) "正在加载账单…" else "加载更多账单") }
            }
            if (state.bills.isNotEmpty()) {
                Text("接水记录包含积分与金额支付，不代表完整钱包收支。", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaterBillRow(bill: WaterBillLogUiState, onLoad: () -> Unit) {
    var expanded by rememberSaveable(bill.id) { mutableStateOf(false) }
    // 只按账单 id 触发预取：把 payment/loading/error 也当 key 会在回包后反复重取。
    LaunchedEffect(bill.id) {
        if (bill.payment == null && !bill.loading && bill.error == null) onLoad()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(bill.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(bill.timeText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (bill.payment != null) {
                Text(bill.payment.totalText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(if (bill.error != null) "金额暂不可用" else "金额加载中…", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = {
            expanded = !expanded
            // 已经取到金额就不再重复请求。
            if (expanded && bill.payment == null && !bill.loading) onLoad()
        }) { Text(if (expanded) "收起支付明细" else "查看支付明细") }
        if (expanded) {
            when {
                bill.loading -> Text("正在加载支付明细…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                bill.error != null -> {
                    Text(bill.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onLoad) { Text("重试") }
                }
                bill.payment != null -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本次总花费", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(bill.payment.totalText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (bill.payment.parts.isEmpty()) {
                        Text("本笔账单未产生扣费", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    bill.payment.parts.forEach { part ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(part.label, Modifier.weight(1f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(part.value, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
