package com.kuaixia.app.data.download

/**
 * 下载文件命名（纯 Kotlin，可 JVM 单测）。
 *
 * 优先级：可用的作品标题 → 可用的作品 ID → 从 URL 路径推导 → 「快夏作品」。
 * 清洗非法字符并限长；URL/主机名形态的字符串一律视为「不可用」，
 * 避免出现 `https___www.iesdouyin.com_share_slides_...` 这类文件名。
 */
object MediaFileNaming {

    private const val MAX_LEN = 80

    /** 输出不含扩展名；扩展名由调用方拼接（实际 MIME 定稿）。 */
    fun baseName(title: String?, id: String?, webpageUrl: String?): String {
        for (candidate in listOf(title, id)) {
            if (usable(candidate)) return sanitize(candidate!!)
        }
        val derived = deriveFromUrl(webpageUrl)
        return if (usable(derived)) sanitize(derived!!) else "快夏作品"
    }

    private fun usable(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val t = s.trim()
        if (t.length > MAX_LEN * 2) return false
        // URL/主机名/路径形态 → 不可作为标题
        if (t.contains("://") || t.startsWith("http", ignoreCase = true)) return false
        if (t.contains('/') || t.contains('?') || t.contains('#')) return false
        // 纯主机名形态（webview 早期用 host 作 id）
        if (t.contains('.') && t.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) {
            return false
        }
        return true
    }

    /** 从 URL 路径取「最像作品标识」的段：优先数字段 / 较长段，忽略 share/video/note/slides 等导航词。 */
    private fun deriveFromUrl(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (!u.contains("://")) return null
        val path = runCatching { java.net.URI(u).path }.getOrNull() ?: return null
        if (path.isNullOrBlank()) return null
        val segments = path.split('/').filter {
            it.isNotBlank() && it != "." && it != ".."
        }
        if (segments.isEmpty()) return null
        val ignored = setOf("share", "video", "note", "slides", "user", "photo", "item")
        // 1) 优先纯数字段（抖音作品 id 等）
        segments.lastOrNull { it.all { ch -> ch.isDigit() } }?.let { return it }
        // 2) 其次忽略导航词后的最后一段
        val meaningful = segments.filterNot { it.lowercase() in ignored }
        if (meaningful.isNotEmpty()) {
            val last = meaningful.last().substringBefore('.').takeIf { it.isNotBlank() }
            if (last != null && last.length >= 4) return last
        }
        val lastAll = segments.last().substringBefore('.')
        return lastAll.takeIf { it.length >= 4 } ?: segments.last()
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim()
        return when {
            cleaned.length > MAX_LEN -> cleaned.substring(0, MAX_LEN)
            cleaned.isNotEmpty() -> cleaned
            else -> "快夏作品"
        }
    }
}
