package com.water.widget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.water.widget.WaterEstimate
import com.water.widget.WaterUsageSettings

/**
 * 「用水量估算」设置卡片，沿用本地 SectionCard 风格（上游版本依赖首页私有的 SettingsRow）。
 * 设置写入 WaterUsageSettings，首页、消费统计与完成通知都按同一比例估算。
 */
@Composable
internal fun WaterUsageSettingsCard() {
    val context = LocalContext.current
    val revision by WaterUsageSettings.changes.collectAsState()
    val estimate = remember(context, revision) { WaterUsageSettings.read(context) }
    var show by rememberSaveable { mutableStateOf(false) }
    val amount = estimate.amountYuan.toBigDecimal().stripTrailingZeros().toPlainString()
    val volume = estimate.volumeMl.toBigDecimal().stripTrailingZeros().toPlainString()
    SectionCard(
        "用水量估算",
        "统一比例：¥$amount 对应 $volume ml。关闭后首页、消费统计和完成通知都不再显示预估水量。"
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { show = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.WaterDrop,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("预估水量设置", fontWeight = FontWeight.SemiBold)
                Text(
                    if (estimate.visible) "¥$amount 对应 $volume ml · 统一比例" else "已隐藏预估水量",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("修改", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        }
    }
    if (show) WaterUsageSettingsDialog(
        estimate,
        onSave = {
            WaterUsageSettings.save(context, it)
            show = false
        },
        onDismiss = { show = false })
}

@Composable
internal fun WaterUsageSettingsDialog(
    estimate: WaterEstimate,
    onSave: (WaterEstimate) -> Unit,
    onDismiss: () -> Unit
) {
    var visible by rememberSaveable { mutableStateOf(estimate.visible) }
    var amountText by rememberSaveable {
        mutableStateOf(estimate.amountYuan.toBigDecimal().stripTrailingZeros().toPlainString())
    }
    var volumeText by rememberSaveable {
        mutableStateOf(estimate.volumeMl.toBigDecimal().stripTrailingZeros().toPlainString())
    }
    val amount = amountText.toDoubleOrNull()
    val volume = volumeText.toDoubleOrNull()
    val amountValid = amount != null && amount.isFinite() && amount > 0
    val volumeValid = volume != null && volume.isFinite() && volume > 0
    val ratioValid = amountValid && volumeValid && (volume / amount).let { it.isFinite() && it > 0 }
    AlertDialog(
        modifier = Modifier.imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text("预估水量设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.toggleable(value = visible, role = Role.Switch, onValueChange = { visible = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("显示预估水量", Modifier.weight(1f))
                    Switch(checked = visible, onCheckedChange = null)
                }
                Text("金额与水量的换算比例", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    isError = !amountValid,
                    supportingText = if (!amountValid) { { Text("请输入大于 0 的有效金额") } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = volumeText,
                    onValueChange = { volumeText = it },
                    label = { Text("对应水量（ml）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    isError = !volumeValid,
                    supportingText = if (!volumeValid) { { Text("请输入大于 0 的有效水量") } } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (amountValid && volumeValid && !ratioValid) {
                    Text("换算比例过大或过小，请调整金额或水量。", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { amountText = "0.16"; volumeText = "500" }) {
                    Text("恢复默认比例")
                }
                Text("适用于全部账号和设备，仅影响首页、消费统计和后续完成通知中的预估水量，不改变实际消费金额。历史水量也按当前比例重新估算。冷热水等单价不同的设备混用时，建议关闭显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = ratioValid, onClick = {
                onSave(WaterEstimate(visible, amount!!, volume!!))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
