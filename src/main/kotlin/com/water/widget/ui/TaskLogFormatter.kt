package com.water.widget.ui

/**
 * 将执行层的诊断信息转换成适合直接展示给用户的运行记录。
 * 调度、并发和 Token 复用策略仍由执行层负责，但不占用界面日志空间。
 */
data class TaskLogGroup(val account: String?, val lines: List<String>)

object TaskLogFormatter {
    fun readableText(message: String): String = message
        .replace("核验累计积分变化", "正在确认积分到账")
        .replace("无可用 Token", "尚未登录")
        .replace("已核验新增", "已确认增加")
        .replace("已核验增加", "已确认增加")
        .replace("此前已核验积分", "此前已确认的积分")
        .replace("到账核验失败", "暂时无法确认到账")
        .replace("到账待核验", "等待确认到账")
        .replace("重试仍受限", "再次尝试仍被拒绝")

    private val accountPrefix = Regex("""^\[(.+?)]\s+(.+)$""")
    private val accountHeader = Regex("""^-+\s*\[(.+?)]\s+通道.+?-+$""")
    private val accountResult = Regex("""^\[(.+?)]\s+本账号(?:预计)?获得\s+(\d+)\s+分$""")
    private val allDone = Regex("""^=+\s*全部完成，本次(?:预计)?获得\s+(\d+)\s+分\s*=+$""")
    private val partialDone =
        Regex("""^=+\s*任务结束，部分未完成，本次(?:预计)?获得\s+(\d+)\s+分\s*=+$""")
    private val taskPlan = Regex("""^\[\d+/\d+]\s+\[(?:APP|支付宝)]""")
    private val signInSuccess = Regex("""^\[(.+?)]\s+签到成功\s+\+(\d+)分(?:（重试）)?$""")
    private val signInReward = Regex("""^\[(.+?)]\s+连签奖励\s+\+(\d+)分(（.+）)?$""")
    private val missionSuccess = Regex("""^\[(.+?)]\s+(\[(?:APP|支付宝)])\s+(.+)：成功\s+\+(\d+)分$""")
    private val missionRound = Regex("""\s+\(\d+/\d+\)$""")

    private data class SuccessRecord(
        val label: String,
        val unitScore: Int
    )

    fun format(raw: String): String? {
        val line = raw.trim()
        if (line.isBlank()) return null

        if (line.contains("已核验增加")) return line.trim('=').trim()

        successRecord(line)?.let {
            return renderSuccess(it, 1)
        }
        accountHeader.matchEntire(line)?.let {
            return "正在处理：${it.groupValues[1]}"
        }
        accountResult.matchEntire(line)?.let {
            return "[${it.groupValues[1]}] 完成 · 获得 ${it.groupValues[2]} 分"
        }
        allDone.matchEntire(line)?.let {
            return "全部完成 · 本次获得 ${it.groupValues[1]} 分"
        }
        partialDone.matchEntire(line)?.let {
            return "任务结束 · 部分未完成 · 本次获得 ${it.groupValues[1]} 分"
        }
        if (line.contains("首页任务中心：一键运行全部账号")) {
            return "开始运行全部账号"
        }

        if (line.contains("安全通道并发执行") ||
            line.contains("共享账号标识") ||
            line.contains("当前积分:") ||
            line.contains("官方 App 任务数:") ||
            line.startsWith("待执行 ") ||
            line.contains("每日签到: 未签到") ||
            line.contains("每日签到: 无签到数据") ||
            taskPlan.containsMatchIn(line)
        ) {
            return null
        }

        return line
            .replace("❌ ", "")
            .replace("⏳ ", "")
            .replace(Regex("""\s+"""), " ")
    }

    fun append(logs: List<String>, raw: String, maxEntries: Int = 80): List<String> {
        val line = raw.trim()
        val success = successRecord(line)
        val updated = if (success == null) {
            val displayLine = format(line) ?: return logs
            logs + displayLine
        } else {
            appendSuccess(logs, success)
        }
        return updated.takeLast(maxEntries.coerceAtLeast(1))
    }

    fun groupByAccount(logs: List<String>): List<TaskLogGroup> {
        val groups = linkedMapOf<String?, MutableList<String>>()
        logs.forEach { line ->
            val prefix = accountPrefix.matchEntire(line)
            val account = if (line.startsWith("正在处理：")) line.removePrefix("正在处理：")
                else prefix?.groupValues?.get(1)
            val message = if (line.startsWith("正在处理：")) "开始处理"
                else prefix?.groupValues?.get(2) ?: line
            groups.getOrPut(account) { mutableListOf() }.add(message)
        }
        return groups.entries.sortedBy { it.key.orEmpty() }.map { (account, lines) ->
            TaskLogGroup(account, lines.asReversed())
        }
    }

    fun isFailure(line: String): Boolean = line.contains("❌") || line.contains("失败") ||
        line.contains("错误") || line.contains("部分未完成") || line.contains("重试仍受限")

    private fun successRecord(line: String): SuccessRecord? {
        signInSuccess.matchEntire(line)?.let {
            return SuccessRecord(label = "[${it.groupValues[1]}] 签到成功", unitScore = it.groupValues[2].toInt())
        }
        signInReward.matchEntire(line)?.let {
            return SuccessRecord(
                label = "[${it.groupValues[1]}] 连签奖励${it.groupValues[3]}",
                unitScore = it.groupValues[2].toInt()
            )
        }
        missionSuccess.matchEntire(line)?.let {
            val taskName = it.groupValues[3].replace(missionRound, "")
            return SuccessRecord(
                label = "[${it.groupValues[1]}] ${it.groupValues[2]} $taskName",
                unitScore = it.groupValues[4].toInt()
            )
        }
        return null
    }

    private fun appendSuccess(logs: List<String>, success: SuccessRecord): List<String> {
        val existingIndex = logs.indexOfFirst { countFor(it, success) != null }
        if (existingIndex < 0) return logs + renderSuccess(success, 1)

        val count = countFor(logs[existingIndex], success) ?: return logs + renderSuccess(success, 1)
        return logs.toMutableList().apply {
            this[existingIndex] = renderSuccess(success, count + 1)
        }
    }

    private fun countFor(line: String, success: SuccessRecord): Int? {
        if (line == renderSuccess(success, 1)) return 1
        val aggregate = Regex(
            """^${Regex.escape(success.label)} \+\d+分（${success.unitScore}分 × (\d+)）$"""
        )
        return aggregate.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun renderSuccess(success: SuccessRecord, count: Int): String {
        val total = success.unitScore * count
        return if (count == 1) {
            "${success.label} +${total}分"
        } else {
            "${success.label} +${total}分（${success.unitScore}分 × $count）"
        }
    }
}
