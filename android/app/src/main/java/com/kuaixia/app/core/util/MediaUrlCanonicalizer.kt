package com.kuaixia.app.core.util

/**
 * 媒体 URL 规范化（纯 Kotlin，可 JVM 单测）。
 *
 * 用途（不改变实际请求 URL，仅用于去重 / 缓存 / 剪贴板判断 / 任务识别）：
 * - 丢弃抖音等分享链接常见的追踪参数（region/mid/u_code/did/iid/previous_page/…）；
 * - 丢弃 fragment；
 * - 保留参数排序后剩余查询参数，使同一作品不同参数顺序/多余参数的 URL 归并；
 * - 去尾斜杠（保留根路径 "/"）。
 */
object MediaUrlCanonicalizer {

    /** 与内容无关的追踪/会话参数（白名单式丢弃，避免误删作品相关参数）。 */
    private val DROP_QUERY_KEYS = setOf(
        "region", "mid", "u_code", "did", "iid", "previous_page",
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "from", "seo_id", "tab_type", "enter_from", "share_medium", "share_plat",
        "share_source", "app", "ts", "timestamp", "callback", "log_pb",
        "channel", "source", "lang", "device_platform", "aid", "platform",
        "vd_source", "m_source", "sec_uid", "search_id",
    )

    /**
     * 规范化 URL。失败或非 http(s) 时原样返回（去空白）。
     * 例：
     * `https://www.douyin.com/video/123?region=CN&mid=9&x=1` → `https://www.douyin.com/video/123?x=1`
     */
    fun canonical(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return trimmed
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return trimmed

        // 去 fragment
        val noFrag = if (trimmed.contains('#')) {
            trimmed.substring(0, trimmed.indexOf('#'))
        } else trimmed
        // 拆 query
        val qIdx = noFrag.indexOf('?')
        var base = if (qIdx >= 0) noFrag.substring(0, qIdx) else noFrag
        // 去尾斜杠（保留根）
        if (base.length > schemeEnd + 3 && base.endsWith("/")) {
            base = base.removeSuffix("/")
        }
        if (qIdx < 0) return base

        val kept = noFrag.substring(qIdx + 1)
            .split('&')
            .filter { it.isNotBlank() }
            .filter { pair -> keyOf(pair) !in DROP_QUERY_KEYS }
            .sorted()
        return if (kept.isEmpty()) base else "$base?" + kept.joinToString("&")
    }

    private fun keyOf(pair: String): String {
        val eq = pair.indexOf('=')
        return (if (eq >= 0) pair.substring(0, eq) else pair).lowercase()
    }
}
