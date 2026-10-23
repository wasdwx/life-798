package com.water.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.water.widget.ui.AccountCardState
import com.water.widget.ui.AccountsScreen
import com.water.widget.ui.TextEntryDialog
import com.water.widget.ui.WaterTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch

class AccountsActivity : ComponentActivity() {
    private var accounts by mutableStateOf<List<AccountCardState>>(emptyList())
    private var dialog by mutableStateOf<DialogState?>(null)
    /** 每个 (手机号, 是否 App) 最近一次检测请求的 token 与状态，用于丢弃过期响应。 */
    private data class CheckedLogin(val token: String, val status: TokenValidity)
    private val loginStates = mutableMapOf<Pair<String, Boolean>, CheckedLogin>()
    /** 手动导入请求代号：并发导入或期间账户被改动时，丢弃过期结果。 */
    private val pendingImports = mutableMapOf<String, Any>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UI.applySystemBarAppearance(this, ThemeSettings.isDark(this))
        refresh()
        setContent {
            WaterTheme(mode = ThemeSettings.mode(this)) {
                val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (it.resultCode == RESULT_OK) toast("登录已保存；同一手机号的两项状态会显示在同一账户中")
                    refresh()
                }
                AccountsScreen(
                    accounts = accounts,
                    onSmsLogin = { login.launch(Intent(this, LoginActivity::class.java)) },
                    onAddToken = { dialog = DialogState.AddToken },
                    onSetCurrent = { AccountStore.setCurrent(this, it); refresh(); toast("已切换当前账户") },
                    onSetAppToken = { phone -> dialog = DialogState.SetAppToken(phone) },
                    onRevealToken = { phone, app -> dialog = DialogState.Reveal(phone, app) },
                    onDelete = { phone -> dialog = DialogState.Delete(phone) },
                    onCheckLogin = { phone -> AccountStore.get(this, phone)?.let(::checkLogins) },
                    onRelogin = { phone, app -> login.launch(LoginActivity.reloginIntent(this, phone, app)) }
                )
                RenderDialog()
            }
        }
        // 登录态是内存态：检测结果变化时重绘卡片，进程重启即回「未检测」。
        lifecycleScope.launch { LoginStatusStore.changes.collect { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 回到账户页时自动复核所有账户（只读请求，不会删除任何登录信息）。
        AccountStore.list(this).forEach(::checkLogins)
    }

    @androidx.compose.runtime.Composable
    private fun RenderDialog() {
        when (val state = dialog) {
            DialogState.AddToken -> TextEntryDialog(
                title = "手动添加登录信息",
                message = "用水登录用于接水和钱包，积分登录可稍后补充。保存前会在线核验账户，登录信息属于敏感内容，请勿分享给他人。",
                fields = listOf(
                    "手机号" to "",
                    "积分登录（支付宝，可选）" to "",
                    "用水登录（App，必需）" to ""
                ),
                sensitive = true,
                onDismiss = { dialog = null },
                onConfirm = { dialog = null; addByToken(it) }
            )
            is DialogState.SetAppToken -> {
                val account = AccountStore.get(this, state.phone)
                TextEntryDialog(
                    title = "手动填写用水登录",
                    message = "用水登录为必需项，用于设备启动、App 端积分任务与钱包充值。",
                    fields = listOf("用水登录（App）" to account?.appToken.orEmpty()),
                    sensitive = true,
                    onDismiss = { dialog = null },
                    onConfirm = { values ->
                        dialog = null
                        val token = values.firstOrNull().orEmpty().trim()
                        if (account == null || token.isBlank()) toast("登录信息不能为空")
                        else verifyAndSaveTokens(account.phone, "", token, account, setCurrent = false)
                    }
                )
            }
            is DialogState.Reveal -> {
                val account = AccountStore.get(this, state.phone)
                val token = if (state.app) account?.appToken.orEmpty() else account?.token.orEmpty()
                AlertDialog(
                    onDismissRequest = { dialog = null },
                    title = { Text(if (state.app) "用水登录信息" else "积分登录信息") },
                    text = { Text("这是一段敏感登录信息，请勿发送给他人或粘贴到不可信应用。\n\n$token") },
                    confirmButton = { TextButton(onClick = { copySensitive(token); dialog = null }) { Text("复制登录信息") } },
                    dismissButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } }
                )
            }
            is DialogState.Delete -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("删除账户") },
                text = { Text("确认删除 ${state.phone}？此操作会移除本机保存的 Token 和设备分配。") },
                confirmButton = { TextButton(onClick = { AccountStore.remove(this, state.phone); dialog = null; refresh(); toast("已删除") }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } }
            )
            null -> Unit
        }
    }

    private fun refresh() {
        val current = AccountStore.getCurrent(this)?.phone
        accounts = AccountStore.list(this).map { account ->
            AccountCardState(
                phone = account.phone.orEmpty(),
                title = account.toString(),
                current = account.phone == current,
                alipayToken = account.token.orEmpty(),
                appToken = account.appToken.orEmpty(),
                alipayStatus = LoginStatusStore.status(account, false),
                appStatus = LoginStatusStore.status(account, true),
                deviceSummary = account.rememberedDevices()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "${it.size} 台设备" }
                    ?: "暂无设备"
            )
        }
    }

    /**
     * 只读复核一个账户的两条登录信息。
     *
     * 三条防陈旧规则，缺一不可：
     *  1. 同一 (手机号, 平台) 只保留最后一次请求，过期响应直接丢弃；
     *  2. 回调时重新从 AccountStore 读账户，避免用旧快照覆盖；
     *  3. 当前 token 与发起请求时的 token 不同则丢弃（用户期间重新登录过）。
     *
     * 检测失败（网络错误 / json 为空）记 FAILED 而不是 EXPIRED：断网不等于登录失效。
     */
    private fun checkLogins(account: Account) {
        listOf(false, true).forEach { app ->
            val token = (if (app) account.appToken else account.token).orEmpty()
            if (token.isBlank()) return@forEach
            val key = account.phone to app
            // 同一 token 正在检测中就不重复发请求。
            if (loginStates[key]?.let { it.token == token && it.status == TokenValidity.CHECKING } == true) return@forEach
            val checking = CheckedLogin(token, TokenValidity.CHECKING)
            loginStates[key] = checking
            LoginStatusStore.record(account, app, TokenValidity.CHECKING)
            val callback = IlifeApi.JsonCallback { json, _ ->
                runOnUiThread {
                    if (loginStates[key] !== checking) return@runOnUiThread
                    val current = AccountStore.get(this, account.phone) ?: return@runOnUiThread
                    if ((if (app) current.appToken else current.token) != token) return@runOnUiThread
                    val status = TokenValidity.from(json)
                    loginStates[key] = CheckedLogin(token, status)
                    LoginStatusStore.record(current, app, status)
                }
            }
            if (app) {
                // 用水登录：用 App 身份头只读查询账户信息。
                IlifeApi.checkLoginStatus(
                    token,
                    TaskProtocol.appType(TaskPlatform.APP),
                    TaskProtocol.version(TaskPlatform.APP),
                    callback
                )
            } else {
                // 积分登录：用小程序身份头读任务列表（积分侧没有单独的会话检查接口）。
                IlifeApi.missionLstWithToken(token, TaskPlatform.MAIN, callback)
            }
        }
    }

    private fun addByToken(values: List<String>) {
        val phone = values.getOrElse(0) { "" }.trim()
        val token = values.getOrElse(1) { "" }.trim()
        val appToken = values.getOrElse(2) { "" }.trim()
        if (phone.isBlank() || (token.isBlank() && appToken.isBlank())) {
            toast("请填写手机号和至少一条登录信息")
            return
        }
        verifyAndSaveTokens(phone, token, appToken, null, setCurrent = true)
    }

    private fun verifyAndSaveTokens(
        phone: String,
        mainInput: String,
        appInput: String,
        fixedAccount: Account?,
        setCurrent: Boolean
    ) {
        val requested = linkedMapOf<String, MutableSet<ImportedTokenPlatform>>()
        fun add(token: String, platform: ImportedTokenPlatform) {
            if (token.isBlank()) return
            requested.getOrPut(token) { linkedSetOf() } += platform
        }
        add(mainInput, ImportedTokenPlatform.MAIN)
        add(appInput, ImportedTokenPlatform.APP)
        if (requested.isEmpty()) {
            toast("登录信息不能为空")
            return
        }

        // 先固定「这次导入针对哪个账户」，回调时用它判断期间有没有被别的操作改过。
        val original = fixedAccount
            ?: AccountStore.get(this, phone)
            ?: AccountStore.list(this).firstOrNull { account ->
                requested.keys.any { candidate -> candidate == account.token || candidate == account.appToken }
            }
        val requestKey = original?.phone ?: phone
        val request = Any()
        pendingImports[requestKey] = request

        toast("正在核验登录信息…")
        val inspected = ConcurrentHashMap<String, InspectedToken>()
        val remaining = AtomicInteger(requested.size)
        requested.keys.forEach { token ->
            IlifeApi.probeToken(token) { probe, err ->
                inspected[token] = TokenImportResolver.inspect(token, probe, err)
                if (remaining.decrementAndGet() == 0) {
                    runOnUiThread {
                        // 页面已关闭，或同一账户又发起了新的导入：丢弃这次结果。
                        if (isDestroyed || isFinishing || pendingImports[requestKey] !== request) return@runOnUiThread
                        pendingImports.remove(requestKey)
                        val existing = AccountStore.get(this, requestKey)
                        // 期间账户被删除、重新登录或换了登录信息：不要用旧探测结果覆盖新状态。
                        if (original == null && existing != null || original != null &&
                            (existing == null || existing.token != original.token || existing.appToken != original.appToken)
                        ) {
                            toastLong("账号或登录信息已变化，请重新检查后保存")
                            return@runOnUiThread
                        }
                        val candidates = requested.map { (candidateToken, platforms) ->
                            TokenImportCandidate(
                                inspection = inspected.getValue(candidateToken),
                                requestedPlatforms = platforms
                            )
                        }
                        val resolution = TokenImportResolver.resolve(
                            candidates = candidates,
                            expectedPhone = phone,
                            existingMainToken = existing?.token.orEmpty(),
                            existingAppToken = existing?.appToken.orEmpty()
                        )
                        if (!resolution.success) {
                            toastLong("验证失败：${resolution.error}")
                            return@runOnUiThread
                        }

                        val account = existing ?: Account(phone)
                        val oldPhone = account.phone.orEmpty()
                        account.phone = phone
                        account.token = resolution.mainToken
                        account.appToken = resolution.appToken
                        resolution.uid.takeIf(String::isNotBlank)?.let { account.uid = it }
                        resolution.eid.takeIf(String::isNotBlank)?.let { account.eid = it }
                        resolution.name.takeIf(String::isNotBlank)?.let { account.name = it }
                        if (oldPhone.isNotBlank() && oldPhone != phone) {
                            AccountStore.remove(this, oldPhone)
                        }
                        if (setCurrent) AccountStore.addOrUpdate(this, account)
                        else AccountStore.addOrUpdateKeepingCurrent(this, account)
                        refresh()
                        // 刚写入的登录信息立刻复核一次，账户页马上能看到真实状态。
                        checkLogins(account)

                        val details = buildList {
                            addAll(resolution.notices)
                            if (resolution.appToken.isBlank()) add("仍需完成用水登录，才能使用设备")
                            if (resolution.mainToken.isBlank()) add("可补充积分登录，获得更多积分")
                        }.distinct()
                        toastLong(
                            if (details.isEmpty()) "登录信息验证并保存成功"
                            else "已保存：${details.joinToString("；")}"
                        )
                    }
                }
            }
        }
    }

    private fun copySensitive(text: String) {
        val clip = ClipData.newPlainText("sensitive credential", text)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        toast("已复制敏感凭据")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun toastLong(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private sealed interface DialogState {
        data object AddToken : DialogState
        data class SetAppToken(val phone: String) : DialogState
        data class Reveal(val phone: String, val app: Boolean) : DialogState
        data class Delete(val phone: String) : DialogState
    }
}
