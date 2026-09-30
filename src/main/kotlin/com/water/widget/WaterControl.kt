package com.water.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WaterControlPhase(val label: String) {
    STARTING("启动中"), RUNNING("停止设备"), STOPPING("停止中"), SETTLING("结算中")
}

data class WaterControlSession(
    val reservationId: Long,
    val accountKey: String,
    val deviceId: String,
    val phase: WaterControlPhase
) {
    fun canStop(account: String, device: String) =
        accountKey == account && deviceId == device && phase == WaterControlPhase.RUNNING
}

object WaterControl {
    private val mutableState = MutableStateFlow<WaterControlSession?>(null)
    val state = mutableState.asStateFlow()

    @JvmStatic fun current() = state.value
    @JvmStatic fun start(id: Long, account: String, device: String) {
        mutableState.value = WaterControlSession(id, account, device, WaterControlPhase.STARTING)
    }
    @JvmStatic fun setPhase(id: Long, phase: WaterControlPhase) {
        mutableState.update { if (it?.reservationId == id) it.copy(phase = phase) else it }
    }
    @JvmStatic fun clear(id: Long) {
        mutableState.update { if (it?.reservationId == id) null else it }
    }
}
