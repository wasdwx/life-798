package com.water.widget.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.water.widget.AppUpdates
import com.water.widget.AutoTasks
import com.water.widget.DiagLog
import com.water.widget.UpdatePolicy
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动积分任务") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("启用自动任务", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { value ->
                        AutoTasks.configure(context, value, minute)
                        enabled = value
                        if (value && !notificationsAllowed) requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    })
                }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, hour, minuteOfHour ->
                        minute = hour * 60 + minuteOfHour
                        AutoTasks.configure(context, enabled, minute)
                    }, minute / 60, minute % 60, true).show()
                }) { Text("执行时间：%02d:%02d".format(minute / 60, minute % 60)) }
                Text(when {
                    !enabled -> "自动任务已关闭"
                    !exactAllowed -> "尚未安排任务：请先允许精确闹钟"
                    else -> "下次执行：" + AutoTasks.nextRun(ZonedDateTime.now(), minute)
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!exactAllowed) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                }) { Text("允许精确闹钟") }
                if (!batteryExempt) TextButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    )
                }) { Text("允许后台运行（电池优化设置）") }
                if (!notificationsAllowed) TextButton(onClick = {
                    requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("允许任务通知") }
                Text("部分系统仍可能限制后台运行。任务最长运行一小时；可随时从任务页停止。",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/** 自动检查更新弹窗：检查频率与是否包含预发布。 */
@Composable
internal fun UpdateSettingsDialog(onCheckUpdate: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var policy by remember { mutableStateOf(AppUpdates.policy(context)) }
    var previews by remember { mutableStateOf(AppUpdates.includePrereleases(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动检查更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UpdatePolicy.entries.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = policy == item, onClick = {
                            policy = item
                            AppUpdates.prefs(context).edit().putString("policy", item.name).apply()
                        })
                        Text(item.label)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("包含预发布版本", Modifier.weight(1f))
                    Switch(checked = previews, onCheckedChange = { value ->
                        previews = value
                        AppUpdates.prefs(context).edit().putBoolean("prereleases", value).apply()
                        onCheckUpdate()
                    })
                }
                Text("草稿和没有就绪 APK 的发布不会作为更新。", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCheckUpdate) { Text("立即检查") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("记录诊断日志", Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { value ->
                        DiagLog.setEnabled(context, value)
                        enabled = value
                        size = DiagLog.totalBytes()
                    })
                }
                if (enabled) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("详细模式（记录脱敏后的请求/响应摘要）", Modifier.weight(1f))
                        Switch(checked = verbose, onCheckedChange = { value ->
                            DiagLog.setVerbose(context, value)
                            verbose = value
                        })
                    }
                    Text("已用 " + formatBytes(size) + "；超过上限会自动滚动，异常单独保留。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { export(false) }) { Text("导出日志") }
                        TextButton(onClick = { export(true) }) { Text("只导出异常") }
                        TextButton(onClick = {
                            DiagLog.clear()
                            size = 0L
                            Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                        }) { Text("清空") }
                    }
                } else {
                    Text("关闭时不会写入任何内容。", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> bytes.toString() + " B"
}
