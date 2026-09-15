package com.kuaixia.app.data.image

import com.kuaixia.app.data.model.ImageResource
import java.util.Locale

/**
 * Douyin 图片 CDN「坏 Host」刷新策略（纯逻辑，可 JVM 单测；无 Android 依赖）。
 *
 * 背景（真机证据，2026-09-11）：
 * - 图文/实况页的图片直链由**上游每次页面加载动态分配** `pN-sign.douyinpic.com` 之一；
 * - 其中 `p96-sign.douyinpic.com` 是坏 Host：Chromium 侧 `img.complete=1` 但
 *   `naturalWidth=0`（28/28 失败），App OkHttp 侧 403（25/25 失败）；非 p96 两通道均成功；
 * - 快夏对图片 URL **零改写**，因此唯一的合法规避方式是**重新加载同一个页面**，
 *   让上游重新下发一批 URL，再按 object key 对齐替换。
 *
 * 本对象只做三件事，全部为纯函数：
 * 1. 识别坏 Host（[isP96]）；
 * 2. 统计坏 Host 数量（[countP96]）；
 * 3. 把刷新结果按 object key 合并进当前结果（[merge]）——**只替换坏 Host 项，且新 URL 必须来自
 *    上游真实返回且不是坏 Host**；绝不拼接/改写 URL 字符串。
 *
 * 纪律：不生成 URL、不替换 Host 字符串、不改 query/签名/tpl；刷新结果条数与原始不一致时整次作废。
 */
object P96RefreshPolicy {

    /** 允许的最大刷新次数（第一次解析不计入）。 */
    const val MAX_ATTEMPTS = 3

    /** 当前已知的坏 Host（仅此一个；`water-v2` 是资源版本语义，与 Host 无关，不参与判断）。 */
    const val BAD_HOST = "p96-sign.douyinpic.com"

    /** 单条替换记录（仅用于日志，不含签名）。 */
    data class Replacement(val key: String, val oldHost: String, val newHost: String)

    /** 合并结果。 */
    data class MergeResult(
        /** 合并后的图片列表（顺序与 [current] 一致；本条为 accepted 时的结果）。 */
        val images: List<ImageResource>,
        /** 本次刷新是否被采纳（条数一致才算有效）。 */
        val accepted: Boolean,
        /** 实际被替换的条数。 */
        val replaced: Int,
        /** 合并后仍为坏 Host 的条数。 */
        val finalP96: Int,
        /** 替换明细（供 INFO 日志逐条输出）。 */
        val replacements: List<Replacement> = emptyList(),
    )

    /** 从 URL 取 host（纯字符串解析；失败返回空串）。 */
    fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        if (start >= url.length) return ""
        val slash = url.indexOf('/', start)
        val end = if (slash < 0) url.length else slash
        val authority = url.substring(start, end).substringAfterLast('@')
        return authority.substringBefore(':').lowercase(Locale.ROOT)
    }

    /** 是否坏 Host（严格等于 [BAD_HOST]）。 */
    fun isP96(url: String?): Boolean = url != null && hostOf(url) == BAD_HOST

    /** 统计坏 Host 条数。 */
    fun countP96(images: List<ImageResource>): Int = images.count { isP96(it.url) }

    /**
     * 按 object key 合并刷新结果。
     *
     * 规则：
     * - `refreshed.size != expectedCount` → **整次作废**（`accepted=false`，返回原列表）；
     * - 仅当「当前项是坏 Host」且「刷新结果中同 object key 的 URL 不是坏 Host」时才替换；
     * - 当前项不是坏 Host → 原样保留（即使刷新给了别的可用 Host，也不无意义替换）；
     * - 刷新结果同 key 重复 → 取**首次出现**（保证 deterministic，且不会产出重复图片）；
     * - 找不到同 key / 新 URL 仍是坏 Host → 保留当前项（绝不丢图）。
     *
     * @param current 当前结果（第一轮解析产物，或被上一次刷新替换后的结果）
     * @param refreshed 本次刷新得到的图片列表（必须来自新的页面加载）
     * @param expectedCount 期望条数（= 第一次解析的图片数）
     */
    fun merge(
        current: List<ImageResource>,
        refreshed: List<ImageResource>,
        expectedCount: Int,
    ): MergeResult {
        if (refreshed.size != expectedCount) {
            return MergeResult(
                images = current,
                accepted = false,
                replaced = 0,
                finalP96 = countP96(current),
            )
        }

        // 首次出现优先，保证 deterministic（同 key 重复时不会因顺序抖动而改变结果）
        val byKey = LinkedHashMap<String, ImageResource>()
        for (img in refreshed) {
            val key = PageJsonMerge.extractObjKey(img.url) ?: continue
            if (!byKey.containsKey(key)) byKey[key] = img
        }

        val out = ArrayList<ImageResource>(current.size)
        val reps = ArrayList<Replacement>()
        var replaced = 0
        for (img in current) {
            if (!isP96(img.url)) {
                out.add(img)
                continue
            }
            val key = PageJsonMerge.extractObjKey(img.url)
            val candidate = if (key == null) null else byKey[key]
            if (candidate == null || candidate.url == img.url || isP96(candidate.url)) {
                out.add(img)
                continue
            }
            out.add(candidate)
            replaced++
            reps.add(Replacement(key ?: "?", hostOf(img.url), hostOf(candidate.url)))
        }
        return MergeResult(
            // 未发生替换时直接返回入参列表（保持同一实例，调用方可据此判断"无需重建结果"）
            images = if (replaced == 0) current else out,
            accepted = true,
            replaced = replaced,
            finalP96 = countP96(out),
            replacements = reps,
        )
    }
}
