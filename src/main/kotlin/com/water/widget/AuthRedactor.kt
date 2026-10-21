package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 诊断日志的**脱敏与摘要**逻辑。
 *
 * 刻意做成纯 JVM（不依赖任何 Android 类），因此可以直接写单元测试，
 * 把「手机号/token/短信码绝不出现在日志里」这条隐私约束用测试钉死。
 *
 * 设计目标：**能看出服务端返回了哪些字段，看不出这些字段的值。**
 * 例如响应里有 refreshToken 时，日志会显示
 *   refreshToken=<redacted len=64>
 * 从而既能判断"服务端到底有没有给续期凭证"，又不会泄露凭证本身。
 *
 * 默认**不记录任何可关联的哈希**：11 位手机号只有 10^11 种，裸 SHA-256 几小时
 * 就能反查回原文，不能算匿名。只有用户在设置里打开「详细模式」时，才由
 * DiagLog 注入 [tagger]（HMAC(本机随机密钥, 值) 的前 8 位），用于判断
 * "日志里这两处是不是同一个值"，且密钥只存在本机、重装即失效。
 */
object AuthRedactor {

    private const val MAX_BODY_CHARS = 8192
    private const val MAX_RENDERED_CHARS = 2000
    private const val MAX_OBJECT_KEYS = 40
    private const val MAX_ARRAY_ITEMS = 5
    private const val MAX_LEAF_CHARS = 200

    /**
     * 详细模式下的「同值判定」钩子；null 表示不记录任何哈希（默认）。
     * 由 DiagLog 在开关变化时注入/清除。
     */
    @Volatile
    var tagger: ((String) -> String)? = null

    /** 值必须脱敏的字段名（小写比较）。 */
    val SENSITIVE_KEYS: Set<String> = setOf(
        "token", "refreshtoken", "accesstoken", "idtoken", "sessionid",
        "uid", "eid", "un", "pn", "phone", "phonenumber", "mobile",
        "authcode", "opencode", "password", "passwd", "idcard",
        "realname", "name", "nickname", "img", "avatar",
        "authorization", "cookie", "openid", "unionid", "sign",
        "did", "id", "oid", "deviceid", "cardno", "cardid"
    )

    fun isSensitiveKey(key: String): Boolean =
        key.lowercase(Locale.US) in SENSITIVE_KEYS

    /**
     * 兜底扫描：即使字段名不在敏感名单里（例如服务端 msg 里回显了手机号），
     * 值中出现的手机号 / 身份证号 / 短信验证码也要抹掉。
     * 这是隐私保证的第二道防线，不依赖字段名。
     */
    private val ID_CARD = Regex("\\d{17}[\\dXx]")
    private val PHONE = Regex("1[3-9]\\d{9}")
    private val CODE_AFTER_KEYWORD = Regex("(验证码|校验码|动态码|短信码)([^0-9]{0,8})(\\d{4,8})")

    fun scrubFreeText(s: String): String {
        var out = ID_CARD.replace(s) { redact(it.value) }
        out = PHONE.replace(out) { redact(it.value) }
        out = CODE_AFTER_KEYWORD.replace(out) { m ->
            m.groupValues[1] + m.groupValues[2] + redact(m.groupValues[3])
        }
        return out
    }

    /**
     * 查询参数默认脱敏，只有白名单里的非敏感参数保留原文。
     * 白名单是为了保留诊断价值（例如 s=true 与 s=1 的契约差异），
     * 未知参数一律按敏感处理。
     *
     * 例：?sign=abc&s=true -> ?sign=<redacted len=3>&s=true
     */
    private val SAFE_QUERY_KEYS = setOf("s", "page", "size", "hascount", "type")

    fun maskUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val path = url.substring(0, q)
        val query = url.substring(q + 1)
        val masked = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                pair
            } else {
                val name = pair.substring(0, eq)
                val value = pair.substring(eq + 1)
                if (name.lowercase(Locale.US) in SAFE_QUERY_KEYS) name + "=" + value
                else name + "=" + redact(value)
            }
        }
        return path + "?" + masked
    }

    /**
     * 把请求/响应体压成一行可读摘要。
     * - JSON：逐字段渲染，敏感字段的值替换为脱敏标记
     * - 非 JSON：整体脱敏（宁可过度，不可泄露）
     *
     * 注意：**必须先解析完整 body 再渲染**。早期版本先把 body 截断到 8KB 再解析，
     * 导致大响应（如 /ui/app/master）永远解析失败、退化成整段脱敏文本，看不到字段结构。
     */
    fun summarize(body: String?): String {
        if (body.isNullOrBlank()) return "(empty)"
        val parsed: Any? = try {
            JSONObject(body)
        } catch (_: Throwable) {
            try { JSONArray(body) } catch (_: Throwable) { null }
        }
        if (parsed != null) {
            // 最后再过一道兜底扫描，确保任何路径都不会漏出手机号等
            val rendered = scrubFreeText(render(parsed, ""))
            return if (rendered.length > MAX_RENDERED_CHARS) {
                rendered.substring(0, MAX_RENDERED_CHARS) + "…(len=" + rendered.length + ")"
            } else {
                rendered
            }
        }
        val text = if (body.length > MAX_BODY_CHARS) body.substring(0, MAX_BODY_CHARS) else body
        return "(non-json len=" + body.length + ") " + redact(text)
    }

    private fun render(value: Any?, path: String): String = when (value) {
        is JSONObject -> {
            val keys = value.keys()
            val parts = ArrayList<String>()
            var seen = 0
            while (keys.hasNext()) {
                val k = keys.next()
                if (seen >= MAX_OBJECT_KEYS) {
                    parts.add("…+" + (value.length() - seen))
                    break
                }
                seen++
                val child = if (path.isEmpty()) k else path + "." + k
                parts.add(k + "=" + render(value.opt(k), child))
            }
            "{" + parts.joinToString(", ") + "}"
        }
        is JSONArray -> {
            val parts = ArrayList<String>()
            val n = value.length()
            for (i in 0 until minOf(n, MAX_ARRAY_ITEMS)) parts.add(render(value.opt(i), path))
            if (n > MAX_ARRAY_ITEMS) parts.add("...+" + (n - MAX_ARRAY_ITEMS))
            "[" + parts.joinToString(", ") + "]"
        }
        null -> "null"
        else -> {
            val leaf = if (path.isEmpty()) "" else path.substringAfterLast('.')
            if (isSensitiveKey(leaf)) {
                redact(value.toString())
            } else {
                val s = scrubFreeText(value.toString())
                if (s.length > MAX_LEAF_CHARS) s.substring(0, MAX_LEAF_CHARS) + "…(len=" + s.length + ")" else s
            }
        }
    }

    /**
     * 敏感值统一替换为「长度」，默认不含任何哈希。
     * 详细模式下（[tagger] 非空）追加 tag，用于判断两处日志是不是同一个值。
     */
    fun redact(value: String): String {
        if (value.isEmpty()) return "<empty>"
        val tag = tagger?.invoke(value)
        return if (tag == null) {
            "<redacted len=" + value.length + ">"
        } else {
            "<redacted len=" + value.length + " tag=" + tag + ">"
        }
    }
}
