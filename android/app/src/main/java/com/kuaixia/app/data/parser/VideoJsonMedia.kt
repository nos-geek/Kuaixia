package com.kuaixia.app.data.parser

/**
 * P8.1：VIDEO 页 PAGE_JSON（videoInfoRes.item_list[].video）视频候选的纯逻辑选择（可 JVM 单测）。
 *
 * 纪律：
 * - 只消费页面真实提供的 HTTP/HTTPS URL（来自 video.play_addr.url_list / download_addr.url_list）；
 * - 只过滤/去重/标注，**不改写任何 URL、不猜测无水印**；
 * - playwm 等带水印语义路径只标注 watermarkHint，绝不自动视为无水印；
 * - 无候选 → 返回空（调用方保持 NO_VIDEO → yt-dlp fallback）。
 */
internal data class VideoJsonCandidate(
    val field: String,
    val url: String,
) {
    /** 保守水印提示：路径含 playwm 等仅作「含水印语义」提示；不含也不等于「无水印」（未证实）。 */
    val watermarkHint: Boolean get() = url.contains("playwm")
}

internal object VideoJsonMedia {

    /**
     * 选择可作为 VIDEO 前置候选的 URL：
     * @param refs        按字段序的 (field, url)（页面真实顺序：play_addr 先于 download_addr）
     * @param capturedUrls 已捕获候选 URL（网络/PROBE_JS），冲突者跳过（不重复入库）
     * @return 空列表表示无可靠新候选（调用方保持 yt-dlp fallback 语义）
     */
    fun collect(
        refs: List<Pair<String, String>>,
        capturedUrls: Collection<String>,
    ): List<VideoJsonCandidate> {
        val seen = HashSet<String>()
        capturedUrls.forEach { seen += it.substringBefore('#') }
        val out = ArrayList<VideoJsonCandidate>(refs.size)
        for ((field, rawUrl) in refs) {
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) continue
            val key = rawUrl.substringBefore('#')
            if (!seen.add(key)) continue
            out.add(VideoJsonCandidate(field, key))
        }
        return out
    }
}
