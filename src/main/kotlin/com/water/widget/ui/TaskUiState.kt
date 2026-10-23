package com.water.widget.ui

import com.water.widget.Account
import com.water.widget.TaskLaneState
import com.water.widget.TaskRunResult

data class TaskUiState(
    val totalAccounts: Int,
    val runnableAccounts: Int,
    val dualPlatformAccounts: Int,
    val running: Boolean,
    val totalGainedText: String,
    val canRun: Boolean,
    val summary: String,
    val logs: List<String>,
    val hasFailures: Boolean,
    val lanes: List<TaskLaneState> = emptyList(),
    val result: TaskRunResult = TaskRunResult.NONE,
    val accountLabels: Map<String, String> = emptyMap()
)

object TaskUiStateFactory {
    fun from(
        accounts: List<Account>,
        running: Boolean,
        totalGained: Int,
        logs: List<String>,
        lanes: List<TaskLaneState> = emptyList(),
        result: TaskRunResult = TaskRunResult.NONE
    ): TaskUiState {
        val runnable = accounts.count { it.hasToken() || it.hasAppToken() }
        val dual = accounts.count { it.hasToken() && it.hasAppToken() }
        return TaskUiState(
            totalAccounts = accounts.size,
            runnableAccounts = runnable,
            dualPlatformAccounts = dual,
            running = running,
            totalGainedText = totalGained.toString(),
            canRun = !running && runnable > 0,
            summary = when {
                accounts.isEmpty() -> "请先添加账户"
                runnable == 0 -> "请完成用水登录或积分登录"
                else -> "$runnable 个账号可运行"
            },
            logs = logs,
            hasFailures = logs.any(TaskLogFormatter::isFailure),
            lanes = lanes,
            result = result,
            accountLabels = accounts.associate { it.phone to it.toString() }
        )
    }
}
