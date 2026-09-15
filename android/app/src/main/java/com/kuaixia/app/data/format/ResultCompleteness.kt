package com.kuaixia.app.data.format

import com.kuaixia.app.data.model.StreamInfo

/**
 * 结果完整性判据（纯 Kotlin，可 JVM 单测）。
 *
 * 用途：解析「成功」不等于「可用」——例如 WebView PAGE_JSON 只拿到一个编码未知的直链时，
 * 曾被 [FormatDisplayGrouper] 过滤成 `formatGroups=0` 的残缺 VIDEO 结果（UI 无格式可选）。
 * 本判据用于**缓存准入**：残缺结果不入成功缓存（宁可下次重解析/回退 yt-dlp），
 * 避免坏结果被 45s TTL 内反复复用。
 */
object ResultCompleteness {

    /** 视频结果是否至少含 1 个可用于 UI/下载的视频格式（与 UI 分组口径一致）。 */
    fun hasDownloadableVideo(streams: List<StreamInfo>): Boolean =
        FormatDisplayGrouper.group(streams).isNotEmpty()
}
