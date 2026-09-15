package com.kuaixia.app.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志 V2：统一脱敏测试（URL 逐参数 / Cookie / token / Authorization / deviceId / did / iid）。 */
class LogSanitizerV2Test {

    @Test
    fun urlQueryIsRedactedPerParameter() {
        val out = LogSanitizer.sanitizeUrl(
            "https://example.com/video?id=123&token=abcdef&sign=xxxxx&bvid=BV1xx",
        )
        assertTrue(out.contains("https://example.com/video"))
        assertTrue("非敏感参数保留", out.contains("id=123"))
        assertTrue("非敏感参数保留", out.contains("bvid=BV1xx"))
        assertTrue("token 被脱敏", out.contains("token=<redacted>"))
        assertTrue("sign 被脱敏", out.contains("sign=<redacted>"))
        assertFalse("token 明文不得出现", out.contains("abcdef"))
        assertFalse("sign 明文不得出现", out.contains("xxxxx"))
    }

    @Test
    fun urlWithoutSensitiveParamsKeepsQuery() {
        val out = LogSanitizer.sanitizeUrl("https://www.douyin.com/share/video/123?from=web")
        assertEquals("https://www.douyin.com/share/video/123?from=web", out)
    }

    @Test
    fun textRedactsAuthorizationAndTokens() {
        val out = LogSanitizer.sanitize(
            "headers Authorization: Bearer eyJhbGciOi.J9 abc, access_token=xyz, refresh_token=rrr",
        )
        assertFalse(out.contains("eyJhbGciOi"))
        assertFalse(out.contains("xyz"))
        assertFalse(out.contains("rrr"))
        assertTrue(out.contains("<redacted>"))
    }

    @Test
    fun textRedactsDeviceAndSessionIdentifiers() {
        val out = LogSanitizer.sanitize("did=12345 iid=67890 device_id=abc sessionid=ss csrf=cc session=kk")
        assertFalse(out.contains("12345"))
        assertFalse(out.contains("67890"))
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("ss"))
        assertFalse(out.contains("cc"))
        assertFalse(out.contains("kk"))
    }

    @Test
    fun cookieValueIsRedactedButCountIsAvailable() {
        // 值用不易与键名混淆的标记，断言「值」不落盘（键名保留属预期，便于诊断）
        val raw = "sessionid=VAL1; sid_tt=VAL2; ttwid=VAL3; Domain=.douyin.com; Path=/; Domain=.iesdouyin.com"
        val stats = LogSanitizer.cookieStats(raw)
        assertEquals("count=3 domains=2", stats)
        val text = LogSanitizer.sanitize("Cookie: $raw")
        assertFalse("Cookie 值不得落盘", text.contains("VAL1"))
        assertFalse(text.contains("VAL2"))
        assertFalse(text.contains("VAL3"))
        assertTrue("键名保留便于诊断", text.contains("sid_tt") || text.contains("<redacted>"))
    }

    @Test
    fun sensitiveHeaderNamesAreDetected() {
        assertTrue(LogSanitizer.isSensitiveHeaderName("Cookie"))
        assertTrue(LogSanitizer.isSensitiveHeaderName("Authorization"))
        assertFalse(LogSanitizer.isSensitiveHeaderName("Referer"))
        assertFalse(LogSanitizer.isSensitiveHeaderName("User-Agent"))
    }

    @Test
    fun headersToStringKeepsDiagnosticHeaders() {
        val s = LogSanitizer.headersToString(
            mapOf("Referer" to "https://www.douyin.com/", "Cookie" to "sessionid=xx"),
        )
        assertTrue(s.contains("Referer=https://www.douyin.com/"))
        assertTrue(s.contains("Cookie=<redacted>"))
        assertFalse(s.contains("xx"))
    }

    /**
     * P2 收尾：解析会话序号字段必须命名为 `parseSeq=` 而非 `session=`。
     * 根因：`session` 在 [LogSanitizer.SENSITIVE_KEYS] 中，且 SENSITIVE_PAIR 贪婪正则会
     * 把 `session=<N> url=… droppedCallbacks=<N>` 整段吞成 `session=<redacted>`。
     * 本测试证明 `parseSeq` 非敏感键，且同一行后续字段（url / droppedCallbacks）不再被吞。
     */
    @Test
    fun parseSeqIsNotSensitiveAndSameLineFieldsSurvive() {
        assertFalse("parseSeq 不得是敏感键", LogSanitizer.isSensitiveQueryParam("parseSeq"))
        assertFalse(LogSanitizer.isSensitiveQueryParam("parseSeq".lowercase()))

        // 模拟 WebViewParser 取消日志的真实形态
        val msg = "嗅探取消 parseSeq=7 url=https://v.douyin.com/abc/ droppedCallbacks=3"
        val out = LogSanitizer.sanitize(msg)

        assertTrue("parseSeq 序号必须保留", out.contains("parseSeq=7"))
        assertTrue("droppedCallbacks 必须保留（不再被吞）", out.contains("droppedCallbacks=3"))
        assertTrue("URL 必须保留", out.contains("https://v.douyin.com/abc/"))
        assertFalse("不得被误脱敏为 redacted", out.contains("parseSeq=<redacted>"))
    }
}
