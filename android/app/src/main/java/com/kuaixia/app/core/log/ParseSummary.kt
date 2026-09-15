package com.kuaixia.app.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 解析摘要（日志 V2 核心）：每次解析结束输出一条**结构统一**的 SUMMARY，
 * 便于多人测试时跨设备批量比对（解析耗时/来源/是否 fallback）。
 *
 * 字段名固定：platform / page / strategy / success / mediaType / source /
 * formats / formatGroups / video / audio / image / fallback(ytdlp) / elapsedMs / error。
 * 取不到的字段用 `unknown`，绝不伪造。
 */
object ParseSummary {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    data class Fields(
        val timeMs: Long,
        val platform: String,
        val page: String,
        val url: String,
        val strategy: String,
        val success: Boolean,
        val mediaType: String,
        val source: String,
        val video: Int,
        val audio: Int,
        val image: Int,
        val formats: Int,
        val formatGroups: Int,
        val fallbackYtdlp: Boolean,
        val elapsedMs: Long,
        val error: String? = null,
    )

    fun render(f: Fields): String = buildString {
        appendLine("========== PARSE SUMMARY ==========")
        appendLine("time=${fmt.format(Date(f.timeMs))}")
        appendLine("platform=${f.platform}")
        appendLine("page=${f.page}")
        appendLine("url=${f.url}")
        appendLine()
        appendLine("strategy=${f.strategy}")
        appendLine()
        appendLine("candidates:")
        appendLine("video=${f.video}")
        appendLine("audio=${f.audio}")
        appendLine("image=${f.image}")
        appendLine()
        appendLine("result:")
        appendLine("success=${f.success}")
        appendLine("mediaType=${f.mediaType}")
        appendLine("source=${f.source}")
        appendLine("formats=${f.formats}")
        appendLine("formatGroups=${f.formatGroups}")
        appendLine()
        appendLine("fallback:")
        appendLine("ytdlp=${f.fallbackYtdlp}")
        if (!f.success) appendLine("error=${f.error ?: "unknown"}")
        appendLine("elapsedMs=${f.elapsedMs}")
        append("===================================")
    }

    /** 页面类型（仅日志层推断：URL path 关键字；取不到用 unknown）。 */
    fun pageOf(url: String): String {
        val u = url.lowercase(Locale.ROOT)
        return when {
            "/video/" in u -> "VIDEO"
            "/note/" in u -> "NOTE"
            "/slides/" in u -> "SLIDES"
            else -> "unknown"
        }
    }
}
