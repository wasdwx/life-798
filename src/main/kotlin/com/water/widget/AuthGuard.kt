package com.water.widget

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 登录态失效判定守卫。
 *
 * 背景：客户端原先在**第一次**收到 code == -99 时就永久删除本地 token
 * （TaskForegroundService.recordTokenFailure / ConfigActivity / ScoreActivity）。
 * 但 -99 并不等于"登录真的过期"：
 *   - IlifeApi 会把**任何 HTTP 401** 无差别合成成 -99（网关、代理、风控、VPN 都可能回 401）
 *   - 签名使用 30 秒时间桶，跨桶或时钟漂移会被服务端拒绝
 *   - 服务端瞬时抖动 / 息屏期间网络异常
 * 一次误判就把用户从已登录踢成未登录，恢复成本是重新走"图形码 + 短信码"。
 *
 * 现在的策略：**先复核，再判定**。
 *   1. 收到 -99 时不立刻删 token；
 *   2. 用同一 token + 同一身份头打一次**只读**接口（/acc/view-info）复核；
 *   3. 复核成功 -> 判定为误判，保留 token；
 *   4. 复核失败 -> 记一次确认，连续 CONFIRM_THRESHOLD 次才判定失效并清除。
 *
 * 复核只打读接口，绝不重试设备控制类请求（与 IlifeApi "Never retry a control request" 一致）。
 */
object AuthGuard {
    private const val TAG = "WaterAuthGuard"
    private const val CONFIRM_THRESHOLD = 2
    private const val REPROBE_THROTTLE_MS = 20_000L

    enum class Verdict { RECOVERED, SUSPECT, CONFIRMED }

    private val confirmCount = ConcurrentHashMap<String, Int>()
    private val lastProbeAt = ConcurrentHashMap<String, Long>()

    private fun note(text: String, level: DiagLevel = DiagLevel.INFO) {
        Log.println(if (level == DiagLevel.WARN) Log.WARN else Log.DEBUG, TAG, text)
        DiagLog.log(level, "AUTHGUARD " + text)
    }

    /**
     * 收到 -99 时的统一入口。
     * @param source 调用来源（task/config/score），只用于日志
     * @param onVerdict 在**后台线程**回调，调用方如需更新 UI 请自行切主线程
     */
    fun handleAuthFailure(
        context: Context,
        phone: String,
        platform: TaskPlatform,
        token: String,
        source: String,
        onVerdict: (Verdict) -> Unit = {},
    ) {
        if (token.isBlank()) {
            onVerdict(Verdict.CONFIRMED)
            return
        }

        val now = System.currentTimeMillis()
        val last = lastProbeAt[token] ?: 0L
        if (now - last < REPROBE_THROTTLE_MS) {
            note("[" + source + "] 复核过于频繁（" + (now - last) + "ms），本次跳过，暂不删除")
            onVerdict(Verdict.SUSPECT)
            return
        }
        lastProbeAt[token] = now

        val appType = TaskProtocol.appType(platform)
        val version = TaskProtocol.version(platform)
        note("[" + source + "] 收到 -99，开始复核（platform=" + platform + " appType=" + appType + " version=" + version + "）")

        IlifeApi.viewInfoWithToken(token, appType, version) { json, err ->
            val code = json?.optInt("code", -999) ?: -999
            if (json != null && code == 0) {
                confirmCount.remove(token)
                note("[" + source + "] 复核成功 -> 判定为误判，**保留**登录信息")
                onVerdict(Verdict.RECOVERED)
            } else {
                val n = (confirmCount[token] ?: 0) + 1
                confirmCount[token] = n
                if (n >= CONFIRM_THRESHOLD) {
                    note("[" + source + "] 复核失败第 " + n + " 次（code=" + code + " err=" + err + "）-> 确认失效，清除登录信息", DiagLevel.WARN)
                    AccountStore.invalidateToken(context, phone, token, platform == TaskPlatform.APP)
                    onVerdict(Verdict.CONFIRMED)
                } else {
                    note("[" + source + "] 复核失败第 " + n + " 次（code=" + code + " err=" + err + "）-> 疑似误判，**暂不删除**")
                    onVerdict(Verdict.SUSPECT)
                }
            }
        }
    }
}
