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
 * 设计目标：**能看出服务端返回了哪些字段、以及请求为什么失败，看不出这些字段的值。**
 * 例如响应里有 refreshToken 时，日志会显示
 *   refreshToken=<redacted len=64>
 * 从而既能判断"服务端到底有没有给续期凭证"，又不会泄露凭证本身。
 *
 * 两道防线：
 *  1. 字段名命中敏感名单（含嵌套路径任意一段）→ 整个值替换为脱敏标记；
 *  2. 兜底扫描自由文本，抹掉手机号（含服务端已打码的 138******99）、身份证、
 *     短信验证码、IP、精确经纬度，以及括号里带「楼/栋/校/院/公司」等字样的地址。
 *
 * 默认**不记录任何可关联的哈希**：11 位手机号只有 10^11 种，裸 SHA-256 几小时
 * 就能反查回原文，不能算匿名。只有用户在设置里打开「详细模式」时，才由
 * DiagLog 注入 [tagger]（HMAC(本机随机密钥, 值) 的前 8 位），用于判断
 * "日志里这两处是不是同一个值"，且密钥只存在本机、重装即失效。
 */
object AuthRedactor {

    /**
     * 脱敏规则版本。规则一旦收紧就 +1，DiagLog 会据此清掉按旧规则写的历史日志，
     * 避免旧明文（例如未脱敏的 IP/经纬度）长期留在设备上被导出。
     */
    const val VERSION = 2

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

    /**
     * 值必须脱敏的字段名（小写比较）。三类：凭证 / 身份标识 / 个人与位置信息。
     *
     * 判断用**路径里任意一段命中**，所以 `addr.city`、`aw.ep.abbr`、
     * `favo.addr.geo.coordinates` 这类嵌套结构不必逐层列全。
     */
    val SENSITIVE_KEYS: Set<String> = setOf(
        // 凭证
        "token", "refreshtoken", "accesstoken", "idtoken", "sessionid",
        "authcode", "opencode", "password", "passwd", "sign",
        "authorization", "cookie", "openid", "unionid",
        // 身份标识
        "uid", "eid", "un", "pn", "phone", "phonenumber", "mobile", "pkpn",
        "idcard", "realname", "name", "nickname", "img", "avatar",
        "did", "id", "oid", "deviceid", "devid", "devicecode", "cardno", "cardid", "sn",
        // 位置与企业信息
        "ip", "addr", "address", "city", "dist", "district", "detail",
        "geo", "coordinates", "lat", "lng", "latitude", "longitude",
        "abbr", "contact", "school", "org", "company", "location", "loc", "ep"
    )

    fun isSensitiveKey(key: String): Boolean =
        key.lowercase(Locale.US) in SENSITIVE_KEYS

    /** 路径（`a.b.c`）里任意一段命中敏感名单即视为敏感。 */
    fun isSensitivePath(path: String): Boolean =
        path.isNotEmpty() && path.split('.').any { isSensitiveKey(it) }

    /**
     * 兜底扫描：即使字段名不在敏感名单里（例如服务端 msg 里回显了手机号、
     * 地址、楼栋），值中出现的隐私内容也要抹掉。这是第二道防线，不依赖字段名。
     *
     * 所有数字型规则都带 `(?<!\d)…(?!\d)` 边界：早期版本没有边界，
     * 11 位手机号规则会把 13 位时间戳/流水号切成「脱敏标记 + 残尾」，
     * 既泄露了位数结构，又把日志搞乱。
     */
    private val ID_CARD = Regex("(?<![\\dXx])\\d{17}[\\dXx](?![\\dXx])")
    private val PHONE = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    /** 服务端自己打过码的手机号，如 138******99 */
    private val MASKED_PHONE = Regex("(?<!\\d)\\d{3}\\*{2,}\\d{2,4}(?!\\d)")
    private val IPV4 = Regex("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])")
    /** 精确到小数点后 4 位以上的经纬度对 */
    private val GEO_PAIR = Regex("\\d{1,3}\\.\\d{4,}\\s*,\\s*\\d{1,3}\\.\\d{4,}")
    private val CODE_AFTER_KEYWORD = Regex("(验证码|校验码|动态码|短信码)([^0-9]{0,8})(\\d{4,8})")
    private val PAREN = Regex("[（(]([^（）()]{1,80})[）)]")
    private val ADDRESS_HINT = Regex("楼|栋|室|层|校|院|路|街|号|区|县|市|省|镇|村|小区|宿舍|公寓|公司|集团")

    fun scrubFreeText(s: String): String {
        var out = ID_CARD.replace(s) { redact(it.value) }
        out = PHONE.replace(out) { redact(it.value) }
        out = MASKED_PHONE.replace(out) { redact(it.value) }
        out = IPV4.replace(out) { redact(it.value) }
        out = GEO_PAIR.replace(out) { redact(it.value) }
        out = CODE_AFTER_KEYWORD.replace(out) { m ->
            m.groupValues[1] + m.groupValues[2] + redact(m.groupValues[3])
        }
        // 楼栋往往藏在自由文本的括号里，例如「管线/饮水机设备(示例校区A栋101室)消费」
        out = PAREN.replace(out) { m ->
            val inner = m.groupValues[1]
            if (ADDRESS_HINT.containsMatchIn(inner)) "(" + redact(inner) + ")" else m.value
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
     * - 非 JSON：**保留正文**（截断后清洗 PII），因为异常/网关错误恰好落在这里
     *
     * 非 JSON 分支以前是整段 `<redacted len=N>`，结果 IlifeApi 把异常拼成
     * `EXCEPTION UnknownHostException: ...` 当响应体传进来时，真正的错误原因
     * 被自己人抹掉了，日志上只剩一个长度。
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
        val scrubbed = scrubFreeText(text)
        val shown = if (scrubbed.length > MAX_RENDERED_CHARS) {
            scrubbed.substring(0, MAX_RENDERED_CHARS) + "…"
        } else {
            scrubbed
        }
        return "(non-json len=" + body.length + ") " + shown
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
            if (isSensitivePath(path)) {
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
