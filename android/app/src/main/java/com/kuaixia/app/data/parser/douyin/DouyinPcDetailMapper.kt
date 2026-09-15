package com.kuaixia.app.data.parser.douyin

import com.kuaixia.app.data.parser.VideoJsonCandidate
import com.kuaixia.app.data.parser.VideoJsonMedia
import com.kuaixia.app.data.parser.VideoJsonRef
import java.util.Locale
import org.json.JSONObject

/**
 * 抖音 PC 形态捕获结果 → 候选 的**纯逻辑**映射（无 Android 依赖，可 JVM 单测）。
 *
 * 数据来源：`DouyinPcProbeScript.EXTRACT_JS` 回传的紧凑 JSON（源自页面**自身**已发生的
 * `aweme/v1/web/aweme/detail/` 响应，本链路不发起任何请求）。
 *
 * ## 规则（与移动链路 P8.2 保持一致）
 * - `bit_rate[]`（数组时）逐档 → 候选：`gear_name` / `width` / `height` / `bit_rate`(bps→由 [VideoJsonMedia] 归一) / `data_size` / `play_addr.url_list`；
 * - `play_addr`、`download_addr` → 追加为兜底候选（顺序在 `bit_rate` 之后，使档位元数据在合并时优先保留）；
 * - **不禁用、不改写、不替换**任何 URL（`playwm` 只由 [VideoJsonCandidate.watermarkHint] 保守标注）；
 * - 去重/合并/档位规整一律复用 [VideoJsonMedia.collectRefs]（与移动链路同一实现）。
 *
 * ## height 与 quality
 * - `height` 已知 → `quality = "${height}P"`（见 [qualityOf]；与 UI 分组 label、下载任务 `quality` 三者一致）；
 * - `height` 未知 → 保持旧的「URL 推断」兜底（[DouyinPcDetailMapper.qualityOf] 内部调用 `qualityHintFromUrl`）。
 */
internal object DouyinPcDetailMapper {

    /** 单档地址（`play_addr` / `download_addr` / `bit_rate[].play_addr` 的统一视图）。 */
    internal data class Addr(
        val width: Int?,
        val height: Int?,
        val dataSize: Long?,
        val urls: List<String>,
    )

    /** 一档清晰度（`bit_rate[i]`）。 */
    internal data class Gear(
        val name: String?,
        val width: Int?,
        val height: Int?,
        /** 页面原值（bps）；由 [VideoJsonMedia] 归一为 kbps。 */
        val bitrateBps: Double?,
        val dataSize: Long?,
        val urls: List<String>,
    )

    /** 一次捕获的解析结果（全部字段可选；`reason` 为空表示 ok）。 */
    internal data class Capture(
        val reason: String,
        val title: String?,
        val cover: String?,
        val videoId: String?,
        val durationMs: Long?,
        val gears: List<Gear>,
        val play: Addr?,
        val download: Addr?,
        /** 页面已发生的请求总数（诊断用，不代表档位）。 */
        val captureCount: Int,
        /** 其中命中 `aweme/detail` 的次数（诊断用）。 */
        val detailHits: Int,
    )

    /**
     * 解析紧凑 JSON；`ok=false` 时返回 null（调用方据此判定"PC 未命中"并回退旧链路）。
     */
    fun parseCompact(json: String?): Capture? {
        if (json.isNullOrBlank()) return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.optBoolean("ok", false)) return null
        val gears = o.optJSONArray("gears")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val g = arr.optJSONObject(i) ?: continue
                    val urls = httpUrls(g.optJSONArray("urls"))
                    if (urls.isEmpty()) continue
                    add(
                        Gear(
                            name = g.optString("gear").takeIf { it.isNotBlank() && it != "null" },
                            width = g.optInt("w", 0).takeIf { it > 0 },
                            height = g.optInt("h", 0).takeIf { it > 0 },
                            bitrateBps = g.optDouble("bitrate", 0.0).takeIf { it > 0 && it.isFinite() },
                            dataSize = g.optLong("size", 0L).takeIf { it > 0 },
                            urls = urls,
                        ),
                    )
                }
            }
        }.orEmpty()
        return Capture(
            reason = o.optString("reason"),
            title = o.optString("title").takeIf { it.isNotBlank() && it != "null" },
            cover = o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
            videoId = o.optString("videoId").takeIf { it.isNotBlank() && it != "null" },
            durationMs = o.optLong("durationMs", 0L).takeIf { it > 0 },
            gears = gears,
            play = addrOf(o.optJSONObject("play")),
            download = addrOf(o.optJSONObject("download")),
            captureCount = o.optInt("captureCount", 0),
            detailHits = o.optInt("detailReq", 0),
        )
    }

    /**
     * 捕获结果 → 候选（复用 [VideoJsonMedia.collectRefs]：去重 + 元数据合并 + 档位规整）。
     * `bit_rate` 档位在前、`play_addr` / `download_addr` 在后。
     */
    fun toCandidates(capture: Capture): List<VideoJsonCandidate> {
        val refs = ArrayList<VideoJsonRef>(capture.gears.size * 2 + 8)
        capture.gears.forEach { g ->
            g.urls.forEach { u ->
                refs += VideoJsonRef(
                    field = FIELD_PLAY,
                    url = u,
                    source = VideoJsonCandidate.SOURCE_BIT_RATE,
                    gear = g.name,
                    width = g.width,
                    height = g.height,
                    bitrate = g.bitrateBps,
                    dataSize = g.dataSize,
                )
            }
        }
        capture.play?.urls?.forEach { u ->
            refs += VideoJsonRef(
                field = FIELD_PLAY,
                url = u,
                source = "play_addr",
                width = capture.play.width,
                height = capture.play.height,
                dataSize = capture.play.dataSize,
            )
        }
        capture.download?.urls?.forEach { u ->
            refs += VideoJsonRef(
                field = FIELD_DOWNLOAD,
                url = u,
                source = "download_addr",
                width = capture.download.width,
                height = capture.download.height,
                dataSize = capture.download.dataSize,
            )
        }
        return VideoJsonMedia.collectRefs(refs, emptyList())
    }

    /**
     * quality 规则（与 `WebViewParser.MediaCandidate.toStreamInfo` 一致）：
     * `height` 已知 → `"${height}P"`；未知 → 旧 URL 推断兜底（不猜档位、不做其它推断）。
     */
    fun qualityOf(height: Int?, url: String): String? =
        height?.takeIf { it > 0 }?.let { "${it}P" } ?: qualityHintFromUrl(url)

    // ---- 内部 ----

    private fun addrOf(o: JSONObject?): Addr? {
        if (o == null) return null
        val urls = httpUrls(o.optJSONArray("urls"))
        if (urls.isEmpty()) return null
        return Addr(
            width = o.optInt("w", 0).takeIf { it > 0 },
            height = o.optInt("h", 0).takeIf { it > 0 },
            dataSize = o.optLong("size", 0L).takeIf { it > 0 },
            urls = urls,
        )
    }

    private fun httpUrls(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val u = arr.optString(i)
            if (u.startsWith("http://") || u.startsWith("https://")) out += u
        }
        return out
    }

    private const val FIELD_PLAY = "play_addr"
    private const val FIELD_DOWNLOAD = "download_addr"
}

/**
 * 从 URL 推断清晰度标签（**旧逻辑**：`ratio=1080p` 一类 token；`height` 未知时的兜底）。
 *
 * 与 `WebViewParser.qualityFromUrl()` 同源：后者已改为委托本函数，避免两套实现漂移；
 * 本函数为 file-level（非 WebViewParser 成员）以便 PC 链路与 JVM 单测直接调用。
 */
internal fun qualityHintFromUrl(url: String): String? = runCatching {
    val u = url.lowercase(Locale.ROOT)
    Regex("(2160|1440|1080|720|480|360|240)\\s*p").find(u)?.groupValues?.get(1)?.plus("P")
}.getOrNull()
