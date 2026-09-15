package com.kuaixia.app.data.model

/**
 * 单条可下载流（清晰度）的统一模型。
 *
 * 支持两类：
 * 1. 单文件流（普通 MP4 直链）：[url] 有值，[videoUrl]/[audioUrl] 为 null。
 * 2. DASH 分离流（B 站等 video-only + audio-only）：[videoUrl] + [audioUrl] 有值，
 *    下载后需 FFmpeg 合并（Phase 3.5）。
 *
 * 字段为 yt-dlp 与服务器两类来源的交集。
 */
data class StreamInfo(
    val formatId: String,
    val url: String,
    val quality: String? = null,
    val ext: String? = null,
    /** MIME 类型（尽力推断；WebView 线路无法获得真实响应 MIME 时可能为 null/近似）。 */
    val mimeType: String? = null,
    val resolution: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Float? = null,
    val fileSize: Long? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val hasAudio: Boolean = false,

    // Phase 3.5：DASH 分离流
    /** 视频流直链（DASH video-only）。null 表示非分离流。 */
    val videoUrl: String? = null,
    /** 音频流直链（DASH audio-only）。null 表示无配对音频。 */
    val audioUrl: String? = null,
    /** 音频编码（如 aac/mp4a），用于展示。 */
    val audioAcodec: String? = null,
    /** 音频文件扩展名（如 m4a），用于临时文件命名。 */
    val audioExt: String? = null,

    // Phase 3.5：各流下载时所需的 HTTP 请求头（yt-dlp format 的 http_headers，如 Referer/User-Agent）
    /** 视频流下载请求头（单文件直链时也用它）。 */
    val videoHeaders: Map<String, String> = emptyMap(),
    /** 音频流下载请求头（DASH 分离流的音频，可能不同于视频流）。 */
    val audioHeaders: Map<String, String> = emptyMap(),

    // Phase 4：HLS 识别（本地 yt-dlp 线路才有；服务器线路用 .m3u8 URL 后缀兜底）
    /** 流协议（yt-dlp protocol：http/https/m3u8/m3u8_native…）。null=未知（按直链处理）。 */
    val protocol: String? = null,
    /** HLS 原始 manifest 地址（master 或 media playlist，如可用）。 */
    val manifestUrl: String? = null,
    /** 码率（yt-dlp tbr，近似总码率；仅用于同清晰度组内择优，UI 可选择性展示）。 */
    val bitrate: Double? = null,
) {
    /** 是否为 DASH 分离流（需下载视频+音频后合并）。 */
    val isDash: Boolean get() = videoUrl != null && audioUrl != null

    /** 是否为 HLS/M3U8 流（protocol 声明 m3u8 或 URL 指向 .m3u8 兜底）。 */
    val isM3u8: Boolean
        get() {
            val proto = protocol?.lowercase().orEmpty()
            val target = url.lowercase()
            val manifest = manifestUrl?.lowercase().orEmpty()
            return proto.contains("m3u8") ||
                target.endsWith(".m3u8") ||
                (target.contains(".m3u8?")) ||
                manifest.endsWith(".m3u8") ||
                (manifest.contains(".m3u8?"))
        }
}
