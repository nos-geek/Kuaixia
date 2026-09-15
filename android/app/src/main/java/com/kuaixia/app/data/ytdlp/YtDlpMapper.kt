package com.kuaixia.app.data.ytdlp

import com.kuaixia.app.data.model.StreamInfo
import com.kuaixia.app.data.model.VideoInfo

/**
 * 把 yt-dlp 的 JSON 结果转换成统一领域模型 [VideoInfo]。
 *
 * Phase 3.5：识别 B 站等 DASH 分离流（video-only + audio-only），
 * 为每个视频清晰度配对最高质量音频，产出可合并的 [StreamInfo]。
 */
object YtDlpMapper {

    fun map(result: YtDlpResult, originalUrl: String): VideoInfo {
        val webpageUrl = result.webpageUrl ?: originalUrl
        return VideoInfo(
            id = result.id ?: webpageUrl,
            title = result.title,
            author = result.uploader,
            webpageUrl = webpageUrl,
            duration = result.duration?.toLong(),
            thumbnail = result.thumbnail,
            platform = result.extractor ?: "unknown",
            streams = buildStreams(result.formats),
        )
    }

    private fun buildStreams(formats: List<YtDlpFormat>): List<StreamInfo> {
        val valid = formats.filter { it.url != null && it.formatId != null }

        val videoOnly = valid.filter { it.isVideoOnly() }
            .sortedByDescending { videoSortKey(it) }
        val audioOnly = valid.filter { it.isAudioOnly() }
        val combined = valid.filter { it.hasVideo() && it.hasAudio() }

        // 最高质量音频（按 abr，其次 tbr）
        val bestAudio = audioOnly.maxByOrNull { it.abr ?: it.tbr ?: 0.0 }

        val streams = mutableListOf<StreamInfo>()

        // 合并格式（音视频一体）→ 单文件直链
        combined.forEach { f ->
            toStreamInfo(f, videoUrl = null, audioUrl = null, audio = null)?.let { streams += it }
        }

        // video-only → 配对 bestaudio，产出 DASH 可合并流
        videoOnly.forEach { v ->
            toStreamInfo(v, videoUrl = v.url, audioUrl = bestAudio?.url, audio = bestAudio)
                ?.let { streams += it }
        }

        // 兜底：无任何可组合流时，退回把每个格式当单文件直链
        if (streams.isEmpty()) {
            valid.forEach { f ->
                toStreamInfo(f, videoUrl = null, audioUrl = null, audio = null)?.let { streams += it }
            }
        }

        return streams
    }

    /**
     * 单条格式 → StreamInfo。
     *
     * @param videoUrl DASH 视频流直链；null 表示单文件（音视频一体）流。
     * @param audioUrl DASH 音频流直链；null 表示无配对音频。
     * @param audio    配对的音频格式（用于取音频编码/扩展名）。
     */
    private fun toStreamInfo(
        f: YtDlpFormat,
        videoUrl: String?,
        audioUrl: String?,
        audio: YtDlpFormat?,
    ): StreamInfo? {
        val url = videoUrl ?: f.url ?: return null
        val formatId = f.formatId ?: return null

        val width = f.width?.takeIf { it > 0 }
        val height = f.height?.takeIf { it > 0 }
        val hasAudio = audio != null || f.hasAudio()

        val resolution = if (width != null && height != null) "${width}×${height}" else null
        val quality = when {
            height != null -> "${height}P"
            f.formatNote != null -> f.formatNote
            else -> formatId
        }

        return StreamInfo(
            formatId = formatId,
            url = url,
            quality = quality,
            ext = f.ext,
            resolution = resolution,
            width = width,
            height = height,
            fps = f.fps?.toFloat(),
            fileSize = f.filesize ?: f.filesizeApprox?.takeIf { it > 0 },
            vcodec = f.vcodec,
            acodec = audio?.acodec ?: f.acodec,
            hasAudio = hasAudio,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            audioAcodec = audio?.acodec,
            audioExt = audio?.ext,
            videoHeaders = f.httpHeaders,
            audioHeaders = audio?.httpHeaders ?: emptyMap(),
            protocol = f.protocol,
            manifestUrl = f.manifestUrl,
            bitrate = f.tbr,
        )
    }

    private fun YtDlpFormat.isVideoOnly(): Boolean = hasVideo() && !hasAudio()

    private fun YtDlpFormat.isAudioOnly(): Boolean = hasAudio() && !hasVideo()

    private fun YtDlpFormat.hasVideo(): Boolean = vcodec != null && vcodec != "none"

    private fun YtDlpFormat.hasAudio(): Boolean = acodec != null && acodec != "none"

    /** 视频流排序键：分辨率优先，其次帧率，其次码率。 */
    private fun videoSortKey(f: YtDlpFormat): Double =
        (f.height ?: 0) * 100_000.0 + (f.fps ?: 0.0) * 1_000.0 + (f.tbr ?: 0.0)
}
