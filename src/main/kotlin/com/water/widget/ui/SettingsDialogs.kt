package com.water.widget.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.water.widget.AppUpdates
import com.water.widget.AutoTasks
import com.water.widget.DiagLog
import com.water.widget.UpdatePolicy
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 统一的设置弹窗外框：高度贴合内容，底部操作按钮紧跟内容，不出现大片空白。 */
@Composable
private fun SettingsDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 420.dp).wrapContentHeight()
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    content = content
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

/** 弹窗内统一的「标题 + 说明 + 开关」行。 */
@Composable
private fun DialogSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 自动积分任务设置弹窗：开关、执行时间、权限状态。 */
@Composable
internal fun AutoTaskSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutoTasks.enabled(context)) }
    var minute by remember { mutableIntStateOf(AutoTasks.minute(context)) }
    var exactAllowed by remember { mutableStateOf(AutoTasks.allowed(context)) }
    var batteryExempt by remember {
        mutableStateOf(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
    }
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    SettingsDialogFrame("自动积分任务", onDismiss) {
        DialogSwitchRow("启用自动任务", "到点自动运行积分任务", enabled) { value ->
            AutoTasks.configure(context, value, minute)
            enabled = value
            if (value && !notificationsAllowed) requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("执行时间", Modifier.weight(1f), fontSize = 15.sp)
            TextButton(onClick = {
                TimePickerDialog(context, { _, hour, minuteOfHour ->
                    minute = hour * 60 + minuteOfHour
                    AutoTasks.configure(context, enabled, minute)
                }, minute / 60, minute % 60, true).show()
            }) { Text("%02d:%02d".format(minute / 60, minute % 60)) }
        }
        Text(
            when {
                !enabled -> "自动任务已关闭"
                !exactAllowed -> "尚未安排任务：请先允许精确闹钟"
                else -> "下次执行：" + AutoTasks.nextRun(ZonedDateTime.now(), minute)
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!exactAllowed || !batteryExempt || !notificationsAllowed) {
            HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (!exactAllowed) TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }) { Text("允许精确闹钟") }
            if (!batteryExempt) TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                )
            }) { Text("允许后台运行") }
            if (!notificationsAllowed) TextButton(onClick = {
                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("允许任务通知") }
        }
        Text("部分系统仍可能限制后台运行；任务最长运行一小时，可随时从任务页停止。",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

/** 自动检查更新弹窗：检查频率与是否包含预发布。 */
@Composable
internal fun UpdateSettingsDialog(onCheckUpdate: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var policy by remember { mutableStateOf(AppUpdates.policy(context)) }
    var previews by remember { mutableStateOf(AppUpdates.includePrereleases(context)) }
    SettingsDialogFrame("自动检查更新", onDismiss) {
        Text("检查频率", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        UpdatePolicy.entries.forEach { item ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        policy = item
                        AppUpdates.prefs(context).edit().putString("policy", item.name).apply()
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = policy == item, onClick = null)
                Text(item.label, Modifier.padding(start = 8.dp), fontSize = 15.sp)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        DialogSwitchRow("包含预发布版本", "草稿和没有就绪 APK 的发布不会作为更新", previews) { value ->
            previews = value
            AppUpdates.prefs(context).edit().putBoolean("prereleases", value).apply()
            onCheckUpdate()
        }
        TextButton(onClick = onCheckUpdate) { Text("立即检查") }
    }
}

/** 诊断日志弹窗：开关、详细模式、导出与清空。 */
@Composable
internal fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(DiagLog.isEnabled()) }
    var verbose by remember { mutableStateOf(DiagLog.isVerbose()) }
    var size by remember { mutableLongStateOf(DiagLog.totalBytes()) }
    fun export(errorsOnly: Boolean) {
        val file = DiagLog.exportZip(context, errorsOnly)
        if (file == null) {
            Toast.makeText(context, "导出失败：没有可导出的日志", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WaterWidget 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "导出诊断日志"))
        } catch (_: RuntimeException) {
            Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }
    SettingsDialogFrame("诊断日志", onDismiss) {
        DialogSwitchRow("记录诊断日志", "关闭时不会写入任何内容", enabled) { value ->
            DiagLog.setEnabled(context, value)
            enabled = value
            size = DiagLog.totalBytes()
        }
        if (enabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DialogSwitchRow("详细模式", "记录脱敏后的请求/响应摘要", verbose) { value ->
                DiagLog.setVerbose(context, value)
                verbose = value
            }
            Text(
                "已用 " + formatBytes(size) + "，超过上限自动滚动，异常单独保留。",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { export(false) }) { Text("导出日志") }
                TextButton(onClick = { export(true) }) { Text("只导出异常") }
                TextButton(
                    onClick = {
                        DiagLog.clear()
                        size = 0L
                        Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清空") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
