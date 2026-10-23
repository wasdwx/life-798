package com.water.widget

import android.content.Context
import androidx.core.content.edit

object TaskRiskConsent {
    fun isConfirmed(context: Context): Boolean =
        context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE).getBoolean("confirmed", false)

    fun confirm(context: Context) {
        context.getSharedPreferences("task_risk_consent", Context.MODE_PRIVATE).edit {
            putBoolean("confirmed", true)
        }
    }
}
