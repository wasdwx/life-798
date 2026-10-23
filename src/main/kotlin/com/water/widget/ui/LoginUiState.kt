package com.water.widget.ui

enum class LoginPlatform(val title: String, val description: String, val actionLabel: String) {
    APP("用水登录", "接水、钱包和 App 积分任务需要此项登录", "完成用水登录"),
    ALIPAY("积分登录", "可选：领取支付宝积分任务，获得更多积分", "完成积分登录")
}

data class LoginUiState(
    val platform: LoginPlatform,
    val platformTitle: String,
    val platformDescription: String,
    val actionLabel: String,
    val nextHint: String,
    val canLoadCaptcha: Boolean,
    val canSendSms: Boolean,
    val canLogin: Boolean
)

object LoginUiStateFactory {
    fun from(
        platform: LoginPlatform,
        captchaLoaded: Boolean,
        smsSent: Boolean,
        phone: String,
        graphCode: String,
        smsCode: String
    ): LoginUiState {
        val nextHint = when {
            !captchaLoaded -> "输入手机号并刷新图形验证码"
            !smsSent -> "填写图形验证码并获取短信"
            else -> "填写短信验证码"
        }

        return LoginUiState(
            platform = platform,
            platformTitle = platform.title,
            platformDescription = platform.description,
            actionLabel = platform.actionLabel,
            nextHint = nextHint,
            canLoadCaptcha = phone.matches(Regex("^1\\d{10}$")),
            canSendSms = captchaLoaded && graphCode.isNotBlank(),
            canLogin = smsSent && smsCode.isNotBlank()
        )
    }
}
