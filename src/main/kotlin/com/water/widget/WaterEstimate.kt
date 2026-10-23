package com.water.widget

import java.util.Locale
import kotlin.math.floor

data class WaterEstimate(
    val visible: Boolean = true,
    val amountYuan: Double = 0.16,
    val volumeMl: Double = 500.0
) {
    fun waterText(scoreEquivalent: Int): String {
        // 先乘比例再除 1000，而不是上游的 score / 1000.0 * ratio：默认比例（0.16 元 / 500 ml，
        // 即 3125 ml/元）下这样才与旧公式 score * 500 / 160 逐字节一致；上游写法会因为
        // 先除 1000 的浮点误差把 72 分算成 220 ml（旧值 230 ml）、2288 分算成 7.1 L（旧值 7.2 L）。
        val millilitres = floor(scoreEquivalent * (volumeMl / amountYuan) / 1000.0)
        return if (millilitres >= 1000) {
            String.format(Locale.CHINA, "%.1f L", millilitres / 1000.0)
        } else {
            String.format(Locale.CHINA, "%.0f ml", floor((millilitres + 5) / 10) * 10)
        }
    }
}
