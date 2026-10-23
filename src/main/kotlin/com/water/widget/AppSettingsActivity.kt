package com.water.widget

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.water.widget.ui.SectionCard
import com.water.widget.ui.WaterUsageSettingsCard
import com.water.widget.ui.WaterTheme
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime

class AppSettingsActivity : ComponentActivity() {
    private val updates: AppUpdateViewModel by viewModels()
    private var autoEnabled by mutableStateOf(false)
    private var minute by mutableIntStateOf(540)
    private var exactAllowed by mutableStateOf(false)
    private var batteryExempt by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(false)
    private var policy by mutableStateOf(UpdatePolicy.DAILY)
    private var previews by mutableStateOf(false)
    private var diagEnabled by mutableStateOf(false)
    private var diagVerbose by mutableStateOf(false)
    private var diagSize by mutableLongStateOf(0L)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        reloadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        reloadSettings()
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                val update by updates.state.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding().verticalScroll(rememberScrollState())
                    .padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton(onClick = { finish() }) { Text("返回") }
                    Text("任务与更新", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    SectionCard("每日自动积分任务") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("启用自动任务", Modifier.weight(1f))
                            Switch(checked = autoEnabled, onCheckedChange = { enabled ->
                                AutoTasks.configure(this@AppSettingsActivity, enabled, minute)
                                autoEnabled = enabled
                                if (enabled && !notificationsAllowed) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            })
                        }
                        TextButton(onClick = {
                            TimePickerDialog(this@AppSettingsActivity, { _, hour, minuteOfHour ->
                                minute = hour * 60 + minuteOfHour
                                AutoTasks.configure(this@AppSettingsActivity, autoEnabled, minute)
                            }, minute / 60, minute % 60, true).show()
                        }) { Text("执行时间：%02d:%02d".format(minute / 60, minute % 60)) }
                        Text(when {
                            !autoEnabled -> "自动任务已关闭"
                            !exactAllowed -> "尚未安排任务：请先允许精确闹钟"
                            else -> "下次执行：" + AutoTasks.nextRun(ZonedDateTime.now(), minute)
                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!exactAllowed) TextButton(onClick = { openSystemSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }) {
                            Text("允许精确闹钟")
                        }
                        if (!batteryExempt) TextButton(onClick = { openSystemSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }) {
                            Text("允许后台运行（电池优化设置）")
                        }
                        if (!notificationsAllowed) TextButton(onClick = { requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("允许任务通知")
                        }
                        Text("部分系统仍可能限制后台运行。任务最长运行一小时；可随时从任务页停止。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SectionCard("诊断日志") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("记录诊断日志", Modifier.weight(1f))
                            Switch(checked = diagEnabled, onCheckedChange = { value ->
                                DiagLog.setEnabled(this@AppSettingsActivity, value)
                                diagEnabled = value
                                diagSize = DiagLog.totalBytes()
                            })
                        }
                        if (diagEnabled) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("详细模式（记录脱敏后的请求/响应摘要）", Modifier.weight(1f))
                                Switch(checked = diagVerbose, onCheckedChange = { value ->
                                    DiagLog.setVerbose(this@AppSettingsActivity, value)
                                    diagVerbose = value
                                })
                            }
                            Text("已用 " + formatBytes(diagSize) + "；超过上限会自动滚动，异常单独保留。",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { exportDiagnostics(errorsOnly = false) }) { Text("导出日志") }
                                TextButton(onClick = { exportDiagnostics(errorsOnly = true) }) { Text("只导出异常") }
                                TextButton(onClick = {
                                    DiagLog.clear()
                                    diagSize = 0L
                                    Toast.makeText(this@AppSettingsActivity, "已清空", Toast.LENGTH_SHORT).show()
                                }) { Text("清空") }
                            }
                        } else {
                            Text("关闭时不会写入任何内容。", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    WaterUsageSettingsCard()
                    SectionCard("检查更新") {
                        Text(buildAnnotatedString {
                            append("github仓库：")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) {
                                append(AppUpdates.REPOSITORY_URL.removePrefix("https://github.com/"))
                            }
                        }, modifier = Modifier.clickable { openUrl(AppUpdates.REPOSITORY_URL) })
                        Text(update.message)
                        Button(onClick = { updates.check(manual = true) }, enabled = !update.checking) { Text("检查更新") }
                        update.available?.let { release ->
                            Text(release.notes.ifBlank { "发布说明见 GitHub" }, fontSize = 13.sp)
                            TextButton(onClick = { openUrl(release.url) }) { Text("查看 ${release.tag} 发布页") }
                        }
                        Text("自动检查频率", fontWeight = FontWeight.SemiBold)
                        UpdatePolicy.entries.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = policy == item, onClick = {
                                    policy = item
                                    AppUpdates.prefs(this@AppSettingsActivity).edit().putString("policy", item.name).apply()
                                })
                                Text(item.label)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("包含预发布版本", Modifier.weight(1f))
                            Switch(checked = previews, onCheckedChange = {
                                previews = it
                                AppUpdates.prefs(this@AppSettingsActivity).edit().putBoolean("prereleases", it).apply()
                                updates.check(manual = true)
                            }, enabled = !update.checking)
                        }
                        Text("草稿和没有就绪 APK 的发布不会作为更新。", fontSize = 12.sp)
                        TextButton(onClick = { openUrl("${AppUpdates.REPOSITORY_URL}/releases") }) { Text("查看全部发布") }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadSettings()
        AutoTasks.schedule(this)
    }

    private fun reloadSettings() {
        autoEnabled = AutoTasks.enabled(this)
        minute = AutoTasks.minute(this)
        exactAllowed = AutoTasks.allowed(this)
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        notificationsAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
        policy = AppUpdates.policy(this)
        previews = AppUpdates.includePrereleases(this)
        diagEnabled = DiagLog.isEnabled()
        diagVerbose = DiagLog.isVerbose()
        diagSize = DiagLog.totalBytes()
    }

    private fun exportDiagnostics(errorsOnly: Boolean) {
        val file = DiagLog.exportZip(this, errorsOnly)
        if (file == null) {
            Toast.makeText(this, "导出失败：没有可导出的日志", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WaterWidget 诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "导出诊断日志"))
        } catch (_: RuntimeException) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> bytes.toString() + " B"
    }

    private fun openSystemSettings(action: String) {
        try { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { Toast.makeText(this, "系统不支持此入口，请在应用设置中手动授权", Toast.LENGTH_LONG).show() }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: RuntimeException) { Toast.makeText(this, "没有可打开网页的应用", Toast.LENGTH_SHORT).show() }
    }
}
