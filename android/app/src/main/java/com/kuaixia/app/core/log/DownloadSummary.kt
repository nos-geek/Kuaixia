package com.kuaixia.app.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载摘要（日志 V2）：每次下载**失败**（或取消/异常）输出一条结构统一的 SUMMARY，
 * 便于多人测试时按设备比对（HTTP 状态、耗时、错误码）。
 *
 * 字段名固定：platform / type / quality / http / elapsedMs / error。取不到用 `unknown`。
 */
object DownloadSummary {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    data class Fields(
        val timeMs: Long,
        val platform: String,
        val type: String,
        val quality: String?,
        val http: Int?,
        val elapsedMs: Long,
        val error: String,
    )

    fun render(f: Fields): String = buildString {
        appendLine("========== DOWNLOAD SUMMARY ==========")
        appendLine("time=${fmt.format(Date(f.timeMs))}")
        appendLine("platform=${f.platform}")
        appendLine("type=${f.type}")
        appendLine("quality=${f.quality?.takeIf { it.isNotBlank() } ?: "unknown"}")
        appendLine()
        appendLine("http=${f.http ?: "unknown"}")
        appendLine("elapsedMs=${f.elapsedMs}")
        appendLine()
        appendLine("error=${f.error.take(200)}")
        append("======================================")
    }

    /** 平台（仅日志层推断）。 */
    fun platformOf(url: String?): String {
        val u = url.orEmpty().lowercase(Locale.ROOT)
        return when {
            "douyin" in u -> "douyin"
            "kuaishou" in u -> "kuaishou"
            "bilibili" in u || "b23.tv" in u -> "bilibili"
            "xiaohongshu" in u || "xhslink" in u -> "xiaohongshu"
            "youtube" in u || "youtu.be" in u -> "youtube"
            u.isBlank() -> "unknown"
            else -> "other"
        }
    }
}
