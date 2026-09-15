package com.kuaixia.app.data.parser

/**
 * P8.2：PAGE_JSON 视频候选引用（video_picker.js 的 refs 单条）。
 *
 * P8.1 只有 [field]/[url]；P8.2 追加 video.bit_rate[] 的档位元数据（全部可选，
 * 缺失时行为与 P8.1 逐字一致）。纪律不变：只消费页面真实提供的 http(s) URL，
 * **不改写 URL、不猜测无水印**。
 */
internal data class VideoJsonRef(
    val field: String,
    val url: String,
    /** 真实来源：play_addr / download_addr / bit_rate（仅标注，不参与判定）。 */
    val source: String? = null,
    /** bit_rate[].gear_name（如 1080p / normal_720_0）。 */
    val gear: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** bit_rate[].bit_rate（页面原值，单位 bps）。 */
    val bitrate: Double? = null,
    /** play_addr.data_size（页面原值，字节）。 */
    val dataSize: Long? = null,
)

/**
 * P8.1/P8.2：VIDEO 页 PAGE_JSON（videoInfoRes.item_list[].video）视频候选的纯逻辑选择（可 JVM 单测）。
 *
 * 纪律：
 * - 只消费页面真实提供的 HTTP/HTTPS URL（来自 video.play_addr.url_list / download_addr.url_list /
 *   bit_rate[].play_addr.url_list）；
 * - 只过滤/去重/标注，**不改写任何 URL、不猜测无水印**；
 * - playwm 等带水印语义路径只标注 watermarkHint，绝不自动视为无水印；
 * - 无候选 → 返回空（调用方保持 NO_VIDEO → yt-dlp fallback）。
 */
internal data class VideoJsonCandidate(
    val field: String,
    val url: String,
    val source: String? = null,
    val gear: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 已归一为 kbps（页面 bps → /1000）；仅用于同清晰度组内择优与日志。 */
    val bitrate: Double? = null,
    val dataSize: Long? = null,
) {
    /** 保守水印提示：路径含 playwm 等仅作「含水印语义」提示；不含也不等于「无水印」（未证实）。 */
    val watermarkHint: Boolean get() = url.contains("playwm")

    /** 是否来自 video.bit_rate[] 档位枚举（仅用于日志/审计，不参与任何排序或判定）。 */
    val fromBitRate: Boolean get() = source == SOURCE_BIT_RATE

    companion object {
        const val SOURCE_BIT_RATE = "bit_rate"
    }
}

internal object VideoJsonMedia {

    /** 已知视频高度白名单：仅用于 gear_name 兜底解析，白名单外一律不取（不猜测）。 */
    private val KNOWN_HEIGHTS = setOf(240, 360, 480, 540, 720, 1080, 1440, 2160)

    /** bps/kbps 判定阈值：页面 bit_rate 为 bps（如 2048000），yt-dlp tbr 为 kbps。 */
    private const val BPS_THRESHOLD = 100_000.0

    /**
     * 选择可作为 VIDEO 前置候选的 URL（P8.1 兼容入口：无档位元数据）。
     * @param refs        按字段序的 (field, url)（页面真实顺序：play_addr 先于 download_addr）
     * @param capturedUrls 已捕获候选 URL（网络/PROBE_JS），冲突者跳过（不重复入库）
     * @return 空列表表示无可靠新候选（调用方保持 yt-dlp fallback 语义）
     */
    fun collect(
        refs: List<Pair<String, String>>,
        capturedUrls: Collection<String>,
    ): List<VideoJsonCandidate> =
        collectRefs(refs.map { (field, url) -> VideoJsonRef(field = field, url = url) }, capturedUrls)

    /**
     * P8.2：带档位元数据的候选选择。
     *
     * 与 P8.1 的差异仅一点：页面 refs **内部**同一 URL 的去重由「跳过」改为「合并（补齐缺失元数据）」，
     * 使 play_addr 与 bit_rate[i].play_addr 指向同一资源时仍保留 height/bitrate 档位信息；
     * 已捕获 URL 的去重、非 http(s) 拒绝、watermarkHint 语义、输出顺序均与 P8.1 完全一致。
     */
    fun collectRefs(
        refs: List<VideoJsonRef>,
        capturedUrls: Collection<String>,
    ): List<VideoJsonCandidate> {
        val captured = HashSet<String>()
        capturedUrls.forEach { captured += it.substringBefore('#') }
        val merged = LinkedHashMap<String, VideoJsonCandidate>(refs.size)
        for (r in refs) {
            val raw = r.url
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) continue
            val key = raw.substringBefore('#')
            if (key in captured) continue
            val candidate = VideoJsonCandidate(
                field = r.field,
                url = key,
                source = r.source,
                gear = r.gear?.takeIf { it.isNotBlank() },
                width = r.width?.takeIf { it > 0 },
                height = resolveHeight(r.height, r.gear),
                bitrate = normalizeBitrate(r.bitrate),
                dataSize = r.dataSize?.takeIf { it > 0 },
            )
            val prev = merged[key]
            merged[key] = if (prev == null) candidate else merge(prev, candidate)
        }
        return merged.values.toList()
    }

    // ---- 元数据规整 ----

    /**
     * 真实字段优先：显式 height 直接使用；缺失时用 gear_name 中的**白名单高度**兜底
     * （如 "1080p"、"normal_720_0" → 1080 / 720）。白名单之外的数字一律不取（不猜测）；
     * 仍无法确定 → null（沿用 P8.1：quality 由调用方 URL 正则兜底）。
     */
    private fun resolveHeight(height: Int?, gear: String?): Int? {
        height?.takeIf { it > 0 }?.let { return it }
        val text = gear?.lowercase() ?: return null
        return Regex("(\\d{3,4})")
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .firstOrNull { it in KNOWN_HEIGHTS }
    }

    /** bit_rate 单位归一：页面 bps（≥100000）→ kbps；已是 kbps 量级则原样保留。 */
    private fun normalizeBitrate(value: Double?): Double? {
        val v = value?.takeIf { it > 0 && it.isFinite() } ?: return null
        return if (v >= BPS_THRESHOLD) v / 1000.0 else v
    }

    /**
     * 同 URL 合并：保留首个条目（顺序与字段语义不变），用后者**补齐**缺失元数据。
     * source 只要任一侧为 bit_rate 即标注为 bit_rate（该候选确实携带档位元数据，供日志统计）。
     */
    private fun merge(a: VideoJsonCandidate, b: VideoJsonCandidate): VideoJsonCandidate = a.copy(
        source = if (a.fromBitRate || b.fromBitRate) {
            VideoJsonCandidate.SOURCE_BIT_RATE
        } else {
            a.source ?: b.source
        },
        gear = a.gear ?: b.gear,
        width = a.width ?: b.width,
        height = a.height ?: b.height,
        bitrate = a.bitrate ?: b.bitrate,
        dataSize = a.dataSize ?: b.dataSize,
    )
}
