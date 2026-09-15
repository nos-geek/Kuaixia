package com.kuaixia.app.data.image

/**
 * 图片「多资源候选」纯逻辑（可 JVM 单测；无 Android 依赖）。
 *
 * 目标（供后续同图多 URL 归组后使用）：同一张作品图片可能有多个派生 URL
 * （展示图/带水印/大图/原图），选择目标为「无水印 + 尽可能高可用画质」。
 *
 * 纪律：
 * - 只对「页面/网络真实提供的 URL」做排序；绝不生成/魔改 URL；
 * - 水印判定只有三态：确认真实带水印(true)、未知(null)、确认真实无水印(false)；
 *   只有当数据源明确（模板名经真实样本标定）才给 true/false，否则 null。
 */
data class ImageCandidate(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    /** true=带水印；false=无水印；null=未知。 */
    val watermarked: Boolean? = null,
    /** 候选来源：NETWORK / DOM / JSON / OTHER。 */
    val source: String = "NETWORK",
) {
    val resolution: Long? get() = width?.let { w -> height?.let { h -> w.toLong() * h } }
}

object ImageCandidateScorer {

    /**
     * 从同一作品图片的多 URL 候选中选出最佳。
     * 排序规则（综合，不做机械的“只要无水印”/“只要最高分辨率”）：
     *  1) 水印档：无水印(false) > 未知(null) > 带水印(true)；
     *  2) 同档内：分辨率高者优先（width×height）；
     *  3) 仍并列：URL 更短者（更可能为原图/直链，而非带复杂签名的派生）优先。
     * 水印为未知(null)时仅与同档比较，不因“未知”而压过已确认无水印的低分辨率候选。
     */
    fun pickBest(candidates: List<ImageCandidate>): ImageCandidate? {
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull { a, b -> compare(a, b) }
    }

    /** a 是否优于 b（正值 = a 更好，供 maxWithOrNull 使用）。 */
    fun compare(a: ImageCandidate, b: ImageCandidate): Int {
        val wm = watermarkRank(b.watermarked) - watermarkRank(a.watermarked)
        if (wm != 0) return wm
        // 同水印档内：已真机标定的无水印档模板优先级（aweme-images webp > jpeg > 其余），
        // 作为 watermark 之后、分辨率之前的二级 tiebreak；不改变「水印档 > 分辨率」主规则。
        val tpl = templateRank(b.url) - templateRank(a.url)
        if (tpl != 0) return tpl
        val res = (a.resolution ?: 0L).compareTo(b.resolution ?: 0L)
        if (res != 0) return res
        return b.url.length.compareTo(a.url.length)
    }

    /** 同水印档内模板优先级（值越小越优先）：aweme-images:q75.webp(0) > jpeg/jpg(1) > 其余(2)。 */
    private fun templateRank(url: String): Int {
        val u = url.lowercase()
        if (!u.contains("aweme-images")) return 2
        return if (u.contains(".webp")) 0 else 1
    }

    private fun watermarkRank(w: Boolean?): Int = when (w) {
        false -> 0 // 无水印最高
        null -> 1 // 未知其次
        true -> 2 // 带水印最后
    }
}

/** URL 提示解析：模板/水印/尺寸（仅辅助候选注解与排序；不作为下载源改写依据）。 */
object ImageUrlHints {

    /** 已确认带水印的模板特征；返回 null = 未知（不做“推测无水印”）。
     *  识别：显式 watermark 词 + CDN 模板段中的 `-water:`/`_water:`/`-water.`（如
     *  `~tplv-dy-lqen-new-water:1440:…` 属水印档，不应与同名无水印档并列时被误判未知）。 */
    fun watermarkHint(url: String?): Boolean? {
        val u = url?.lowercase() ?: return null
        if (u.contains("water-v2") || u.contains("watermark") || u.contains("~tplv-dy-water")) {
            return true
        }
        if (WATER_SEG.containsMatchIn(u)) return true
        // 已真机标定（像素级 A/B，2026-09-12）的无水印档：slidesinfo 的 aweme-images 模板
        // （同尺寸、底部干净无水印）。仅此一处给 false，其余仍 null（不猜无水印）。
        if (u.contains("aweme-images")) return false
        return null
    }

    private val WATER_SEG = Regex("""[-_/]water([:.]|$)|water[-_/]""")

    /** 尽力从 URL 解析尺寸（null=无法解析）。来源标记为「URL推断」。
     *  w/h token 需左侧为非字母数字边界，避免把 `…new:1440` 的尾字母 w 误当 width 标记。 */
    fun sizeFromUrl(url: String?): Pair<Int?, Int?> {
        val u = url?.lowercase() ?: return null to null
        val w = Regex("""(?:^|[^a-z0-9])w[-_=:.](\d{2,5})""").find(u)?.groupValues?.get(1)?.toIntOrNull()
        val h = Regex("""(?:^|[^a-z0-9])h[-_=:.](\d{2,5})""").find(u)?.groupValues?.get(1)?.toIntOrNull()
        if (w != null || h != null) return w to h
        val x = Regex("""(\d{3,5})[x×:](\d{3,5})""").find(u)
        if (x != null) {
            val a = x.groupValues[1].toIntOrNull()
            val b = x.groupValues[2].toIntOrNull()
            if (a != null && b != null) return a to b
        }
        return null to null
    }
}
