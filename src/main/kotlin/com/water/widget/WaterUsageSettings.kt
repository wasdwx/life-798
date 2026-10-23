package com.water.widget

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WaterUsageSettings {
    private const val PREFS = "water_usage_settings"
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()

    fun read(context: Context): WaterEstimate {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WaterEstimate(
            visible = prefs.getBoolean("visible", true),
            amountYuan = prefs.getString("amount_yuan", "0.16")!!.toDouble(),
            volumeMl = prefs.getString("volume_ml", "500.0")!!.toDouble()
        )
    }

    fun save(context: Context, estimate: WaterEstimate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("visible", estimate.visible)
            putString("amount_yuan", estimate.amountYuan.toString())
            putString("volume_ml", estimate.volumeMl.toString())
        }
        revision.value += 1
    }
}
