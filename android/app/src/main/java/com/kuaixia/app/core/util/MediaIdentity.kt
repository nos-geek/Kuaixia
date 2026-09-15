package com.kuaixia.app.core.util

/**
 * 作品身份（identity）解析（纯 Kotlin，可 JVM 单测）。
 *
 * 背景（BUG-002）：同一个抖音作品可以用多种 URL 形态进入剪贴板——
 * - 短链：`https://v.douyin.com/6kAjxhW68PA`（本身不含作品 ID）
 * - 长链：`https://www.iesdouyin.com/share/slides/7683858155142507429?...`
 * - 长链 + 任意分享参数（`activity_info` / `share_sign` / `ug_share_id` / `with_sec_did` …）
 *
 * [MediaUrlCanonicalizer] 只能做「URL 字符串级」归并（去 fragment / 去白名单 query / 排序），
 * 它永远无法把短链与长链归一。因此去重需要一层**作品身份级**的 key：
 *
 * - 能从 URL 直接提取作品 ID（如长链）→ identity = `douyin:aweme:<awemeId>`，**与 query 无关**；
 * - 不能提取（如短链、非抖音）→ 退化为 [MediaUrlCanonicalizer.canonical]（保持既有行为）。
 *
 * 短链第一次出现时无法凭字符串得知作品 ID，因此由调用方在**解析成功后**
 * 用最终网页 URL 登记别名（见 [ClipboardIdentityBook.registerResolved]）。
 *
 * 规则表驱动（[IdRule]），新增平台只需加一条规则，不做 `if (platform == ...)` 硬编码。
 */
object MediaIdentity {

    /** 抖音作品身份前缀。identity = 前缀 + 作品 ID。 */
    const val DOUYIN_PREFIX = "douyin:aweme:"

    /** 抖音分享长链里不影响身份的参数（仅供调用方参考/日志裁剪，identity 本身已与 query 无关）。 */
    private const val ID_GROUP = """(\d{6,25})"""

    /**
     * 一条「作品 ID」提取规则。
     *
     * @param hostSuffixes 命中的主机后缀（精确相等或以 `.<suffix>` 结尾）
     * @param pathRegexes  路径提取正则，**第 1 个捕获组**为作品 ID
     * @param queryKeys    兜底：从这些 query 参数取纯数字 ID
     * @param prefix       identity 前缀
     */
    private data class IdRule(
        val hostSuffixes: List<String>,
        val pathRegexes: List<Regex>,
        val queryKeys: List<String>,
        val prefix: String,
    )

    private val RULES = listOf(
        IdRule(
            hostSuffixes = listOf("douyin.com", "iesdouyin.com"),
            pathRegexes = listOf(
                Regex("(?i)/share/(?:slides|video|note|mix|music|photo)/$ID_GROUP"),
                Regex("(?i)/(?:slides|video|note|item)/$ID_GROUP"),
            ),
            queryKeys = listOf("modal_id", "aweme_id"),
            prefix = DOUYIN_PREFIX,
        ),
    )

    /** 别名条目分隔符（不可见字符，避免与 URL/ID 冲突）。 */
    private const val ALIAS_SEP = "\u0001"

    /** 别名表上限（防 DataStore 无限膨胀）。 */
    const val MAX_ALIASES = 200

    /**
     * 计算 URL 的作品身份：能提取作品 ID 时用 `prefix + id`，否则退化为 canonical URL。
     *
     * 例：
     * - `https://www.iesdouyin.com/share/slides/7683858155142507429?activity_info=x` → `douyin:aweme:7683858155142507429`
     * - `https://v.douyin.com/6kAjxhW68PA` → `https://v.douyin.com/6kAjxhW68PA`（短链无 ID）
     * - `https://example.com/a/b` → canonical 结果（非抖音，行为不变）
     */
    fun identityOf(url: String): String {
        val m = match(url) ?: return MediaUrlCanonicalizer.canonical(url)
        return m.prefix + m.id
    }

    /** 直接可提取的作品 ID（如抖音 awemeId）；无法提取返回 null。 */
    fun contentIdOf(url: String): String? = match(url)?.id

    /** identity 类型：`aweme` = 作品 ID 级；`url` = 退化到 URL 字符串级。供日志/诊断使用。 */
    fun identityTypeOf(identity: String?): String =
        if (awemeIdFromIdentity(identity) != null) "aweme" else "url"

    /**
     * 从 identity 反解作品 ID（仅 `prefix:id` 形态可解，否则 null）。供日志使用。
     *
     * 注意：URL 形态的 identity（如 `https://…`）也含 `:`，因此必须校验
     * 前缀不含 `/` 且末段全为数字，否则会误判。
     */
    fun awemeIdFromIdentity(identity: String?): String? {
        if (identity.isNullOrBlank()) return null
        val idx = identity.lastIndexOf(':')
        if (idx <= 0 || idx >= identity.length - 1) return null
        if (identity.substring(0, idx).contains('/')) return null // URL 形态，不是 identity
        val id = identity.substring(idx + 1)
        return id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /**
     * 结合别名表解析身份：先查别名（短链登记过 → 直接拿到作品身份），
     * 否则按 [identityOf] 计算。
     *
     * @param aliases canonical(URL) → identity
     */
    fun resolve(url: String, aliases: Map<String, String>): String {
        val key = MediaUrlCanonicalizer.canonical(url)
        return aliases[key] ?: identityOf(url)
    }

    /** 别名表序列化为 DataStore 可存的 StringSet。 */
    fun encodeAliases(aliases: Map<String, String>): Set<String> {
        if (aliases.isEmpty()) return emptySet()
        val entries = aliases.entries.toList()
        val from = (entries.size - MAX_ALIASES).coerceAtLeast(0)
        return entries.subList(from, entries.size)
            .mapTo(LinkedHashSet()) { "${it.key}$ALIAS_SEP${it.value}" }
    }

    /** 反序列化别名表（脏数据自动跳过）。 */
    fun decodeAliases(raw: Set<String>?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (entry in raw) {
            val i = entry.indexOf(ALIAS_SEP)
            if (i <= 0 || i >= entry.length - 1) continue
            val key = entry.substring(0, i)
            val value = entry.substring(i + 1)
            if (key.isBlank() || value.isBlank()) continue
            out[key] = value
        }
        return out
    }

    // ================================ 内部实现 ================================

    private data class Match(val prefix: String, val id: String)

    private fun match(url: String): Match? {
        val host = hostOf(url) ?: return null
        val rule = RULES.firstOrNull { r ->
            r.hostSuffixes.any { host == it || host.endsWith(".$it") }
        } ?: return null

        val path = pathOf(url)
        if (!path.isNullOrBlank()) {
            for (re in rule.pathRegexes) {
                val id = re.find(path)?.groupValues?.getOrNull(1)
                if (!id.isNullOrBlank()) return Match(rule.prefix, id)
            }
        }
        for (key in rule.queryKeys) {
            val id = queryParam(url, key)
            if (!id.isNullOrBlank() && id.all(Char::isDigit) && id.length >= 6) {
                return Match(rule.prefix, id)
            }
        }
        return null
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url.trim()).host?.lowercase()
    }.getOrNull()

    private fun pathOf(url: String): String? = runCatching {
        java.net.URI(url.trim()).path
    }.getOrNull()

    private fun queryParam(url: String, key: String): String? {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return null
        val query = url.substring(qIdx + 1).substringBefore('#')
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (!pair.substring(0, eq).equals(key, ignoreCase = true)) continue
            return pair.substring(eq + 1).trim()
        }
        return null
    }
}
