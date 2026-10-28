package com.water.widget

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 积分任务的唯一执行入口。
 *
 * Activity 和小部件只发送启动/停止请求；任务批次、限速等待和网络回调全部由此前台服务持有，
 * 因此旋转屏幕、离开页面或 Activity 被回收都不会中断当前批次。
 */
class TaskForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val taskRateLimiter = TaskExecutionCoordinator.tokenRateLimiter
    private var taskLease: TaskExecutionCoordinator.Lease? = null
    private var generation = 0
    private var running = false
    private var stoppingNormally = false
    private var completedLanes = 0
    private var hadFailures = false
    // 本批是否存在「连接层失败」（超时/断网/DNS），用于决定要不要自动补跑
    private var networkFailures = false
    private var lastWidgetRefreshAt = 0L
    private var latestStartId = 0
    private val expiredTokens = mutableSetOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private val batchTimeout = Runnable { softCancel("任务已运行一小时，已停止以避免持续耗电，可稍后继续。") }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationScope.launch {
            TaskRunRepository.state.collect { state ->
                if (running || stoppingNormally) {
                    updateNotification(state)
                    throttledWidgetRefresh()
                }
                if (running) armStallWatchdog()
            }
        }
    }

    /**
     * 批次停摆看门狗。
     *
     * 任何一条回调链被静默掐断（网络回调没回、通道计数没对上、限速重试打转），
     * finishRun 就不会来，仓库的 running 会永远挂在 true 上，
     * 而前台服务自己不会死，于是 onDestroy 的兜底也轮不到。
     * 之后每次启动都被判成「已在运行」——点了没反应、不弹通知、运行记录也不动。
     *
     * 合法的最长静默是限速重试的 60 秒加上两个 15 秒网络超时，
     * 四分钟没有任何状态变化就足以断定卡死了。
     */
    private val stallWatchdog = Runnable {
        if (running) {
            softCancel("任务超过 ${STALL_TIMEOUT_MILLIS / 60_000} 分钟没有进展，已自动停止，可重新运行。")
        }
    }

    private fun armStallWatchdog() {
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.postDelayed(stallWatchdog, STALL_TIMEOUT_MILLIS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP_TASKS && running) {
            // softCancel 用刚记下的 latestStartId 停服务；没在跑的停止请求落到下面直接 stopSelf
            softCancel("任务已手动停止，可稍后重新运行。")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_RUN_TASKS) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running || TaskRunRepository.state.value.running) {
            // 这里不能 stopSelf(startId)：startId 是刚收到的、也就是最新的，
            // stopSelf 会真的把服务停掉，连带干掉正在跑的批次。
            // 服务已经在前台，系统的 5 秒 startForeground 要求也已经满足。
            return START_NOT_STICKY
        }

        val accounts = AccountStore.list(this).filter { it.hasToken() || it.hasAppToken() }
        if (accounts.isEmpty()) {
            startForegroundCompat(buildNotification(TaskRunState(running = true)))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val lease = TaskExecutionCoordinator.tryAcquire(accounts)
        if (lease == null) {
            val message = "上一次任务请求仍在收尾，请约半分钟后重试。"
            TaskRunRepository.fail(message)
            val failedState = TaskRunRepository.state.value
            startForegroundCompat(buildNotification(failedState))
            notifyTaskResult(failedState)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val plannedLanes = AccountExecutionPlanner.plan(accounts)
        val laneStates = plannedLanes.mapIndexed { index, lane ->
            TaskLaneState(
                laneId = index + 1,
                laneCount = plannedLanes.size,
                accountCount = lane.accounts.size
            )
        }
        taskLease = lease
        taskRateLimiter.clear()
        completedLanes = 0
        hadFailures = false
        networkFailures = false
        expiredTokens.clear()
        running = true
        stoppingNormally = false
        val runGeneration = ++generation

        TaskRunRepository.start(laneStates)
        appendTaskLog("===== 首页任务中心：一键运行全部账号 =====")
        appendTaskLog(
            "共 ${accounts.size} 个账号，分为 ${plannedLanes.size} 个安全通道并发执行；" +
                "共享账号标识、uid 或 Token 的账号将串行执行。"
        )
        startForegroundCompat(buildNotification(TaskRunRepository.state.value))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "water:points-task").apply {
                setReferenceCounted(false)
                acquire(MAX_BATCH_MILLIS)
            }
        mainHandler.postDelayed(batchTimeout, MAX_BATCH_MILLIS)

        // 息屏/刚唤醒时网络常常还停在 CONNECTING，或者「连着 Wi-Fi 但未验证」，
        // 这时立刻发请求会瞬间失败、整批任务报废。先等系统确认网络可用（最多 8 秒）。
        // 等不到就不要硬撞：直接结束本批并安排补跑，省掉「白等 + 4 次重试 × 退避」的几十秒。
        Thread {
            val networkReady = NetReady.await(this)
            onMain {
                if (!isActive(runGeneration)) return@onMain
                if (!networkReady) {
                    networkFailures = true
                    appendTaskLog("网络不可用（未就绪），本次跳过全部账号，稍后自动补跑")
                    plannedLanes.forEachIndexed { laneIndex, _ ->
                        TaskRunRepository.updateLane(
                            laneId = laneIndex + 1,
                            currentTask = "网络不可用",
                            phase = TaskLanePhase.FAILED
                        )
                        TaskRunRepository.completeLane(laneIndex + 1)
                        completedLanes += 1
                    }
                    if (completedLanes >= plannedLanes.size) finishRun()
                    return@onMain
                }
                plannedLanes.forEachIndexed { laneIndex, lane ->
                    runAccountLane(
                        accounts = lane.accounts,
                        index = 0,
                        laneId = laneIndex + 1,
                        laneCount = plannedLanes.size,
                        runGeneration = runGeneration
                    ) {
                        if (!isActive(runGeneration)) return@runAccountLane
                        TaskRunRepository.completeLane(laneIndex + 1)
                        completedLanes += 1
                        // 用 >= 而不是 ==：万一某个通道回调多走了一次，用相等判断会直接错过收尾，
                        // 仓库的 running 就永远挂在 true 上，之后每次启动都被当成「已在运行」。
                        if (completedLanes >= plannedLanes.size) finishRun()
                    }
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    /**
     * Android 15 对 dataSync 前台服务触发时限回调时停止继续调度。
     * 已发出的 HttpURLConnection 无法可靠强制取消，所以 lease 保留一个网络超时窗口。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        softCancel("任务达到系统运行时限，已安全停止，可稍后重新运行。")
    }

    override fun onDestroy() {
        releaseWakeLock()
        notificationScope.cancel()
        mainHandler.removeCallbacks(stallWatchdog)
        if (running && !stoppingNormally) {
            generation += 1
            running = false
            mainHandler.removeCallbacksAndMessages(null)
            taskLease?.let(TaskExecutionCoordinator::releaseAfterInFlightNetworkGrace)
            taskLease = null
            TaskRunRepository.cancel("任务服务被系统停止，可重新运行。")
            // 状态已经不是「运行中」了，不刷新的话小部件会一直停在最后一次广播的进度上
            WidgetSupport.refreshAll(this)
        } else if (TaskRunRepository.state.value.running) {
            // 服务都要销毁了，进程里不可能还有批次在跑。仓库却还挂着「运行中」，
            // 说明某条回调链被静默掐断、没走到 finishRun。
            // 这个 latch 没人会再清：start() 会永远返回 ALREADY_RUNNING，
            // 于是「点了没反应、不弹通知、运行记录里也没有」——必须在这里兜住。
            TaskRunRepository.cancel("上次任务未正常收尾，状态已重置，可重新运行。")
            notifyTaskResult(TaskRunRepository.state.value)
            WidgetSupport.refreshAll(this)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 同 WaterService：只触发重绘，让进程存活与否自己决定小部件显示什么
        WidgetSupport.refreshAll(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun runAccountLane(
        accounts: List<Account>,
        index: Int,
        laneId: Int,
        laneCount: Int,
        runGeneration: Int,
        done: () -> Unit
    ) {
        if (!isActive(runGeneration)) return
        if (index >= accounts.size) {
            done()
            return
        }

        val account = accounts[index]
        val accountLabel = account.toString().ifBlank { "账号 ${index + 1}" }
        TaskRunRepository.updateLane(
            laneId = laneId,
            accountIndex = index + 1,
            accountLabel = accountLabel,
            currentTask = "正在加载任务",
            phase = TaskLanePhase.LOADING
        )
        appendTaskLog(
            "\n---------- [$account] 通道 $laneId/$laneCount，" +
                "账号 ${index + 1}/${accounts.size} ----------"
        )
        loadAndRunAccountTasks(account, laneId, runGeneration) { gained ->
            if (!isActive(runGeneration)) return@loadAndRunAccountTasks
            appendTaskLog("  [$account] 本账号获得 $gained 分")
            if (gained > 0) {
                // 起始余额已在 loadAndRunAccountTasks 里缓存，这里补上本次新增；
                // 不补的话小部件显示的余额永远停在跑之前那个数。
                account.score += gained
                val token = listOf(account.token.orEmpty(), account.appToken.orEmpty())
                    .firstOrNull { it.isNotBlank() && it !in expiredTokens }.orEmpty()
                AccountStore.updateScore(this@TaskForegroundService, account.phone, token, account.score)
            }
            if (index + 1 >= accounts.size) {
                // 最后一个账号后面没有请求要限速，空等 ACCOUNT_GAP 只会让通知多停在「收尾」
                done()
                return@loadAndRunAccountTasks
            }
            TaskRunRepository.updateLane(
                laneId = laneId,
                currentTask = "准备下一个账号",
                phase = TaskLanePhase.WAITING
            )
            postDelayed(ACCOUNT_GAP_MILLIS, runGeneration) {
                runAccountLane(accounts, index + 1, laneId, laneCount, runGeneration, done)
            }
        }
    }

    private fun loadAndRunAccountTasks(
        account: Account,
        laneId: Int,
        runGeneration: Int,
        platform: TaskPlatform = TaskProtocol.preferredPlatform(account),
        done: (Int) -> Unit
    ) {
        loadMissionStage(account, laneId, runGeneration, platform, attempt = 0) { missionJson, _, effectivePlatform ->
            if (!isActive(runGeneration)) return@loadMissionStage
            // missionJson == null 表示连接层失败（超时/DNS/断网），此时签到、进度接口同样连不上，
            // 再逐个去撞只是把等待时间翻几倍，这里直接结束本账号。
            if (missionJson == null) {
                appendTaskLog("  [$account] 任务列表连不上服务端，跳过本账号其余阶段")
                TaskRunRepository.updateLane(
                    laneId = laneId,
                    currentTask = "网络不可用",
                    phase = TaskLanePhase.FAILED
                )
                done(0)
                return@loadMissionStage
            }
            val data = missionJson.optJSONObject("data")
            val missions = data?.optJSONArray("missions")
            val score = data?.optJSONObject("accScoreRsp")?.optInt("validScore", -1) ?: -1
            appendTaskLog("  当前积分: " + score + "，任务数: " + (missions?.length() ?: 0))
            if (score >= 0) {
                // 缓存下来供桌面小部件离线展示，小部件不能自己发网络请求
                account.score = score
                AccountStore.updateScore(
                    this@TaskForegroundService,
                    account.phone,
                    TaskProtocol.token(account, effectivePlatform),
                    score
                )
            }
            TaskRunRepository.updateLane(
                laneId = laneId,
                currentTask = "检查每日签到",
                phase = TaskLanePhase.SIGNING_IN
            )
            runDailySignInIfNeeded(account, effectivePlatform, data, laneId, runGeneration) { signGained ->
                TaskRunRepository.updateLane(
                    laneId = laneId,
                    currentTask = "正在合并任务列表",
                    phase = TaskLanePhase.LOADING_MISSIONS
                )
                loadAppMissionsIfNeeded(account, effectivePlatform, missions, laneId, runGeneration) { plannedMissions ->
                    loadScoreProgressStage(account, effectivePlatform, laneId, runGeneration, attempt = 0) { doneCount ->
                        val work = TaskMissionPlanner.buildWork(plannedMissions, doneCount)
                        if (work.isEmpty()) {
                            appendTaskLog("  [$account] 无可执行任务（已完成/无积分/已过滤）")
                            TaskRunRepository.updateLane(
                                laneId = laneId,
                                currentTask = "没有待执行任务",
                                phase = TaskLanePhase.WAITING
                            )
                            done(signGained)
                        } else {
                            appendTaskLog("  待执行 " + work.size + " 次任务")
                            runMissionItems(
                                account = account,
                                work = work,
                                index = 0,
                                gained = signGained,
                                laneId = laneId,
                                runGeneration = runGeneration,
                                done = done
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 阶段一：任务列表。
     *
     * 以前这里失败就直接 done(0)，签到 / 进度核对 / 领奖全部跟着不执行——一个接口把整批任务拖死。
     * 现在它只负责「拿到任务列表」：失败先等 5 秒重试一次，主平台连不上或失效时切 App 平台，
     * 仍然失败就把 null 交给调用方，由调用方决定后面几个阶段怎么走。
     */
    private fun loadMissionStage(
        account: Account,
        laneId: Int,
        runGeneration: Int,
        platform: TaskPlatform,
        attempt: Int,
        done: (JSONObject?, String?, TaskPlatform) -> Unit
    ) {
        if (!isActive(runGeneration)) return
        val token = TaskProtocol.token(account, platform)
        if (token.isBlank()) {
            done(null, "无可用 Token", platform)
            return
        }
        IlifeApi.missionLstWithToken(token, platform) { json, err ->
            onMain {
                if (!isActive(runGeneration)) return@onMain
                if (json != null && json.optInt("code", -999) == 0) {
                    done(json, null, platform)
                    return@onMain
                }
                hadFailures = true
                recordTokenFailure(account, platform, token, json)
                val networkFailure = json == null
                if (networkFailure) networkFailures = true
                val mainExpired = json?.optInt("code") == -99
                appendTaskLog(
                    "  [" + account + "][" + platform + "] 获取任务列表失败(" +
                        (attempt + 1) + "/" + STAGE_RETRY_ATTEMPTS + "): " +
                        TaskProtocol.failure(json, err, token)
                )
                if (platform == TaskPlatform.MAIN && account.hasAppToken() && (mainExpired || networkFailure)) {
                    appendTaskLog(
                        if (networkFailure) "  主平台网络失败，改为执行 App 平台任务"
                        else "  主平台登录失效，改为执行有效的 App 平台任务"
                    )
                    loadMissionStage(account, laneId, runGeneration, TaskPlatform.APP, attempt, done)
                    return@onMain
                }
                if (attempt + 1 < STAGE_RETRY_ATTEMPTS) {
                    appendTaskLog("  " + (STAGE_RETRY_DELAY_MILLIS / 1000) + " 秒后重试任务列表")
                    TaskRunRepository.updateLane(
                        laneId = laneId,
                        currentTask = "任务列表重试中",
                        phase = TaskLanePhase.WAITING
                    )
                    postDelayed(STAGE_RETRY_DELAY_MILLIS, runGeneration) {
                        loadMissionStage(account, laneId, runGeneration, platform, attempt + 1, done)
                    }
                } else {
                    appendTaskLog("  任务列表多次失败，继续其余阶段")
                    done(null, err, platform)
                }
            }
        }
    }

    /**
     * 阶段三：今日任务进度。与任务列表彻底解耦，自己重试；最终失败就按「未知进度」继续，
     * 由 TaskMissionPlanner 自己过滤已完成的项。
     */
    private fun loadScoreProgressStage(
        account: Account,
        platform: TaskPlatform,
        laneId: Int,
        runGeneration: Int,
        attempt: Int,
        done: (Map<String, Int>) -> Unit
    ) {
        if (!isActive(runGeneration)) return
        val token = TaskProtocol.token(account, platform)
        TaskRunRepository.updateLane(
            laneId = laneId,
            currentTask = "核对今日任务进度",
            phase = TaskLanePhase.LOADING_MISSIONS
        )
        IlifeApi.scoreLstWithToken(token) { json, err ->
            onMain {
                if (!isActive(runGeneration)) return@onMain
                if (json != null && json.optInt("code", -999) == 0) {
                    done(parseTodayDoneCount(json))
                    return@onMain
                }
                hadFailures = true
                recordTokenFailure(account, platform, token, json)
                if (json == null) networkFailures = true
                appendTaskLog(
                    "  [" + account + "] 获取今日任务进度失败(" +
                        (attempt + 1) + "/" + STAGE_RETRY_ATTEMPTS + "): " +
                        TaskProtocol.failure(json, err, token)
                )
                if (attempt + 1 < STAGE_RETRY_ATTEMPTS) {
                    TaskRunRepository.updateLane(
                        laneId = laneId,
                        currentTask = "进度重试中",
                        phase = TaskLanePhase.WAITING
                    )
                    postDelayed(STAGE_RETRY_DELAY_MILLIS, runGeneration) {
                        loadScoreProgressStage(account, platform, laneId, runGeneration, attempt + 1, done)
                    }
                } else {
                    appendTodoFallback(laneId)
                    done(emptyMap())
                }
            }
        }
    }

    private fun appendTodoFallback(laneId: Int) {
        appendTaskLog("  进度多次失败，按未知进度继续")
        TaskRunRepository.updateLane(
            laneId = laneId,
            currentTask = "按未知进度继续",
            phase = TaskLanePhase.WAITING
        )
    }

    private fun runDailySignInIfNeeded(
        account: Account,
        platform: TaskPlatform,
        data: JSONObject?,
        laneId: Int,
        runGeneration: Int,
        done: (Int) -> Unit
    ) {
        if (!isActive(runGeneration)) return
        val dailyRsp = data?.optJSONObject("dailyRSP")
        val weekMask = data?.optJSONObject("accScoreRsp")
            ?.optJSONObject("daily")
            ?.optInt("week", 0) ?: 0
        val calendarDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val weekDay = if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1
        val rules = dailyRsp?.optJSONArray("config")?.let { config ->
            buildList {
                for (index in 0 until config.length()) {
                    val rule = config.optJSONObject(index) ?: continue
                    add(
                        DailySignInRule(
                            weekMask = rule.optInt("rule", 0),
                            score = rule.optInt("score", 0),
                            description = rule.optString("msg", "")
                        )
                    )
                }
            }
        }.orEmpty()
        // 任务列表没拿到时 dailyRSP 也不存在，但签到是独立接口，仍按官方默认项试一次，
        // 不再因为任务列表失败就把签到一起放弃。
        val plan = if (data == null) {
            appendTaskLog("  [$account] 每日签到: 任务列表不可用，按默认签到项尝试")
            DailySignInPlanner.plan(
                weekMask = 0,
                weekDay = weekDay,
                adId = FALLBACK_SIGN_IN_AD_ID,
                score = FALLBACK_SIGN_IN_SCORE
            )
        } else {
            DailySignInPlanner.plan(
                weekMask = weekMask,
                weekDay = weekDay,
                adId = dailyRsp?.optString("adId", ""),
                score = dailyRsp?.optInt("score", 5) ?: 0,
                rules = rules
            )
        }
        when {
            plan.adId.isBlank() -> {
                appendTaskLog("  [$account] 每日签到: 无签到数据，跳过")
                done(0)
            }
            plan.alreadySigned -> {
                appendTaskLog("  [$account] 每日签到: 今日已签到")
                done(0)
            }
            else -> {
                appendTaskLog("  [$account] 每日签到: 未签到，优先执行")
                sendDailySignIn(account, platform, plan, laneId, runGeneration, retry = false, done = done)
            }
        }
    }

    private fun sendDailySignIn(
        account: Account,
        platform: TaskPlatform,
        plan: DailySignInPlan,
        laneId: Int,
        runGeneration: Int,
        retry: Boolean,
        done: (Int) -> Unit
    ) {
        if (!isActive(runGeneration)) return
        val token = TaskProtocol.token(account, platform)
        if (token.isNullOrBlank()) {
            hadFailures = true
            appendTaskLog("    [$account] 签到失败: 无可用 Token，继续任务")
            done(0)
            return
        }

        TaskRunRepository.updateLane(
            laneId = laneId,
            currentTask = if (retry) "重试每日签到" else "每日签到",
            phase = TaskLanePhase.SIGNING_IN
        )
        val delay = taskRateLimiter.reserveDelayMillis(token, System.currentTimeMillis())
        postDelayed(delay, runGeneration) {
            if (!canUseToken(account, platform, token)) {
                done(0)
                return@postDelayed
            }
            IlifeApi.scoreSendSignIn(token, account.uid, plan.weekDay, plan.adId, plan.baseScore, platform) { json, err ->
                onMain {
                    if (!isActive(runGeneration)) return@onMain
                    when {
                        json?.optInt("code", -999) == 0 -> {
                            appendTaskLog(
                                "    [$account] 签到成功 +${plan.baseScore}分" +
                                    if (retry) "（重试）" else ""
                            )
                            plan.rewards.forEach { reward ->
                                val description = reward.description
                                    .takeIf { it.isNotBlank() }
                                    ?.let { "（$it）" }
                                    .orEmpty()
                                appendTaskLog("    [$account] 连签奖励 +${reward.score}分$description")
                            }
                            TaskRunRepository.addPoints(laneId, plan.totalScore)
                            done(plan.totalScore)
                        }
                        json?.optInt("code", -999) == -98 && !retry -> {
                            appendTaskLog("    [$account] 签到频率限制，60秒后重试一次")
                            TaskRunRepository.updateLane(
                                laneId = laneId,
                                currentTask = "签到受限，等待重试",
                                phase = TaskLanePhase.WAITING
                            )
                            postDelayed(RETRY_DELAY_MILLIS, runGeneration) {
                                sendDailySignIn(
                                    account,
                                    platform,
                                    plan,
                                    laneId,
                                    runGeneration,
                                    retry = true,
                                    done = done
                                )
                            }
                        }
                        else -> {
                            hadFailures = true
                            recordTokenFailure(account, platform, token, json)
                            val reason = TaskProtocol.failure(json, err, token)
                            appendTaskLog("    [$account] 签到失败: $reason，继续任务")
                            done(0)
                        }
                    }
                }
            }
        }
    }

    private fun loadAppMissionsIfNeeded(
        account: Account,
        initialPlatform: TaskPlatform,
        mainMissions: JSONArray?,
        laneId: Int,
        runGeneration: Int,
        callback: (List<PlannedMission>) -> Unit
    ) {
        if (!isActive(runGeneration)) return
        if (initialPlatform == TaskPlatform.APP) {
            callback(TaskMissionPlanner.merge(null, mainMissions))
            return
        }
        if (!account.hasAppToken()) {
            callback(TaskMissionPlanner.merge(mainMissions, null))
            return
        }
        IlifeApi.missionLstWithToken(account.appToken, TaskPlatform.APP) { appJson, error ->
            onMain {
                if (!isActive(runGeneration)) return@onMain
                var appMissions: JSONArray? = null
                if (appJson != null && appJson.optInt("code", -999) == 0) {
                    appMissions = appJson.optJSONObject("data")?.optJSONArray("missions")
                    appendTaskLog("  官方 App 任务数: ${appMissions?.length() ?: 0}，已合并去重")
                } else {
                    hadFailures = true
                    recordTokenFailure(account, TaskPlatform.APP, account.appToken, appJson)
                    appendTaskLog("  [$account] 官方 App 任务获取失败：${TaskProtocol.failure(appJson, error, account.appToken)}，继续执行支付宝任务")
                    TaskRunRepository.updateLane(
                        laneId = laneId,
                        currentTask = "App 任务获取失败，继续运行",
                        phase = TaskLanePhase.LOADING_MISSIONS
                    )
                }
                callback(TaskMissionPlanner.merge(mainMissions, appMissions))
            }
        }
    }

    private fun runMissionItems(
        account: Account,
        work: List<MissionWorkItem>,
        index: Int,
        gained: Int,
        laneId: Int,
        runGeneration: Int,
        rateLimitRetries: Int = 0,
        done: (Int) -> Unit
    ) {
        if (!isActive(runGeneration)) return
        if (index >= work.size) {
            done(gained)
            return
        }

        val item = work[index]
        val round = if (item.total > 1) " (${item.round}/${item.total})" else ""
        val notificationRound = if (item.total > 1) "（${item.round}/${item.total}）" else ""
        val platformName = if (item.platform == TaskPlatform.APP) "[APP]" else "[支付宝]"
        val token = if (item.platform == TaskPlatform.APP && account.hasAppToken()) {
            account.appToken
        } else {
            account.token
        }
        if (!canUseToken(account, item.platform, token)) {
            runMissionItems(account, work, index + 1, gained, laneId, runGeneration, done = done)
            return
        }
        TaskRunRepository.updateLane(
            laneId = laneId,
            currentTask = "$platformName ${item.name}$notificationRound",
            phase = TaskLanePhase.RUNNING_MISSION
        )
        appendTaskLog("  [${index + 1}/${work.size}] $platformName ${item.name} +${item.score}分$round")
        val delay = taskRateLimiter.reserveDelayMillis(token, System.currentTimeMillis())
        postDelayed(delay, runGeneration) {
            if (!canUseToken(account, item.platform, token)) {
                runMissionItems(account, work, index + 1, gained, laneId, runGeneration, done = done)
                return@postDelayed
            }
            IlifeApi.scoreSendWithToken(token, account.uid, item.refId, item.score, item.platform) { json, err ->
                onMain {
                    if (!isActive(runGeneration)) return@onMain
                    val nextGained = when {
                        json == null -> {
                            hadFailures = true
                            appendTaskLog(
                                "    [$account] $platformName ${item.name}$round：" +
                                    "网络错误 ${err ?: "未知错误"}"
                            )
                            gained
                        }
                        json.optInt("code", -999) == 0 -> {
                            appendTaskLog(
                                "    [$account] $platformName ${item.name}$round：" +
                                    "成功 +${item.score}分"
                            )
                            TaskRunRepository.addPoints(laneId, item.score)
                            gained + item.score
                        }
                        json.optInt("code", -999) == -98 -> {
                            if (rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) {
                                // 无限重试同一项会让通道永远完不了，finishRun 也就永远不来，
                                // 仓库的 running 卡死，之后所有启动都被判为「已在运行」。
                                hadFailures = true
                                appendTaskLog(
                                    "    [$account] $platformName ${item.name}$round：" +
                                        "连续 $MAX_RATE_LIMIT_RETRIES 次受限，跳过这一项"
                                )
                                gained
                            } else {
                                appendTaskLog(
                                    "    [$account] $platformName ${item.name}$round：" +
                                        "请求过于频繁，60秒后重试" +
                                        "（第 ${rateLimitRetries + 1}/$MAX_RATE_LIMIT_RETRIES 次）"
                                )
                                TaskRunRepository.updateLane(
                                    laneId = laneId,
                                    currentTask = "${item.name}受限，等待重试",
                                    phase = TaskLanePhase.WAITING
                                )
                                postDelayed(RETRY_DELAY_MILLIS, runGeneration) {
                                    runMissionItems(
                                        account,
                                        work,
                                        index,
                                        gained,
                                        laneId,
                                        runGeneration,
                                        rateLimitRetries + 1,
                                        done
                                    )
                                }
                                return@onMain
                            }
                        }
                        else -> {
                            hadFailures = true
                            recordTokenFailure(account, item.platform, token, json)
                            appendTaskLog(
                                "    [$account] $platformName ${item.name}$round：" +
                                    "失败 ${TaskProtocol.failure(json, err, token)}"
                            )
                            gained
                        }
                    }
                    if (index + 1 >= work.size) {
                        // 最后一项后面没有请求要限速，直接收工；
                        // 空等 MISSION_GAP 会让「正在收尾」白白停留 30 秒。
                        done(nextGained)
                        return@onMain
                    }
                    TaskRunRepository.updateLane(
                        laneId = laneId,
                        currentTask = "等待下一项任务",
                        phase = TaskLanePhase.WAITING
                    )
                    postDelayed(MISSION_GAP_MILLIS, runGeneration) {
                        runMissionItems(
                            account,
                            work,
                            index + 1,
                            nextGained,
                            laneId,
                            runGeneration,
                            0,
                            done
                        )
                    }
                }
            }
        }
    }

    private fun finishRun() {
        if (!running) return
        releaseWakeLock()
        val gained = TaskRunRepository.state.value.totalGained
        appendTaskLog(
            if (hadFailures) {
                "===== 任务结束，部分未完成，本次获得 $gained 分 ====="
            } else {
                "===== 全部完成，本次获得 $gained 分 ====="
            }
        )
        // 整批都栽在网络上的话，别等明天：+15/+30/+60 分钟各补跑一次（最多 3 次）。
        // 只要这批没有网络失败，就把之前的补跑计划取消掉。
        if (networkFailures) {
            AutoTasks.scheduleCatchUp(this)
            // 闹钟只是兜底：网络一恢复就立刻重试，不必等到 +15 分钟。
            AutoTasks.armNetworkRecovery(this)
        } else {
            AutoTasks.clearCatchUp(this)
            AutoTasks.disarmNetworkRecovery(this)
        }
        TaskRunRepository.finish(partial = hadFailures)
        taskLease?.let(TaskExecutionCoordinator::release)
        taskLease = null
        running = false
        stoppingNormally = true
        mainHandler.removeCallbacks(stallWatchdog)
        notifyTaskResult(TaskRunRepository.state.value)
        WidgetSupport.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    private fun softCancel(message: String) {
        if (!running) return
        releaseWakeLock()
        generation += 1
        running = false
        stoppingNormally = true
        mainHandler.removeCallbacksAndMessages(null)
        taskLease?.let(TaskExecutionCoordinator::releaseAfterInFlightNetworkGrace)
        taskLease = null
        TaskRunRepository.cancel(message)
        notifyTaskResult(TaskRunRepository.state.value)
        WidgetSupport.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    private fun releaseWakeLock() {
        mainHandler.removeCallbacks(batchTimeout)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun recordTokenFailure(account: Account, platform: TaskPlatform, token: String, json: JSONObject?) {
        if (json?.optInt("code", -999) != -99) return
        expiredTokens += token
        // 按上游模型：不自动删除登录信息，只记录状态，由账户页/首页展示「需重新验证」。
        // 误判（网关 401、签名跨桶、服务端抖动）不再把用户踢成未登录。
        LoginStatusStore.record(account, platform == TaskPlatform.APP, TokenValidity.EXPIRED)
    }

    private fun canUseToken(account: Account, platform: TaskPlatform, token: String): Boolean {
        if (token in expiredTokens) return false
        val latest = AccountStore.get(this, account.phone)
        if (latest != null && TaskProtocol.token(latest, platform) == token) return true
        expiredTokens += token
        hadFailures = true
        appendTaskLog("  [$account][$platform] 登录信息已变更，停止使用旧凭据，请重新运行任务")
        return false
    }

    private fun isActive(runGeneration: Int): Boolean =
        running && generation == runGeneration && TaskRunRepository.state.value.running

    private fun appendTaskLog(line: String) {
        TaskRunRepository.appendLog(line)
        updateNotification(TaskRunRepository.state.value)
    }

    private fun postDelayed(delayMillis: Long, runGeneration: Int, action: () -> Unit) {
        if (!isActive(runGeneration)) return
        mainHandler.postDelayed(
            {
                if (isActive(runGeneration)) action()
            },
            delayMillis.coerceAtLeast(0L)
        )
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun parseTodayDoneCount(scoreJson: JSONObject?): Map<String, Int> {
        if (scoreJson == null || scoreJson.optInt("code", -999) != 0) return emptyMap()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val out = mutableMapOf<String, Int>()
        val items = scoreJson.optJSONArray("data") ?: return out
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optLong("ctime", 0L) < start) continue
            val adId = item.optJSONObject("data")?.optString("adId", "").orEmpty()
            if (adId.isNotBlank()) out[adId] = (out[adId] ?: 0) + 1
        }
        return out
    }

    private fun createNotificationChannel() {
        AppNotifications.ensureChannels(this)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AppNotifications.TASK_PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AppNotifications.TASK_PROGRESS_ID, notification)
        }
    }

    private fun updateNotification(state: TaskRunState) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(AppNotifications.TASK_PROGRESS_ID, buildNotification(state))
        } catch (_: SecurityException) {
            // Android 13+ 用户可关闭普通通知；前台服务本身仍由系统任务管理器展示。
        }
    }

    /**
     * 结束时另发一条结果通知，而不是把进度通知原地改文字。
     *
     * 进度通知挂在前台服务上、常驻且带不确定进度条；就地改文字在部分系统上
     * 仍然显示成「执行中」的样子，也划不掉。接水那套一直是进度、结果分两条，
     * 这里对齐它：进度条随 [stopForeground] 一起 REMOVE 掉，结果单独发。
     */
    private fun notifyTaskResult(state: TaskRunState) {
        if (state.running) return
        val content = TaskNotificationTextFormatter.format(state)
        try {
            getSystemService(NotificationManager::class.java)
                .notify(
                    AppNotifications.TASK_RESULT_ID,
                    AppNotifications.taskResult(this, content.title, content.text)
                )
        } catch (_: SecurityException) {
            // Android 13+ 用户可拒绝通知权限，运行记录里仍有完整日志。
        }
    }

    /**
     * 让桌面小部件跟上运行进度。
     *
     * 每条日志都会推一次状态，逐条广播太密；结束时的那次刷新在 [finishRun] 里
     * 无条件执行，不走这里，所以不会被节流吞掉。
     */
    private fun throttledWidgetRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWidgetRefreshAt < WIDGET_REFRESH_INTERVAL_MILLIS) return
        lastWidgetRefreshAt = now
        WidgetSupport.refreshAll(this)
    }

    private fun buildNotification(state: TaskRunState): Notification {
        val content = TaskNotificationTextFormatter.format(state)
        val builder = Notification.Builder(this, AppNotifications.CHANNEL_TASK_PROGRESS)
            .setSmallIcon(com.water.widget.R.drawable.ic_water_drop)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setContentIntent(AppNotifications.openApp(this, false, AppNotifications.TASK_PROGRESS_ID))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setAutoCancel(!state.running)
            .setProgress(0, 0, state.running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    enum class StartResult {
        STARTED,
        ALREADY_RUNNING,
        NO_ACCOUNTS,
        RISK_NOT_CONFIRMED,
        FAILED
    }

    companion object {
        const val ACTION_RUN_TASKS = "com.water.widget.action.RUN_TASKS"
        const val ACTION_STOP_TASKS = "com.water.widget.action.STOP_TASKS"
        private const val ACCOUNT_GAP_MILLIS = 1_200L
        private const val MISSION_GAP_MILLIS = 30_000L
        private const val RETRY_DELAY_MILLIS = 60_000L
        private const val MAX_RATE_LIMIT_RETRIES = 3
        private const val STALL_TIMEOUT_MILLIS = 4 * 60_000L
        private const val MAX_BATCH_MILLIS = 60 * 60_000L
        private const val WIDGET_REFRESH_INTERVAL_MILLIS = 3_000L

        /** 任务列表 / 今日进度各自独立重试：第一次失败后等 5 秒再试一次，仍失败才降级。 */
        private const val STAGE_RETRY_ATTEMPTS = 2
        private const val STAGE_RETRY_DELAY_MILLIS = 5_000L

        /** 任务列表拿不到时，签到仍按官方默认项尝试（adId 与基础分来自官方客户端）。 */
        private const val FALLBACK_SIGN_IN_AD_ID = "DAILY_CHECK_IN"
        private const val FALLBACK_SIGN_IN_SCORE = 5

        fun start(context: Context): StartResult {
            if (TaskRunRepository.state.value.running) return StartResult.ALREADY_RUNNING
            // 首次使用必须先阅读并确认风险提示与免责声明，确认前不允许启动任务。
            if (!TaskRiskConsent.isConfirmed(context)) return StartResult.RISK_NOT_CONFIRMED
            if (AccountStore.list(context).none { it.hasToken() || it.hasAppToken() }) return StartResult.NO_ACCOUNTS
            return try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, TaskForegroundService::class.java).setAction(ACTION_RUN_TASKS)
                )
                StartResult.STARTED
            } catch (_: RuntimeException) {
                StartResult.FAILED
            }
        }

        /** 请求停止当前批次，没有批次在跑时什么也不做。 */
        fun stop(context: Context) {
            if (!TaskRunRepository.state.value.running) return
            try {
                // 批次在跑说明前台服务在，应用算前台，普通 startService 不受后台启动限制
                context.startService(
                    Intent(context, TaskForegroundService::class.java).setAction(ACTION_STOP_TASKS)
                )
            } catch (_: RuntimeException) {
                // 真被拦下也只是没停成：批次照常跑，小部件下次刷新回到「运行中」
            }
        }
    }
}
