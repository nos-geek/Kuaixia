package com.kuaixia.app.data.download

/** 下载类型：决定走哪条下载管线（第一版分派依据）。 */
enum class DownloadKind {
    /** 单文件 HTTP 直链（HttpDownloader）。 */
    DIRECT,

    /** DASH 分离流：视频+音频分别下载后 FFmpeg 合并（HttpDownloader + FFmpegMerger）。 */
    DASH,

    /** HLS/M3U8：拉 playlist → 分片 → 合并（M3U8Downloader）。 */
    M3U8,

    /** 图片/图集单张资源（HttpDownloader 直下 → Pictures/快夏）。 */
    IMAGE,
}

/**
 * 下载任务。由解析结果（VideoInfo + 选中的 StreamInfo）创建。
 *
 * 支持三类：
 * 1. 单文件直链（普通 MP4）：[url] 有值，[videoUrl]/[audioUrl] 为 null。
 * 2. DASH 分离流（B 站等）：[videoUrl] + [audioUrl] 有值，需分别下载后 FFmpeg 合并。
 * 3. HLS/M3U8（[kind] == M3U8）：[url] 指向 playlist，按分片下载后合并。
 *
 * @param kind          下载类型（createTask 时按 StreamInfo 判定；老数据为 DIRECT/DASH 由字段兼容推导）
 * @param id            任务唯一 id
 * @param url           主下载地址（单文件直链 / DASH 视频流 / M3U8 playlist）——**当前资源缓存**，
 *                      可能带 deadline/upsig 等临时签名，可能过期；过期时以 [originalUrl] 重新解析
 * @param originalUrl   任务原始来源（视频网页 URL），用于 CDN URL 失效后重新解析
 * @param fileName      最终文件名（含扩展名，如 .mp4）
 * @param saveDir       临时下载目录（视频/音频分片与中间产物），完成后移入 MediaStore
 * @param state         最终状态
 * @param stage         细分阶段（下载视频/下载音频/合并中/完成）
 * @param progress      进度快照
 * @param errorMessage  失败原因（用户可读）
 * @param title         展示用：视频标题
 * @param quality       展示用：清晰度
 * @param videoUrl      DASH 视频流 url（null 表示非 DASH）
 * @param audioUrl      DASH 音频流 url
 * @param audioFileName 音频临时文件名（含扩展名）
 * @param audioCodec    音频编码展示（如 AAC）
 * @param httpHeaders   主下载流（单文件直链 / DASH 视频流 / M3U8 playlist+分片）的请求头
 *                      （**仅非敏感子集**落库）
 * @param audioHeaders  DASH 音频流的请求头
 * @param mediaStoreUri 保存到 MediaStore 后的 uri（删除文件需经 ContentResolver）
 * @param createdAt / updatedAt / completedAt  生命周期时间戳（ms）
 * @param retryCount    失败/重试计数
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val saveDir: String,
    val kind: DownloadKind = DownloadKind.DIRECT,
    /** 解析来源标记（WebViewParser 产出 "webview"；yt-dlp 为 extractor；server 为站点名）。 */
    val source: String? = null,
    val state: DownloadState = DownloadState.QUEUED,
    val stage: DownloadStage = DownloadStage.QUEUED,
    val progress: DownloadProgress = DownloadProgress(),
    val errorMessage: String? = null,
    val title: String? = null,
    val originalUrl: String? = null,
    val quality: String? = null,
    val mimeType: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val audioFileName: String? = null,
    val audioCodec: String? = null,
    /** 主下载流（单文件直链 / DASH 视频流 / M3U8）的请求头（落库时仅保留非敏感子集）。 */
    val httpHeaders: Map<String, String> = emptyMap(),
    /** DASH 音频流的请求头。 */
    val audioHeaders: Map<String, String> = emptyMap(),
    val mediaStoreUri: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val completedAt: Long? = null,
    val retryCount: Int = 0,
) {
    /** 完整临时文件路径。 */
    val filePath: String get() = "$saveDir/$fileName"

    /** 音频临时文件路径（DASH 任务）。 */
    val audioFilePath: String? get() = audioFileName?.let { "$saveDir/$it" }

    /** 是否为 DASH 分离流（需下载视频+音频后合并）。 */
    val isDash: Boolean get() = kind == DownloadKind.DASH || (videoUrl != null && audioUrl != null)

    /** M3U8 分片下载目录（cacheDir/downloads/<taskId>/segments/）。 */
    val segmentDir: String get() = "$saveDir/$id/segments"

    /** M3U8 合并前最终分片文件路径。 */
    val m3u8OutputPath: String get() = "$saveDir/$id/merged.mp4"

    /** 带状态的复制（时间戳自动维护）。 */
    fun withState(state: DownloadState, errorMessage: String? = null, stage: DownloadStage? = null): DownloadTask =
        copy(
            state = state,
            errorMessage = errorMessage,
            stage = stage ?: this.stage,
            updatedAt = System.currentTimeMillis(),
            completedAt = if (state == DownloadState.COMPLETED) System.currentTimeMillis() else completedAt,
        )

    fun withStage(stage: DownloadStage): DownloadTask =
        copy(stage = stage, updatedAt = System.currentTimeMillis())

    fun withProgress(progress: DownloadProgress): DownloadTask =
        copy(progress = progress, updatedAt = System.currentTimeMillis())
}
