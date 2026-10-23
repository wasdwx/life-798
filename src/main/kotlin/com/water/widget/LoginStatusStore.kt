package com.water.widget

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object LoginStatusStore {
    private data class CheckedLogin(val token: String?, val status: TokenValidity)
    // 写入方既有主线程（账户页）也有后台线程（任务服务），用并发容器避免读写竞争。
    private val states = ConcurrentHashMap<Pair<String, Boolean>, CheckedLogin>()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun status(account: Account, app: Boolean): TokenValidity {
        val token = if (app) account.appToken else account.token
        return states[account.phone to app]?.takeIf { it.token == token }?.status ?: TokenValidity.UNCHECKED
    }

    fun record(account: Account, app: Boolean, status: TokenValidity) {
        val token = if (app) account.appToken else account.token
        states[account.phone to app] = CheckedLogin(token, status)
        revision.value += 1
    }
}
