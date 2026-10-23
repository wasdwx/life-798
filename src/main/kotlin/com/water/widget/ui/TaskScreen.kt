package com.water.widget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.water.widget.TaskRunResult

private const val MAX_VISIBLE_TASK_LOGS = 80
private const val COLLAPSED_TASK_LOGS = 3

@Composable
fun TaskScreen(
    state: TaskUiState,
    onRun: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var onlyFailures by rememberSaveable { mutableStateOf(false) }
    val visibleLogs = remember(state.logs, onlyFailures) {
        if (onlyFailures) {
            state.logs.filter(TaskLogFormatter::isFailure)
        } else {
            state.logs
        }.takeLast(MAX_VISIBLE_TASK_LOGS)
    }
    val groups = remember(visibleLogs) { TaskLogFormatter.groupByAccount(visibleLogs) }
    val actionLabel = when {
        state.running -> "停止任务"
        state.canRun -> "运行今日任务"
        state.totalAccounts == 0 -> "请先添加账户"
        else -> "请先完成登录"
    }

    Column(
        modifier = modifier.fillMaxWidth().background(Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            shape = WaterShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("今日任务", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        state.running -> "任务运行中"
                        state.result == TaskRunResult.COMPLETED -> "本次任务已完成"
                        state.result == TaskRunResult.PARTIAL -> "本次已结束 · 部分任务未完成或到账未确认"
                        state.result == TaskRunResult.CANCELLED -> "本次任务已停止"
                        state.result == TaskRunResult.FAILED -> "本次运行失败，请查看记录"
                        else -> state.summary
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (state.totalGainedText != "0" || state.result == TaskRunResult.COMPLETED) {
                    Text(
                        "已确认增加 ${state.totalGainedText} 积分",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (state.totalGainedText == "0" &&
                    (state.running || state.result != TaskRunResult.NONE && state.result != TaskRunResult.COMPLETED)) {
                    Text("暂未确认有积分增加", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                if (state.running) {
                    state.lanes.forEach { lane ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                lane.accountLabel.ifBlank { "正在处理账号" },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(TaskLogFormatter.readableText(lane.currentTask), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("任务间隔 30 秒；受限后等 60 秒重试一次，仍受限则跳过。", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.totalGainedText != "0" || state.result == TaskRunResult.COMPLETED) {
                    Text("按官方累计积分确认，包含运行期间从其他途径获得的积分。", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = if (state.running) onStop else onRun,
                    enabled = state.running || state.canRun,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(actionLabel)
                }
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("运行记录", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        when {
                            visibleLogs.isEmpty() && onlyFailures -> "没有失败记录"
                            visibleLogs.isEmpty() -> "暂无记录"
                            onlyFailures -> "${visibleLogs.size} 条失败记录"
                            else -> "最近 ${visibleLogs.size} 条 · 按账号分类"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                if (state.hasFailures) {
                    FilterChip(
                        selected = onlyFailures,
                        onClick = { onlyFailures = !onlyFailures },
                        label = { Text("仅看失败", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.errorContainer)
                    )
                }
            }
            if (visibleLogs.isEmpty()) {
                Text(
                    if (onlyFailures) "没有失败记录" else "运行任务后显示结果",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                groups.forEach { group ->
                    key(group.account) { AccountLogCard(group, state.accountLabels[group.account] ?: group.account) }
                }
            }
        }
    }
}

@Composable
private fun AccountLogCard(group: TaskLogGroup, accountLabel: String?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(accountLabel ?: "整体运行", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (group.account != null && group.account != accountLabel) {
                Text(group.account, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${group.lines.size} 条记录 · 最新在前", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            group.lines.take(if (expanded) MAX_VISIBLE_TASK_LOGS else COLLAPSED_TASK_LOGS).forEachIndexed { index, line ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                TaskLogRow(line)
            }
            if (group.lines.size > COLLAPSED_TASK_LOGS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起记录" else "展开全部 ${group.lines.size} 条记录")
                }
            }
        }
    }
}

@Composable
private fun TaskLogRow(line: String) {
    val style = logStyle(line)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(shape = CircleShape, color = style.background, modifier = Modifier.padding(top = 1.dp)) {
            Icon(style.icon, contentDescription = style.label, tint = style.color, modifier = Modifier.padding(5.dp))
        }
        Text(
            text = TaskLogFormatter.readableText(line.trim().removePrefix("===== ").removeSuffix(" =====")),
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}

private data class TaskLogStyle(val icon: ImageVector, val color: Color, val background: Color, val label: String)

@Composable
private fun logStyle(line: String): TaskLogStyle = when {
    TaskLogFormatter.isFailure(line) -> TaskLogStyle(
        Icons.Default.ErrorOutline,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.errorContainer,
        "失败"
    )
    line.contains("待核验") || line.contains("⏳") || line.contains("等待") || line.contains("频率") || line.contains("重试") -> TaskLogStyle(
        Icons.Default.Schedule,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.tertiaryContainer,
        "等待确认"
    )
    line.contains("已核验增加") -> TaskLogStyle(
        Icons.Default.CheckCircle,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primaryContainer,
        "已确认"
    )
    line.contains("✅") || line.contains("成功") || line.contains("完成") || line.contains("获得") -> TaskLogStyle(
        Icons.Default.CheckCircle,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primaryContainer,
        "成功"
    )
    else -> TaskLogStyle(
        Icons.Default.Info,
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant,
        "信息"
    )
}
