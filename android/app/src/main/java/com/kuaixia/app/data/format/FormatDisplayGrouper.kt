package com.kuaixia.app.data.format

import com.kuaixia.app.data.model.StreamInfo

/**
 * 单个 UI 清晰度分组（Phase 6.5）。
 *
 * - [label]          UI 展示名（沿用项目命名：heightP，如 "1280P"；无高度视频归「其他」）
 * - [height]         分组用有效视频高度（null = 无高度组）
 * - [representative] 该清晰度默认下载的代表格式（**直接绑定原 StreamInfo**，点击即下载它）
 * - [alternatives]   组内其余有效格式（底层保留完整，未来可做高级展开）
 */
data class DisplayFormatGroup(
    val label: String,
    val height: Int?,
    val representative: StreamInfo,
    val alternatives: List<StreamInfo>,
)

/**
 * 视频格式整理/择优（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 职责：把解析产生的完整 [StreamInfo] 列表整理为「按显示清晰度分组、每组一个最佳代表」的 UI 数据。
 * **不修改也不删除底层 formats**——所有有效视频流都会保留在 [DisplayFormatGroup.alternatives] 中。
 *
 * 规则：
 * 1. 只保留真正视频流（排除 audio-only / 无视频 URL / vcodec=none）。
 * 2. 按有效视频高度分组；height==null 的有效视频统一归「其他」（不丢失）。
 * 3. 组内去掉「同高度 + 同编码族 + 同 fps 段 + 同一视频资源 URL」的真正重复（保留更优者）。
 * 4. 组内择优（均降序）：编码 av01/AV1 ≈ vp9/VP9 > hevc/H.265 > avc/H.264（未知靠后，
 *    不按平台写死）→ 码率高 → fps 高 → 容器 mp4 > webm > 其它（照顾播放兼容）。
 * 5. 输出按高度从高到低排序，无高度组最后。
 *
 * 注：模型无 HDR/dynamic range 字段，HDR 不作为独立评分维度；同高度 HDR/DV 版本会作为
 * alternatives 保留（编码族已归类 hevc 时与代表同组）。
 */
object FormatDisplayGrouper {

    /**
     * 未知/无高度视频组的展示名（空串占位：非本地化文本，由 UI 层替换为当前语言资源）。
     */
    const val OTHER_LABEL = ""

    fun group(streams: List<StreamInfo>): List<DisplayFormatGroup> {
        val videos = streams.filter { it.isRealVideoStream() }
        val deDuped = videos.dedupeIdentical()
        val byHeight = deDuped.groupBy { it.height?.takeIf { h -> h > 0 } }

        val groups = byHeight.mapNotNull { (height, members) ->
            val best = members.maxWithOrNull(qualityComparator) ?: return@mapNotNull null
            val label = height?.let { "${it}P" }
                ?: best.quality?.takeIf { q -> q.isNotBlank() && '×' !in q }
                ?: OTHER_LABEL
            DisplayFormatGroup(
                label = label,
                height = height,
                representative = best,
                alternatives = members.filterNot { it === best }.sortedWith(qualityComparator.reversed()),
            )
        }

        return groups.sortedWith(compareByDescending<DisplayFormatGroup> { it.height ?: -1 })
    }

    // ---- 组内择优（a 优于 b 返回正数） ----

    private val qualityComparator = Comparator<StreamInfo> { a, b ->
        compareByRules(a, b)
    }

    private fun compareByRules(a: StreamInfo, b: StreamInfo): Int {
        val byCodec = codecTier(a.vcodec).compareTo(codecTier(b.vcodec))
        if (byCodec != 0) return byCodec
        val byBitrate = (a.bitrate ?: 0.0).compareTo(b.bitrate ?: 0.0)
        if (byBitrate != 0) return byBitrate
        val byFps = (a.fps ?: 0f).compareTo(b.fps ?: 0f)
        if (byFps != 0) return byFps
        val byContainer = containerTier(a).compareTo(containerTier(b))
        if (byContainer != 0) return byContainer
        // 全等：确定性回退，formatId 小者优先
        return a.formatId.compareTo(b.formatId)
    }

    /** 可视为视频容器的扩展名（编码未知时用于判定）。 */
    private val VIDEO_EXTS = setOf("mp4", "m4v", "mov", "webm", "mkv", "flv", "ts", "m2ts", "m3u8", "mpd")

    /** 音频容器/扩展名（编码未知时优先排除，防把 audio-only 当视频）。 */
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")

    /**
     * 是否真实视频流：
     * - `vcodec` 明确且有值（非 `none`）→ 视频（yt-dlp/服务器线路，保持原行为）；
     * - `vcodec` 未知（WebView / 页面 JSON 直链拿不到编码信息，如 `aweme/v1/playwm/` 这类无 `.mp4`
     *   后缀的 URL）→ 按 mime 优先、ext 兜底、DASH 兜底判定，并**先排除 audio-only**
     *   （`mime` 以 audio/ 开头或音频扩展名），避免误收音频；
     * - 编码与容器均未知且非 DASH → 保守排除（不猜测）。
     */
    private fun isRealVideo(stream: StreamInfo): Boolean {
        val url = stream.videoUrl?.takeIf { it.isNotBlank() } ?: stream.url
        if (url.isBlank()) return false
        val codec = stream.vcodec?.lowercase()
        if (codec == "none") return false
        if (!codec.isNullOrEmpty()) return true
        val mime = stream.mimeType?.lowercase().orEmpty()
        if (mime.startsWith("audio/")) return false
        if (mime.startsWith("video/")) return true
        val ext = stream.ext?.lowercase()?.removePrefix(".").orEmpty()
        if (ext in AUDIO_EXTS) return false
        if (ext in VIDEO_EXTS) return true
        return stream.isDash
    }

    private fun StreamInfo.isRealVideoStream(): Boolean = isRealVideo(this)

    // ---- 编码 / fps / 容器判定 ----

    private fun codecTier(vcodec: String?): Int {
        val v = vcodec.orEmpty().lowercase()
        return when {
            v.startsWith("av01") || v == "av1" || v.startsWith("av1") -> 5
            v.startsWith("vp9") || v.startsWith("vp09") -> 4
            v.contains("hevc") || v.contains("hvc1") || v.contains("hev1") || v.contains("h265") ||
                v.contains("dvh1") || v.contains("dvhe") -> 3
            v.startsWith("avc1") || v.contains("avc") || v.startsWith("h264") || v.contains("264") -> 2
            else -> 1
        }
    }

    private fun codecFamily(vcodec: String?): String = when (codecTier(vcodec)) {
        5 -> "av01"
        4 -> "vp9"
        3 -> "hevc"
        2 -> "h264"
        else -> vcodec?.lowercase() ?: "?"
    }

    private fun containerTier(s: StreamInfo): Int = when (s.ext?.lowercase()) {
        "mp4", "m4v", "mov" -> 2
        "webm" -> 1
        else -> 0
    }

    /** fps 分段（50+ 视为高帧率段）。 */
    private fun fpsBucket(fps: Float?): Int = when {
        fps == null -> 0
        fps >= 50f -> 3
        fps >= 25f -> 2
        else -> 1
    }

    private fun resourceUrl(s: StreamInfo): String = s.videoUrl?.takeIf { it.isNotBlank() } ?: s.url

    /** 同组真正重复：同高度 + 同编码族 + 同 fps 段 + 同一视频资源 URL。 */
    private fun List<StreamInfo>.dedupeIdentical(): List<StreamInfo> {
        val seen = LinkedHashMap<String, StreamInfo>()
        for (s in this) {
            val key = buildString {
                append(s.height?.takeIf { it > 0 } ?: 0)
                append('|').append(codecFamily(s.vcodec))
                append('|').append(fpsBucket(s.fps))
                append('|').append(resourceUrl(s))
            }
            val prev = seen[key]
            if (prev == null || compareByRules(s, prev) > 0) seen[key] = s
        }
        return seen.values.toList()
    }

    // ---- 展示辅助（UI / 日志用） ----

    /** 编码短标签：AV1 / VP9 / H.265 / H.264 / 原始（未知时返回空串，由 UI 层替换为当前语言资源）。 */
    fun codecLabelShort(vcodec: String?): String = when (codecTier(vcodec)) {
        5 -> "AV1"
        4 -> "VP9"
        3 -> "H.265"
        2 -> "H.264"
        else -> vcodec?.takeIf { it.isNotBlank() }?.substringBefore('.') ?: ""
    }

    /** fps 展示：60fps / 30fps …（无则 null）。 */
    fun fpsLabel(fps: Float?): String? {
        val f = fps ?: return null
        val rounded = if (f >= 50f) 60 else kotlin.math.round(f).toInt()
        return "${rounded}fps"
    }
}
