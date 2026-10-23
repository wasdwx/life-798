package com.water.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun TaskRiskDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("积分任务风险提示", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("运行积分任务可能触发官方平台的限制或风控，请在使用前充分了解以下风险：")
                Text("1. 积分损失：账号积分可能被扣除、清零或冻结，已经获得的积分也可能无法继续使用。")
                Text("2. 无法获取积分：任务可能失败、奖励不到账，或长期无法继续获取积分；提示成功不代表实际到账。")
                Text("3. 账号风控：账号可能被限制登录、限制功能或冻结，具体处理以官方平台为准。")
                Text("请谨慎使用重要账号，优先通过官方渠道完成任务；出现异常时立即停止任务，并联系官方核实。")
                Text("免责声明", fontWeight = FontWeight.Bold)
                Text("本应用为非官方第三方工具，与官方平台无关联。任务是否成功、积分是否发放及账号处理结果均由官方平台决定。开发者不保证收益、积分到账或账号不受限制，也不承诺恢复积分或解除风控。请自行评估风险并自愿使用，因使用本功能产生的相关风险及损失由使用者自行承担；依法不得免除或限制的责任不受本提示影响。")
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("我已阅读并确认") } },
        shape = WaterShapeTokens.prominent
    )
}
