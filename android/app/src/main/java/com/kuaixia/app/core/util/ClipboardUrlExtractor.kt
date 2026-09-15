package com.kuaixia.app.core.util

import java.net.URI

/**
 * 剪贴板 URL 提取与规范化（纯 Kotlin，可 JVM 单测）。
 *
 * 目标：从「整段分享文本」（URL + 中文描述/标点/表情/随机字符/换行…）中稳健提取干净 URL，
 * 不让用户手工清洗。提取采用**通用字符级规则**，不做「按平台删除后缀」特判：
 *
 * ```
 * 整段文本 → 候选扫描 → 规范化 → host 校验 → 平台识别 → 干净 URL
 * ```
 *
 * 截断规则（不依赖具体平台文案）：
 * - 扫描到 `http(s)://` 后逐字符取 URL 字符；遇到**终止字符**即结束：
 *   - 非 ASCII（中文/全角标点/emoji/其他语言）一律截断（合法 URL 不应含非 ASCII 原文）；
 *   - ASCII 中：空白、控制符、`"` `'` `<` `>` `(` `)` `,` 反引号 截断；
 *   - 其余 ASCII（字母数字与 `?=&%#/:._-~!$*;@[]` 等 RFC3986 合法字符）完整保留——
 *     因此 `https://youtube.com/shorts/xxx?si=…` 的 query 不被误删。
 * - `www.` 前缀仅在文本中没有 http(s) 时才兜底补 `https://`。
 *
 * host 校验：scheme 后必须存在非空且含 `.` 的 host（裸 `https://`、`03/27 :9pm` 之类不会被误判）。
 * 平台识别仅用于 UI/日志/后续选解析器，**不限制**未知站点 URL 的提取。
 */
object ClipboardUrlExtractor {

    enum class Platform(val label: String) {
        DOUYIN("douyin"),
        KUAISHOU("kuaishou"),
        XIAOHONGSHU("xiaohongshu"),
        BILIBILI("bilibili"),
        YOUTUBE("youtube"),
        UNKNOWN("unknown"),
    }

    data class Candidate(val url: String, val platform: Platform)

    // ---- 对外 API ----

    /** 提取全部候选（保持出现顺序、去重）。 */
    fun extractCandidates(text: String?): List<Candidate> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Candidate>()
        val seen = HashSet<String>()
        scanHttpUrls(text).forEach { url ->
            normalize(url)?.let { clean ->
                if (clean.isNotEmpty() && seen.add(clean)) {
                    out += Candidate(clean, platformOf(clean))
                }
            }
        }
        if (out.isEmpty()) {
            // 无 http(s) 时兜底 www.
            scanWww(text).forEach { www ->
                val url = "https://$www"
                normalize(url)?.let { clean ->
                    if (clean.isNotEmpty() && seen.add(clean)) {
                        out += Candidate(clean, platformOf(clean))
                    }
                }
            }
        }
        return out
    }

    fun extract(text: String?): List<String> = extractCandidates(text).map { it.url }

    /** 首个候选（首页「检测到剪贴板链接」用）。 */
    fun first(text: String?): Candidate? = extractCandidates(text).firstOrNull()

    /**
     * 清洗用户输入：若整段文本含 URL → 返回首个干净 URL；否则原样 trim 返回。
     * （解析框允许直接粘贴整段分享文案。）
     */
    fun cleanInput(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val candidate = first(raw)?.url
        return if (candidate != null) candidate else raw.trim()
    }

    // ---- 平台识别（仅展示/日志用，不限制提取） ----

    fun platformOf(url: String?): Platform {
        val host = runCatching { URI(url ?: "").host }.getOrNull()?.lowercase() ?: return Platform.UNKNOWN
        return when {
            host == "v.douyin.com" || host.endsWith(".douyin.com") || host.endsWith(".iesdouyin.com") ->
                Platform.DOUYIN
            host == "v.kuaishou.com" || host.endsWith(".kuaishou.com") || host.endsWith(".chenzhongtech.com") ->
                Platform.KUAISHOU
            host == "xhslink.cn" || host.endsWith(".xhslink.cn") || host.endsWith(".xiaohongshu.com") ->
                Platform.XIAOHONGSHU
            host == "b23.tv" || host.endsWith(".bilibili.com") || host.endsWith(".biliintl.com") ->
                Platform.BILIBILI
            host == "youtu.be" || host.endsWith(".youtube.com") || host.endsWith(".youtube-nocookie.com") ->
                Platform.YOUTUBE
            else -> Platform.UNKNOWN
        }
    }

    // ---- 内部 ----

    private fun scanHttpUrls(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if ((c == 'h' || c == 'H') && hasSchemeAt(text, i)) {
                // 前一个字符若是字母/数字，避免把单词内部（如 xhttps://）误当起点
                if (i > 0 && text[i - 1].isLetterOrDigit()) {
                    i++
                    continue
                }
                val schemeLen = if (regionMatches(text, i, "https://")) 8 else if (regionMatches(text, i, "http://")) 7 else -1
                if (schemeLen > 0) {
                    var j = i + schemeLen
                    while (j < n && isUrlChar(text.codePointAt(j))) {
                        j++
                    }
                    if (j > i + schemeLen) {
                        out += text.substring(i, j)
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        return out
    }

    private fun scanWww(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (regionMatches(text, i, "www.")) {
                var j = i + 4
                while (j < n && isUrlChar(text.codePointAt(j))) {
                    j++
                }
                out += text.substring(i, j)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    private fun hasSchemeAt(text: String, i: Int): Boolean =
        regionMatches(text, i, "https://") || regionMatches(text, i, "http://")

    private fun regionMatches(text: String, start: Int, prefix: String): Boolean =
        text.length - start >= prefix.length &&
            text.regionMatches(start, prefix, 0, prefix.length, ignoreCase = true)

    /** URL 合法字符判定：ASCII 白名单内保留；非 ASCII/终止字符停止。 */
    private fun isUrlChar(cp: Int): Boolean {
        if (cp >= 0x80) return false // CJK/全角/emoji 等一律非 URL 原文
        val c = cp.toChar()
        if (c <= ' ' || c == 0x7f.toChar()) return false // 空白/控制
        return when (c) {
            '"', '\'', '<', '>', '(', ')', ',', '`' -> false // 终止：括号/引号/逗号
            else -> true // 字母数字与 ?=&%#/:._-~!$*;@[] 等均保留
        }
    }

    /** 最小规范化：trim + 去除首尾成对包裹字符。 */
    private fun normalize(raw: String): String? {
        var s = raw.trim()
        // 尾部逐层去除包裹性字符（「）】》》’" 等），不影响合法路径/query
        while (s.isNotEmpty() && s.last() in ")]}」』】》\"'’") {
            s = s.dropLast(1).trimEnd()
        }
        if (s.isEmpty()) return null

        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != "http" && scheme != "https") return null
        if (host.isNullOrEmpty()) return null
        if (host != "localhost" && !host.contains('.') && host != "127.0.0.1") return null
        return s
    }
}
