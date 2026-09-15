package com.kuaixia.app.data.image

/**
 * HTML `srcset` / `<source srcset>` 字符串拆分（纯逻辑，可 JVM 单测）。
 *
 * 按 HTML spec 的近似实现：
 * - 候选串以逗号分隔；URL 内不允许未转义逗号（规范如此），故直接按逗号切分是安全的近似；
 * - 每条候选 = URL +（空白 + 描述符…）；取首个空白前的段为 URL，其余为描述符；
 * - 描述符支持 `480w`（提供候选宽度），`2x`/`1x` 等密度描述符不产出宽度；
 * - 过滤 `data:` / `blob:`（页面会话内数据，不可作下载源）；`javascript:` 同理丢弃；
 * - malformed/空段直接跳过，不抛异常。
 *
 * 只负责“拆出候选 URL + 宽度提示”，**不做绝对化**（绝对化由 [DomImageGroup] 统一处理）。
 */
object SrcsetSplitter {

    /** 单条 srcset 候选。 */
    data class Entry(val url: String, val widthPx: Int?)

    private val W_DESC = Regex("""(\d{2,5})w""")

    private val SKIP_PREFIX = listOf("data:", "blob:", "javascript:")

    fun split(srcset: String?): List<Entry> {
        if (srcset.isNullOrBlank()) return emptyList()
        val out = mutableListOf<Entry>()
        for (raw in srcset.split(',')) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val sp = trimmed.indexOfFirst { it == ' ' || it == '\t' }
            val url: String
            val desc: String
            if (sp >= 0) {
                url = trimmed.substring(0, sp).trim()
                desc = trimmed.substring(sp + 1).trim()
            } else {
                url = trimmed
                desc = ""
            }
            if (url.isEmpty()) continue
            if (SKIP_PREFIX.any { url.startsWith(it) }) continue
            // malformed 过滤：既非 http(s) 也不含 '.'/'/' 的片段（纯描述符“480w”、碎片“broken”等）不是 URL
            if (!url.startsWith("http://") && !url.startsWith("https://") &&
                url.indexOf('.') < 0 && url.indexOf('/') < 0
            ) continue
            val width = W_DESC.find(desc)?.groupValues?.get(1)?.toIntOrNull()
            out.add(Entry(url, width))
        }
        return out
    }
}
