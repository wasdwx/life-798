package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotificationTextFormatterTest {
    @Test
    fun formatsSingleLaneWithCurrentAccountAndTask() {
        val state = TaskRunState(
            running = true,
            totalGained = 15,
            lanes = listOf(
                TaskLaneState(
                    laneId = 1,
                    laneCount = 1,
                    accountIndex = 1,
                    accountCount = 2,
                    accountLabel = "测试账号",
                    currentTask = "[APP] 每日浏览（2/3）",
                    phase = TaskLanePhase.RUNNING_MISSION,
                    gained = 15
                )
            )
        )

        assertEquals(
            TaskNotificationText(
                title = "积分任务运行中 · 已获得 15 分",
                text = "测试账号 · [APP] 每日浏览（2/3）"
            ),
            TaskNotificationTextFormatter.format(state)
        )
    }

    @Test
    fun formatsTwoConcurrentLanesWithoutLosingEitherCurrentTask() {
        val state = TaskRunState(
            running = true,
            totalGained = 10,
            lanes = listOf(
                TaskLaneState(
                    laneId = 1,
                    laneCount = 2,
                    accountCount = 1,
                    accountLabel = "账号甲",
                    currentTask = "每日签到",
                    phase = TaskLanePhase.SIGNING_IN
                ),
                TaskLaneState(
                    laneId = 2,
                    laneCount = 2,
                    accountCount = 1,
                    accountLabel = "账号乙",
                    currentTask = "[支付宝] 浏览商品",
                    phase = TaskLanePhase.RUNNING_MISSION
                )
            )
        )

        assertEquals(
            "通道1：账号甲 · 每日签到；通道2：账号乙 · [支付宝] 浏览商品",
            TaskNotificationTextFormatter.format(state).text
        )
    }

    @Test
    fun formatsCompletedTotal() {
        val state = TaskRunReducer.finish(
            TaskRunState(running = true, totalGained = 35)
        )

        assertEquals(
            TaskNotificationText("积分任务已完成", "本次共获得 35 分"),
            TaskNotificationTextFormatter.format(state)
        )
    }

    @Test
    fun formatsPartialCompletionWithoutClaimingFullSuccess() {
        val state = TaskRunReducer.finish(
            TaskRunState(running = true, totalGained = 20),
            partial = true
        )

        assertEquals(
            TaskNotificationText(
                "积分任务已结束 · 部分未完成",
                "本次已获得 20 分，可在运行记录中查看详情"
            ),
            TaskNotificationTextFormatter.format(state)
        )
    }

    @Test
    fun formatsFailureWithActionableReason() {
        val state = TaskRunReducer.fail(
            TaskRunState(totalGained = 5),
            "上一次任务请求仍在收尾，请约半分钟后重试。"
        )

        assertEquals(
            TaskNotificationText(
                "积分任务运行失败",
                "上一次任务请求仍在收尾，请约半分钟后重试。"
            ),
            TaskNotificationTextFormatter.format(state)
        )
    }

    /**
     * 「正在收尾」是运行中状态才有的文字：所有通道都标了完成、但 finish() 还没来。
     *
     * 真机上通知栏长期停在这句话，就等于批次没走到 finishRun，
     * 仓库的 running 挂死，之后每次启动都被判成「已在运行」。
     * 这条用例把这个含义钉住，别哪天顺手把它改成终态文案。
     */
    @Test
    fun wrapUpTextOnlyExistsWhileStillRunning() {
        val allLanesDone = TaskRunReducer.completeLane(
            TaskRunReducer.start(
                listOf(TaskLaneState(laneId = 1, laneCount = 1, accountCount = 1))
            ),
            laneId = 1
        )

        assertTrue(allLanesDone.running)
        assertEquals("正在收尾", TaskNotificationTextFormatter.format(allLanesDone).text)

        // 真正收尾之后必须换成终态标题，不能再是「运行中」
        val finished = TaskRunReducer.finish(allLanesDone)
        assertFalse(finished.running)
        assertEquals("积分任务已完成", TaskNotificationTextFormatter.format(finished).title)
    }
}
