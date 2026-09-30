package com.water.widget

import org.json.JSONObject

/** Request identity is explicit; a token's text cannot identify its platform. */
object TaskProtocol {
    @JvmStatic fun appType(platform: TaskPlatform) = if (platform == TaskPlatform.APP) "1,1" else "1,5"
    @JvmStatic fun version(platform: TaskPlatform) = if (platform == TaskPlatform.APP) "3.1.9" else "2.0.178"
    fun preferredPlatform(account: Account) = if (account.hasToken()) TaskPlatform.MAIN else TaskPlatform.APP
    fun token(account: Account, platform: TaskPlatform): String =
        (if (platform == TaskPlatform.APP) account.appToken else account.token).orEmpty()

    @JvmStatic
    fun missionBody(adId: String, score: Int, platform: TaskPlatform): JSONObject =
        JSONObject().put("adId", adId).put("type", 101).apply {
            if (platform == TaskPlatform.APP) {
                require(score > 0) { "任务积分配置无效" }
                put("addScore", score)
                put("addScoreType", 4)
            }
        }

    @JvmStatic
    fun signInBody(adId: String, weekDay: Int, baseScore: Int, platform: TaskPlatform): JSONObject {
        require(weekDay in 1..7 && baseScore >= 0)
        return JSONObject().put("adId", adId).apply {
            put(if (platform == TaskPlatform.APP) "weekday" else "weekDay", weekDay)
            if (platform == TaskPlatform.APP) {
                put("addScore", baseScore)
                put("addScoreType", 1)
            }
        }
    }

    fun failure(json: JSONObject?, error: String?, token: String): String {
        val code = json?.optInt("code", -999)
        val message = when {
            code == -99 -> "登录已过期，请重新完成该平台登录（code=-99）"
            json != null -> "code=$code " + json.optString("msg", "服务端未说明原因")
            else -> error ?: "网络响应为空"
        }
        return if (token.isBlank()) message else message.replace(token, "[Token]")
    }
}
