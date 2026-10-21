package com.water.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 等待系统真正具备「可用」网络（NET_CAPABILITY_VALIDATED）。
 *
 * 息屏/刚唤醒时，连接状态经常还停在 CONNECTING，或者「连着 Wi-Fi 但没验证通过」，
 * 这时发请求会立刻失败，整批任务就此报废。这里最多等 20 秒。
 *
 * 注意：await() 会阻塞调用线程，只在后台任务线程里调用。
 */
object NetReady {
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    fun await(context: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        if (isValidated(cm)) {
            DiagLog.info("NETREADY already validated")
            return true
        }
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) latch.countDown()
            }

            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    latch.countDown()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            cm.registerNetworkCallback(request, callback)
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            DiagLog.info(if (ok) "NETREADY validated" else "NETREADY timeout after ${timeoutMs}ms")
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

    private fun isValidated(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
