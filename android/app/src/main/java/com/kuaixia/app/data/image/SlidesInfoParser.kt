package com.kuaixia.app.data.image

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 抖音 `/share/slides/` 页 slidesinfo 接口响应解析（纯逻辑，可 JVM 单测；无 Android 依赖）。
 *
 * 数据来源（真机取证已证实，2026-09-12）：
 *   `https://www.iesdouyin.com/web/api/v2/aweme/slidesinfo/`
 *   响应体：`aweme_details[0].images[i].url_list[j]`
 *   同一 `images[i]` 的 `url_list[*]` = 同一张作品图的多个 CDN 档
 *   （含 `tplv-dy-water-v2` 带水印档 与 `tplv-dy-aweme-images:q75.webp/.jpeg` 无水印档）。
 *
 * 只使用已真实标定的字段：`uri` / `url_list`（`width`/`height`/`mime`/`format` 不在此处消费，
 * 交由下游按 URL 推断/日志）。**不虚构** live/dynamic/animated/play_addr 等映射
 * （Live Photo 结构当前证据不足）。
 *
 * 输出复用 [PageJsonMerge.JsonImage]（与 PAGE_JSON note 页同构），下游直接走
 * [PageJsonMerge.merge] 归并 + 选优，不新建重复模型。
 */
object SlidesInfoParser {

    /** 解析 slidesinfo JSON → 作品图片列表（每张图 = 一个 JsonImage，urls = 该图多档 url_list）。 */
    fun parse(json: String): List<PageJsonMerge.JsonImage> {
        val root = runCatching {
            JSONTokener(json.trimStart('\uFEFF', ' ', '\n', '\r', '\t')).nextValue()
        }.getOrNull() as? JSONObject ?: return emptyList()

        val details = root.optJSONArray("aweme_details") ?: return emptyList()
        val first = details.optJSONObject(0) ?: return emptyList()
        val imagesArr = first.optJSONArray("images") ?: return emptyList()

        val out = ArrayList<PageJsonMerge.JsonImage>(imagesArr.length())
        for (i in 0 until imagesArr.length()) {
            val img = imagesArr.optJSONObject(i) ?: continue
            val urls = img.optJSONArray("url_list")?.let { arr ->
                buildList {
                    val seen = HashSet<String>()
                    for (j in 0 until arr.length()) {
                        val u = arr.optString(j)
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            if (seen.add(u)) add(u)
                        }
                    }
                }
            }.orEmpty()
            if (urls.isEmpty()) continue
            // objectKey 必须与「捕获候选」的 extractObjKey 同源（url_list 首条含 ~tplv → 干净对象段）；
            // 失败时退回 uri 末段（去掉 tos 目录前缀，得到与 ~tplv 段一致的对象 key）。
            val objKey = PageJsonMerge.extractObjKey(urls.first())
                ?: img.optString("uri").takeIf { it.isNotBlank() }?.substringAfterLast('/')
            out.add(PageJsonMerge.JsonImage(seq = out.size, objKey = objKey, urls = urls))
        }
        return out
    }
}
