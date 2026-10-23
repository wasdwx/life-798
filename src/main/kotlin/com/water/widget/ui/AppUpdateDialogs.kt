package com.water.widget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.water.widget.UpdatePolicy
import com.water.widget.UpdateState

/**
 * 自动检查更新频率选择。
 *
 * 上游用 AutoUpdateCheck 枚举，本地对应类型是 UpdatePolicy（OFF / ON_OPEN / DAILY / WEEKLY），
 * 语义与文案一致，这里只做类型映射。
 */
@Composable
fun AutoUpdateSettingsDialog(policy: UpdatePolicy, onSelect: (UpdatePolicy) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自动检查更新") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("打开应用时，按所选频率检查新版本。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdatePolicy.entries.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth().selectable(selected = item == policy,
                    role = Role.RadioButton, onClick = { onSelect(item) }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = item == policy, onClick = null)
                    Text(item.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

/**
 * 检查更新结果。
 *
 * 上游用 AppUpdateUiState，本地对应类型是 UpdateState(checking, message, available)，
 * 其中 message 始终非空（默认「当前版本 vX」），因此只有查到可用版本或确实在报错时才弹窗。
 */
@Composable
fun AppUpdateResultDialog(state: UpdateState, onOpenRelease: (String) -> Unit, onDismiss: () -> Unit) {
    val release = state.available
    if (release != null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("发现新版本 ${release.tag}") },
            text = { Text(release.notes.ifBlank { "前往发布页查看更新内容并下载安装包。" },
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { onOpenRelease(release.url); onDismiss() }) { Text("前往下载") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } })
    } else if (!state.checking && state.message.isNotBlank()) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("检查更新") }, text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
    }
}
