package com.kuaixia.app.data.ytdlp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * yt-dlp `--dump-single-json` 输出的 JSON 模型（仅取项目需要的字段）。
 *
 * 这是 yt-dlp 侧的中间模型，最终会由 [YtDlpMapper] 转换成统一领域模型，
 * 不会直接暴露给 UI。
 */
@Serializable
data class YtDlpResult(
    val id: String? = null,
    val title: String? = null,
    val uploader: String? = null,
    @SerialName("webpage_url") val webpageUrl: String? = null,
    val duration: Double? = null,
    val thumbnail: String? = null,
    val extractor: String? = null,
    val formats: List<YtDlpFormat> = emptyList(),
)

@Serializable
data class YtDlpFormat(
    @SerialName("format_id") val formatId: String? = null,
    @SerialName("format_note") val formatNote: String? = null,
    val format: String? = null,
    val ext: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val filesize: Long? = null,
    @SerialName("filesize_approx") val filesizeApprox: Long? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val url: String? = null,
    val tbr: Double? = null,
    val abr: Double? = null,
    @SerialName("http_headers") val httpHeaders: Map<String, String> = emptyMap(),
    // Phase 4：HLS 识别（yt-dlp protocol：http/https/m3u8/m3u8_native/…）
    val protocol: String? = null,
    @SerialName("manifest_url") val manifestUrl: String? = null,
)
