package com.water.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 等待系统真正具备「可用」网络。
 *
 * 息屏/刚唤醒时，连接状态经常还停在 CONNECTING，或者「连着 Wi-Fi 但没验证通过」，
 * 这时发请求会立刻失败。这里最多等 8 秒。
 *
 * 判定刻意比过去宽松：只要网络具备 INTERNET 且未被系统挂起（NOT_SUSPENDED）就算可用，
 * 不再死等 NET_CAPABILITY_VALIDATED。凌晨 Doze 场景下 VALIDATED 往往要等到系统
 * 唤醒后才有，死等 20 秒既等不到、又会把整批任务拖成「先白等再白重试」。
 * 真正的连通性由请求自己暴露，[com.water.widget.TaskForegroundService] 拿到 false
 * 会直接跳过本批并安排补跑，不再硬撞。
 *
 * 注意：await() 会阻塞调用线程，只在后台任务线程里调用。
 */
object NetReady {
    private const val DEFAULT_TIMEOUT_MS = 8_000L

    fun await(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        if (isUsable(cm)) {
            DiagLog.info("NETREADY already usable")
            return true
        }
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (isUsableCaps(caps)) latch.countDown()
            }

            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && isUsableCaps(caps)) latch.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            cm.registerNetworkCallback(request, callback)
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            DiagLog.info(if (ok) "NETREADY usable" else "NETREADY timeout after " + timeoutMs + "ms")
            ok
        } catch (t: Throwable) {
            // 注册失败（权限/系统异常）就不要卡住任务，按可用处理，交给请求自己失败。
            DiagLog.warn("NETREADY unavailable: " + t.javaClass.simpleName)
            true
        } finally {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
            }
        }
    }

    private fun isUsable(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return isUsableCaps(caps)
    }

    private fun isUsableCaps(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        // 被系统挂起的网络（Doze/休眠）即使挂着 INTERNET 也发不出去
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }
}
