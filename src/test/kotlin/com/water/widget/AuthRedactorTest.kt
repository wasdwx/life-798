package com.water.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私保证的回归测试。
 *
 * 核心断言：**手机号、短信验证码、token 等敏感值绝不原样出现在诊断日志里**，
 * 同时**字段名必须保留**（否则就看不出服务端返回了哪些字段）。
 */
class AuthRedactorTest {

    private val phone = "13800001234"
    private val smsCode = "654321"
    private val token = "eyJhbGciOiJIUzI1NiJ9.fake-token-value-for-test"

    @Test fun loginResponseKeepsFieldNamesButHidesValues() {
        val response = JSONObject()
            .put("code", 0)
            .put("msg", "成功")
            .put(
                "data",
                JSONObject().put(
                    "al",
                    JSONObject()
                        .put("token", token)
                        .put("uid", "1234567890")
                        .put("eid", "abcdef1234")
                        .put("refreshToken", "refresh-value-should-be-hidden")
                        .put("expiresIn", 259200)
                        .put("phone", phone)
                )
            )
            .toString()

        val summary = AuthRedactor.summarize(response)

        // 字段名必须保留 —— 这是本次调查的关键：能看出服务端有没有返回续期相关字段
        assertTrue(summary.contains("refreshToken"))
        assertTrue(summary.contains("expiresIn"))
        assertTrue(summary.contains("token="))

        // 非敏感值必须保留 —— 否则看不懂发生了什么
        assertTrue(summary.contains("code=0"))
        assertTrue(summary.contains("msg=成功"))
        assertTrue(summary.contains("expiresIn=259200"))

        // 敏感值绝不能出现
        assertFalse(summary.contains(token))
        assertFalse(summary.contains(phone))
        assertFalse(summary.contains("1234567890"))
        assertFalse(summary.contains("abcdef1234"))
        assertFalse(summary.contains("refresh-value-should-be-hidden"))

        // 但要看得出"这里有个值、多长"
        assertTrue(summary.contains("len=" + token.length))
        // 默认不写任何可反查的指纹（裸 sha256 对 11 位手机号可暴力枚举，等于没脱敏）
        assertFalse(summary.contains("sha256="))
        assertFalse(summary.contains("tag="))
    }

    @Test fun loginRequestBodyHidesPhoneAndSmsCodeButKeepsCid() {
        val request = JSONObject()
            .put("openCode", "")
            .put("authCode", smsCode)
            .put("un", phone)
            .put("cid", "drinkwaterapp123456789")
            .toString()

        val summary = AuthRedactor.summarize(request)

        assertTrue(summary.contains("un="))
        assertTrue(summary.contains("authCode="))
        assertTrue(summary.contains("openCode=<empty>"))
        // cid 不是个人信息，保留以便确认客户端标识
        assertTrue(summary.contains("drinkwaterapp123456789"))

        assertFalse(summary.contains(phone))
        assertFalse(summary.contains(smsCode))
    }

    @Test fun querySignatureIsMaskedButParameterNameKept() {
        val masked = AuthRedactor.maskUrl(
            "https://i.ilife798.com/api/v1/acc/score/score-send?sign=deadbeefcafe&s=true"
        )
        assertTrue(masked.startsWith("https://i.ilife798.com/api/v1/acc/score/score-send?sign="))
        // 白名单参数保留原文，便于看出契约差异（s=true vs s=1）
        assertTrue(masked.contains("s=true"))
        // 未知/敏感参数一律脱敏
        assertFalse(masked.contains("deadbeefcafe"))
    }

    @Test fun unknownQueryParameterIsRedacted() {
        val masked = AuthRedactor.maskUrl("https://x/api?phone=" + phone + "&page=0")
        assertFalse(masked.contains(phone))
        assertTrue(masked.contains("page=0"))
        assertTrue(masked.contains("phone=<redacted"))
    }

    @Test fun nonJsonBodyIsShownButScrubbed() {
        val summary = AuthRedactor.summarize(
            "EXCEPTION UnknownHostException: Unable to resolve host for " + phone
        )
        assertFalse(summary.contains(phone))
        assertTrue(summary.contains("non-json"))
        // 错误正文必须保留：以前整段变成 <redacted len=N>，异常原因全丢，没法排查
        assertTrue(summary.contains("UnknownHostException"))
    }

    @Test fun timestampIsNotMistakenForPhone() {
        // 13 位时间戳 / 16 位流水号不能被当成手机号切碎（之前会出现 <redacted len=11>78 这种畸形输出）
        val summary = AuthRedactor.summarize("{\"ctime\":1761234567890,\"refId\":1761234567890123}")
        assertTrue(summary.contains("ctime=1761234567890"))
        assertTrue(summary.contains("1761234567890123"))
    }

    @Test fun maskedPhoneIpAndGeoAreScrubbed() {
        // 全部为构造的假数据：203.0.113.0/24 是 RFC 5737 保留的文档网段
        val text = "pkPn=138******99 ip=203.0.113.9 geo={coordinates=[123.4567000,45.6789000]}"
        val scrubbed = AuthRedactor.scrubFreeText(text)
        assertFalse(scrubbed.contains("138******99"))
        assertFalse(scrubbed.contains("203.0.113.9"))
        assertFalse(scrubbed.contains("123.4567000"))
        assertFalse(scrubbed.contains("45.6789000"))
    }

    @Test fun addressAndCompanyInsideParenthesesAreScrubbed() {
        val text = "msg=管线/饮水机设备(示例校区A栋101室)消费 abbr=(示例科技有限公司)"
        val scrubbed = AuthRedactor.scrubFreeText(text)
        assertFalse(scrubbed.contains("示例校区A栋101室"))
        assertFalse(scrubbed.contains("示例科技有限公司"))
        // 不含地址特征的括号内容不该被误伤
        assertTrue(AuthRedactor.scrubFreeText("(非json len=108)").contains("非json len=108"))
    }

    @Test fun nestedLocationPathIsRedacted() {
        val response = JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONObject().put(
                    "addr",
                    JSONObject().put("city", "示例市").put("detail", "示例大学")
                ).put("aw", JSONObject().put("ep", JSONObject().put("abbr", "示例公司")))
            )
            .toString()
        val summary = AuthRedactor.summarize(response)
        assertFalse(summary.contains("示例市"))
        assertFalse(summary.contains("示例大学"))
        assertFalse(summary.contains("示例公司"))
    }

    @Test fun nestedArraysAndUnknownKeysAreHandled() {
        val response = JSONObject()
            .put("code", -99)
            .put("msg", "登录已过期")
            .put("data", JSONArray().put(JSONObject().put("token", token)).put(JSONObject().put("token", token)))
            .toString()

        val summary = AuthRedactor.summarize(response)
        assertTrue(summary.contains("code=-99"))
        assertTrue(summary.contains("登录已过期"))
        assertFalse(summary.contains(token))
    }

    @Test fun sensitiveKeyMatchingIsCaseInsensitive() {
        assertTrue(AuthRedactor.isSensitiveKey("Token"))
        assertTrue(AuthRedactor.isSensitiveKey("REFRESHTOKEN"))
        assertTrue(AuthRedactor.isSensitiveKey("phoneNumber"))
        assertFalse(AuthRedactor.isSensitiveKey("code"))
        assertFalse(AuthRedactor.isSensitiveKey("msg"))
    }

    @Test fun phoneEchoedInsideServerMessageIsScrubbed() {
        // 服务端可能把手机号回显在 msg 里，而 msg 不在敏感字段名单里
        val response = JSONObject()
            .put("code", -1)
            .put("msg", "验证码已发送至 " + phone + "，请查收")
            .toString()
        val summary = AuthRedactor.summarize(response)
        assertFalse(summary.contains(phone))
        assertTrue(summary.contains("验证码已发送至"))
        assertTrue(summary.contains("<redacted"))
    }

    @Test fun smsCodeEchoedInsideServerMessageIsScrubbed() {
        val response = JSONObject()
            .put("code", 0)
            .put("msg", "短信验证码 " + smsCode + " 已发送")
            .toString()
        val summary = AuthRedactor.summarize(response)
        assertFalse(summary.contains(smsCode))
        assertTrue(summary.contains("<redacted"))
    }

    @Test fun freeTextScrubberCatchesIdCardAndPhone() {
        val scrubbed = AuthRedactor.scrubFreeText("联系人 13800001234 证件 11010119900307123X")
        assertFalse(scrubbed.contains("13800001234"))
        assertFalse(scrubbed.contains("11010119900307123X"))
    }

    @Test fun defaultRedactionRevealsOnlyLength() {
        val a = AuthRedactor.redact(token)
        val b = AuthRedactor.redact(token)
        // 不注入 tagger 时，同长度但不同值的两个串完全无法区分 —— 这是刻意的
        val sameLengthDifferent = token.dropLast(1) + "X"
        assertEquals(token.length, sameLengthDifferent.length)
        assertEquals(a, b)
        assertEquals(a, AuthRedactor.redact(sameLengthDifferent))
        assertTrue(a.startsWith("<redacted len="))
        assertFalse(a.contains("tag="))
        assertFalse(a.contains("sha256="))
    }

    @Test fun taggerAddsStableShortTagOnlyInVerboseMode() {
        // 详细模式：用本机随机密钥算出的 tag，同一安装内可判断"是不是同一个值"
        AuthRedactor.tagger = { value -> "a7f3c9d2" }
        try {
            val a = AuthRedactor.redact(token)
            val b = AuthRedactor.redact(token + "x")
            assertTrue(a.contains("tag=a7f3c9d2"))
            assertFalse(a.contains(token))
            // 同一个值 → 同一个 tag；不同值由 tagger 自己区分，这里只验证被拼进去
            assertTrue(b.contains("tag=a7f3c9d2"))
        } finally {
            AuthRedactor.tagger = null
        }
    }

    @Test fun largeBodyIsParsedBeforeTruncation() {
        // 旧实现先截断到 8192 再解析，大响应会整段退化成 non-json，
        // 于是服务端到底返回了什么字段完全看不出来。这里守住"先解析、后渲染"。
        val big = "x".repeat(20000)
        val response = JSONObject()
            .put("code", 0)
            .put("msg", big)
            .put("data", JSONObject().put("token", token).put("expiresIn", 259200))
            .toString()
        assertTrue(response.length > 8192)

        val summary = AuthRedactor.summarize(response)
        assertTrue(summary.contains("code=0"))
        assertTrue(summary.contains("token="))
        assertTrue(summary.contains("expiresIn=259200"))
        assertFalse(summary.contains("non-json"))
        assertFalse(summary.contains(token))
    }

    @Test fun deviceIdentifiersAreRedacted() {
        val response = JSONObject()
            .put("data", JSONObject()
                .put("did", "device-abc-123456")
                .put("id", "9876543210")
                .put("oid", "oid-value-xyz")
                .put("deviceId", "dev-000111"))
            .toString()
        val summary = AuthRedactor.summarize(response)
        assertFalse(summary.contains("device-abc-123456"))
        assertFalse(summary.contains("9876543210"))
        assertFalse(summary.contains("oid-value-xyz"))
        assertFalse(summary.contains("dev-000111"))
        // 字段名仍然保留
        assertTrue(summary.contains("did="))
        assertTrue(summary.contains("oid="))
    }
}
