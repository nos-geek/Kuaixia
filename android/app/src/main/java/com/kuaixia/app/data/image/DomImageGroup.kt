package com.kuaixia.app.data.image

import java.net.URI

/**
 * DOM `<img>` 元素级候选收集（纯逻辑，可 JVM 单测；无 Android 依赖）。
 *
 * 一个 `<img>` 元素 = 页面里的一张作品图；其全部候选 URL 来源：
 * `currentSrc`（浏览器当前选中）/ `src` / `srcset`（多尺寸）/ 常见懒加载
 * `data-src`、`data-original`、`data-url`、`data-lazy-src`。`<picture><source srcset>`
 * 的候选在 JS 采集端已并入该元素的 srcset 字段一并上报。
 *
 * 职责：
 * - 统一绝对化（[URI.resolve] 于 [Input.baseHref]）；
 * - 过滤 `data:`/`blob:`/`javascript:` 与非 http(s)；
 * - 按 URL 去重（保序）；
 * - 每条候选标注来源 [SRC_DOM]/[SRC_SRCSET]/[SRC_DATA]（供日志与评分排序）；
 * - 转 [ImageCandidate]（尺寸/水印提示来自 URL 推断，**绝不猜测无水印**）。
 *
 * 纪律：不生成 URL、不改写 URL、只收集页面真实提供的候选。
 */
object DomImageGroup {

    /** 候选来源标签（日志与 [ImageCandidate.source] 共用）。 */
    const val SRC_NETWORK = "NETWORK"
    const val SRC_DOM = "DOM_SRC"
    const val SRC_SRCSET = "DOM_SRCSET"
    const val SRC_DATA = "DOM_DATA"

    /** 一个 `<img>` 元素上报的原始素材（bridge 传入；空串=无）。 */
    data class Input(
        val src: String?,
        val currentSrc: String?,
        val srcset: String?,
        /** 懒加载属性值，以 U+001F 分隔（与 JS 端 join 约定一致）。 */
        val dataRaw: String?,
        /** 页面当前 location.href（绝对化的 base）。 */
        val baseHref: String?,
    ) {
        val dataUrls: List<String>
            get() = if (dataRaw.isNullOrBlank()) emptyList()
            else dataRaw.split('\u001F').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 归组后的单条候选。 */
    data class Entry(val url: String, val source: String, val widthPx: Int?)

    /** JS 侧采集的懒加载属性名（仅文档；取值由 JS 完成并随 dataRaw 传入）。 */
    val LAZY_ATTRS = listOf("data-src", "data-original", "data-url", "data-lazy-src")

    private val NON_HTTP_PREFIX = listOf("data:", "blob:", "javascript:")

    /** 解析并去重元素级候选 URL（保序）。base 不可解析时返回空列表。 */
    fun collect(input: Input): List<Entry> {
        if (input.baseHref.isNullOrBlank()) return emptyList()
        val base = runCatching { URI(input.baseHref) }.getOrNull() ?: return emptyList()

        val out = linkedMapOf<String, Entry>()

        fun abs(u: String?, label: String, widthPx: Int? = null) {
            if (u.isNullOrBlank()) return
            if (NON_HTTP_PREFIX.any { u.startsWith(it) }) return
            val resolved = runCatching { base.resolve(u).toString() }.getOrNull() ?: return
            if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return
            out.putIfAbsent(resolved, Entry(resolved, label, widthPx))
        }

        // currentSrc 优先（浏览器真实选中档），随后 src / srcset / data-*
        abs(input.currentSrc, SRC_DOM)
        abs(input.src, SRC_DOM)
        SrcsetSplitter.split(input.srcset).forEach { abs(it.url, SRC_SRCSET, it.widthPx) }
        input.dataUrls.forEach { abs(it, SRC_DATA) }
        return out.values.toList()
    }

    /** 元素级候选 → [ImageCandidate] 列表（供 ImageCandidateScorer 组内选优）。 */
    fun buildCandidates(entries: List<Entry>): List<ImageCandidate> = entries.map { e ->
        val (w, h) = ImageUrlHints.sizeFromUrl(e.url)
        ImageCandidate(
            url = e.url,
            width = w ?: e.widthPx,
            height = h,
            watermarked = ImageUrlHints.watermarkHint(e.url),
            source = e.source,
        )
    }

    /** 组内选优：无水印/未知 优先于带水印，同档比分辨率。组空返回 null。 */
    fun pickBest(entries: List<Entry>): Entry? =
        ImageCandidateScorer.pickBest(buildCandidates(entries))?.let { best ->
            entries.firstOrNull { it.url == best.url }
        }
}
