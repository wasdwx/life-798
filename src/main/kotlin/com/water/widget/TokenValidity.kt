package com.water.widget

import org.json.JSONObject

enum class TokenValidity(val label: String) {
    UNCHECKED("未检测"),
    CHECKING("检测中…"),
    VALID("有效"),
    EXPIRED("需重新验证"),
    FAILED("检测失败，请重试");

    companion object {
        fun from(json: JSONObject?): TokenValidity = when (json?.optInt("code", -999)) {
            0 -> VALID
            -99 -> EXPIRED
            else -> FAILED
        }

        fun expiredMessage(json: JSONObject?, useApp: Boolean): String? =
            if (from(json) == EXPIRED) {
                (if (useApp) "用水登录（App）" else "积分登录（支付宝）") +
                    "未通过验证，请先检查登录状态，仍无法使用时再重新登录"
            } else null
    }
}
