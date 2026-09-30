package com.water.widget

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseVersion(val major: Long, val minor: Long, val patch: Long, val suffix: String?, val revision: Long) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch },
            { if (it.suffix == null) 1 else 0 }, { it.suffix.orEmpty() }, { it.revision })

    companion object {
        private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(personal|alpha|beta|rc)\\.(\\d+))?$")
        fun parse(value: String): ReleaseVersion? {
            val parts = pattern.matchEntire(value)?.groupValues ?: return null
            return ReleaseVersion(parts[1].toLongOrNull() ?: return null,
                parts[2].toLongOrNull() ?: return null, parts[3].toLongOrNull() ?: return null,
                parts[4].ifBlank { null }, parts[5].ifBlank { "0" }.toLongOrNull() ?: return null)
        }
    }
}

data class AppRelease(val tag: String, val version: ReleaseVersion, val notes: String, val prerelease: Boolean) {
    val url: String get() = "${AppUpdates.REPOSITORY_URL}/releases/tag/$tag"
}

object AppUpdates {
    const val REPOSITORY_URL = "https://github.com/wasdwx/life-798"
    private const val API_URL = "https://api.github.com/repos/wasdwx/life-798/releases?per_page=100"
    fun prefs(context: Context) = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    fun policy(context: Context) = prefs(context).getString("policy", "DAILY").let { saved ->
        UpdatePolicy.entries.firstOrNull { it.name == saved } ?: UpdatePolicy.DAILY
    }
    fun includePrereleases(context: Context) = prefs(context).getBoolean("prereleases", false)

    fun shouldCheck(policy: UpdatePolicy, now: Long, last: Long) =
        policy != UpdatePolicy.OFF && (last == 0L || now < last || now - last >= policy.interval)

    fun newest(releases: JSONArray, includePrereleases: Boolean): AppRelease? =
        (0 until releases.length()).mapNotNull { index ->
            val item = releases.optJSONObject(index) ?: return@mapNotNull null
            if (item.optBoolean("draft")) return@mapNotNull null
            val tag = item.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: return@mapNotNull null
            val preview = item.optBoolean("prerelease") || version.suffix != null
            if (preview && !includePrereleases) return@mapNotNull null
            val assets = item.optJSONArray("assets") ?: return@mapNotNull null
            val ready = (0 until assets.length()).any { assetIndex ->
                assets.optJSONObject(assetIndex)?.let {
                    it.optString("name") == "WaterWidget-$tag-release.apk" && it.optLong("size") > 0 &&
                        it.optString("state", "uploaded") == "uploaded"
                } == true
            }
            if (!ready) null else AppRelease(tag, version, item.optString("body").take(8000), preview)
        }.maxByOrNull { it.version }

    suspend fun fetch(includePrereleases: Boolean): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "WaterWidget/${BuildConfig.VERSION_NAME}")
            // No account token is ever attached to GitHub requests.
            val code = connection.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub 暂时限流，请稍后重试或打开项目发布页")
            if (code != 200) throw IOException("更新检查失败（HTTP $code）")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            newest(JSONArray(body), includePrereleases)
        } finally {
            connection.disconnect()
        }
    }
}

enum class UpdatePolicy(val label: String, val interval: Long) {
    OFF("关闭", Long.MAX_VALUE), ON_OPEN("每次打开", 0), DAILY("每天一次", 86_400_000), WEEKLY("每周一次", 604_800_000)
}

data class UpdateState(val checking: Boolean = false, val message: String = "当前版本 v${BuildConfig.VERSION_NAME}", val available: AppRelease? = null)

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()

    fun check(manual: Boolean = false) {
        val context = getApplication<Application>()
        if (state.value.checking) return
        val prefs = AppUpdates.prefs(context)
        if (!manual && !AppUpdates.shouldCheck(AppUpdates.policy(context), System.currentTimeMillis(), prefs.getLong("last_attempt", 0))) return
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        mutableState.value = state.value.copy(checking = true, message = "正在检查更新…")
        viewModelScope.launch {
            try {
                val release = AppUpdates.fetch(AppUpdates.includePrereleases(context))
                val current = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
                val newer = release?.takeIf { it.version > current }
                mutableState.value = UpdateState(message = when {
                    newer != null -> "发现${if (newer.prerelease) "预发布" else "正式"}版本 ${newer.tag}"
                    release == null -> "没有可用的${if (AppUpdates.includePrereleases(context)) "已发布" else "正式"}版本（草稿不会显示）"
                    else -> "已是最新版本，仓库最新为 ${release.tag}"
                }, available = newer)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = UpdateState(message = error.message ?: "检查更新失败，请稍后重试")
            }
        }
    }
}
