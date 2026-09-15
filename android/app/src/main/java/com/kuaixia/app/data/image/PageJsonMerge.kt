package com.kuaixia.app.data.image

import com.kuaixia.app.data.model.ImageResource

/**
 * PAGE_JSON 图片候选归并（纯逻辑，可 JVM 单测；无 Android 依赖）。
 *
 * 数据源：页面 JSON（如 `window._ROUTER_DATA → …videoInfoRes.item_list[*].images[*].url_list[*]`），
 * 由 WebViewParser 在页面存活时读取后传入。
 *
 * 语义：
 * - `images[i]` = 作品第 i 张图；其 `url_list[*]` = 同一张图的多个 CDN 镜像/质量档（**不是多张图**）；
 * - NETWORK/DOM 捕获的同 object key URL（540 展示图等）与 PAGE_JSON 候选归为**同一张图**的候选组；
 * - 组内经 [ImageCandidateScorer] 选优后**每张作品图只输出 1 个 URL**；输出顺序 = JSON `images[]` 顺序。
 *
 * 纪律：不生成/魔改 URL；只使用页面 JSON 真实提供的 url_list 与真实捕获 URL；
 * `download_url_list`（water 版）由调用方不传入，本逻辑不感知。
 */
object PageJsonMerge {

    /** 来源标签。 */
    const val SRC_PAGE_JSON = "PAGE_JSON"

    /** JSON 中的一张作品图（seq 为作品内全序序号）。 */
    data class JsonImage(val seq: Int, val objKey: String?, val urls: List<String>)

    /** 已捕获候选（DOM/NETWORK 等，来源仅用于日志）。 */
    data class Captured(val url: String, val source: String)

    /**
     * 从 URL 提取资源 key（用于同图归组）：
     * - 含 `~tplv`：取「~tplv 之前」的路径末段（如 `oMAAy…~tplv-…webp` → `oMAAy…`）；
     * - 不含 `~tplv`（原图类 `/obj/tos-…/hash`）：保留资源目录+末段用于关联。
     */
    fun extractObjKey(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val noQuery = url.substringBefore('?').substringBefore('#')
        val scheme = noQuery.indexOf("://")
        val path = if (scheme >= 0) {
            val slash = noQuery.indexOf('/', scheme + 3)
            if (slash >= 0) noQuery.substring(slash) else ""
        } else noQuery
        val ti = path.indexOf("~tplv")
        val seg = path.substring(path.lastIndexOf('/') + 1)
        return if (ti >= 0) {
            seg.substringBefore("~tplv").substringBefore('.').ifEmpty { null }
        } else {
            path.removePrefix("/obj/").take(64).ifEmpty { null }
        }
    }

    /**
     * 归并：PAGE_JSON 权威（存在非空时优先于旧扁平链）。
     * 每张 JSON 图按 objKey 吸收同图捕获候选后组内选优；无候选的图跳过。
     * @return 顺序与 `json` 一致的最终图片列表（每组一张）。
     */
    fun merge(json: List<JsonImage>, captured: List<Captured>): List<ImageResource> {
        if (json.isEmpty()) return emptyList()
        val capturedByKey = captured.groupBy { extractObjKey(it.url) }
        val out = ArrayList<ImageResource>(json.size)
        for (img in json) {
            val cands = ArrayList<ImageCandidate>(img.urls.size + 4)
            for (u in img.urls) {
                if (!isHttp(u)) continue
                val (w, h) = ImageUrlHints.sizeFromUrl(u)
                cands.add(
                    ImageCandidate(
                        url = u,
                        width = w,
                        height = h,
                        watermarked = ImageUrlHints.watermarkHint(u),
                        source = SRC_PAGE_JSON,
                    ),
                )
            }
            capturedByKey[img.objKey]?.forEach { c ->
                val (w, h) = ImageUrlHints.sizeFromUrl(c.url)
                cands.add(
                    ImageCandidate(
                        url = c.url,
                        width = w,
                        height = h,
                        watermarked = ImageUrlHints.watermarkHint(c.url),
                        source = c.source,
                    ),
                )
            }
            val best = ImageCandidateScorer.pickBest(cands) ?: continue
            val (hintExt, hintMime) = urlImageHint(best.url)
            out.add(ImageResource(url = best.url, mimeType = hintMime, extension = hintExt, httpHeaders = emptyMap()))
        }
        return out
    }

    private fun isHttp(u: String): Boolean =
        u.startsWith("http://") || u.startsWith("https://")

    private val EXT = listOf("jpg", "jpeg", "png", "webp", "avif", "gif", "apng", "heic", "heif")

    private fun urlImageHint(url: String): Pair<String?, String?> {
        val path = url.substringBefore('?').lowercase()
        for (e in EXT) {
            if (path.endsWith(".$e")) {
                val mime = when (e) {
                    "jpg", "jpeg" -> "image/jpeg"
                    else -> "image/$e"
                }
                return e to mime
            }
        }
        return null to null
    }
}
