package com.kuaixia.app.core.log

/**
 * 日志脱敏（日志 V2 统一入口）：确保 Cookie / Authorization / token / signature / 设备标识等不落盘。
 *
 * 设计：
 * - 敏感键集合 [SENSITIVE_KEYS] 是唯一事实来源，文本配对正则由它生成（避免两处漂移）；
 * - URL 查询参数**逐参数**脱敏（`?id=123&token=<redacted>&sign=<redacted>`），其余参数保留便于诊断；
 * - 提供 Cookie 统计 [cookieStats]（只记数量与域数，绝不落 Cookie 值）。
 *
 * 普通 Referer / User-Agent 属于诊断必需信息，正常保留（值过长时截断）。
 * 脱敏是**兜底**：业务代码仍应避免主动把敏感值拼进日志消息。
 */
object LogSanitizer {

    /** 敏感字段名（header 名 / query 参数名 / JSON key）。V2 增补设备标识与隐私参数。 */
    val SENSITIVE_KEYS = setOf(
        // 会话 / 鉴权
        "cookie", "cookies", "set-cookie", "authorization", "proxy-authorization", "auth",
        "token", "access_token", "refresh_token", "bearer", "apikey", "api_key", "x-api-key",
        "password", "passwd", "secret", "credential", "private_key", "client_secret",
        // 会话标识
        "session", "sessionid", "session_id", "session_key", "sid", "sid_tt", "sessionid_ss", "csrf",
        "csrf_token", "csrftoken", "ttwid", "odin_tt", "ms_token", "mstoken",
        // 签名
        "signature", "sig", "sign", "upsig", "w_rid", "wts", "deadline", "trid",
        "share_token", "sharetoken", "verify", "verification",
        // 设备 / 用户标识
        "did", "iid", "device_id", "deviceid", "device_idfa", "buvid", "buvid3", "buvid4",
        "uid", "user_id", "userid", "openid", "unionid", "phone", "mobile", "email",
    )

    /** 匹配 `key: value` / `key=value` 中的敏感片段（由 [SENSITIVE_KEYS] 生成）。 */
    private val SENSITIVE_PAIR: Regex = run {
        val alternation = SENSITIVE_KEYS.joinToString("|") { Regex.escape(it) }
        Regex("(?i)\\b($alternation)\\b\\s*[:=]\\s*[^,\\n;}\\]]*")
    }

    private const val MAX_VALUE_LEN = 300
    private const val MAX_QUERY_LEN = 300

    /** header 名是否敏感（value 不得落盘）。 */
    fun isSensitiveHeaderName(name: String): Boolean =
        name.lowercase() in SENSITIVE_KEYS

    /** query 参数名是否敏感（其值替换为 `<redacted>`）。 */
    fun isSensitiveQueryParam(name: String): Boolean =
        name.lowercase() in SENSITIVE_KEYS

    /** 脱敏任意文本（日志消息、stacktrace）。 */
    fun sanitize(text: String): String =
        runCatching { SENSITIVE_PAIR.replace(text) { m -> "${m.groupValues[1]}=<redacted>" } }
            .getOrDefault(text)

    /**
     * 脱敏 URL：保留 scheme://host[:port]/path，query **逐参数**处理——
     * 敏感参数值替换为 `<redacted>`，非敏感参数原样保留（如 `?id=123&bvid=…`）。
     */
    fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "(空)"
        return runCatching {
            val uri = java.net.URI(url)
            val sb = StringBuilder()
            if (uri.scheme != null) sb.append(uri.scheme).append("://")
            sb.append(uri.host ?: "")
            if (uri.port > 0) sb.append(':').append(uri.port)
            sb.append(uri.path ?: "")
            val query = uri.query
            if (!query.isNullOrBlank()) {
                val redacted = query.split('&').joinToString("&") { kv ->
                    val key = kv.substringBefore('=')
                    if (isSensitiveQueryParam(key)) "$key=<redacted>" else kv
                }
                sb.append('?').append(redacted.take(MAX_QUERY_LEN))
            }
            sb.toString()
        }.getOrDefault(url.take(MAX_VALUE_LEN))
    }

    /**
     * HTTP 请求头摘要：输出 key，非敏感头附带值；敏感头值脱敏。
     * 如：`Referer=https://www.bilibili.com/, Cookie=<redacted>`
     */
    fun headersToString(headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty()) return "(空)"
        return headers.entries.joinToString(", ") { (k, v) ->
            if (isSensitiveHeaderName(k)) "$k=<redacted>"
            else "$k=${sanitize(v).take(MAX_VALUE_LEN)}"
        }
    }

    /** 仅输出请求头 key 列表（最保守，用于创建任务/失败上下文）。 */
    fun headerKeys(headers: Map<String, String>?): String =
        if (headers.isNullOrEmpty()) "(空)" else headers.keys.joinToString(", ")

    /**
     * 过滤出可持久化的非敏感请求头（供 Room 保存/任务恢复）。
     * Cookie/Authorization/token/签名等一律剔除。
     */
    fun persistableHeaders(headers: Map<String, String>?): Map<String, String> =
        headers.orEmpty().filterKeys { !isSensitiveHeaderName(it) }

    /**
     * Cookie 统计（只记数量与域数，绝不落 Cookie 值）：
     * 输入原始 `Set-Cookie`/`Cookie` 头文本，输出如 `count=56 domains=3`。
     */
    fun cookieStats(rawCookies: String?): String {
        val raw = rawCookies?.takeIf { it.isNotBlank() } ?: return "count=0 domains=0"
        var count = 0
        val domains = HashSet<String>()
        raw.split(';').forEach { seg ->
            val kv = seg.trim()
            if (kv.isEmpty()) return@forEach
            when {
                kv.startsWith("domain=", true) -> domains.add(kv.substringAfter('=').trim())
                kv.startsWith("path=", true) || kv.startsWith("expires=", true) ||
                    kv.startsWith("max-age=", true) || kv.startsWith("samesite=", true) ||
                    kv.startsWith("secure", true) || kv.startsWith("httponly", true) -> Unit
                kv.contains('=') -> count++
            }
        }
        return "count=$count domains=${domains.size}"
    }

    /** 清理控制字符，避免破坏日志格式。 */
    fun flatten(text: String): String = text.replace(Regex("[\\r\\n]+"), " ⏎ ").take(2000)
}
