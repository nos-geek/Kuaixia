package com.kuaixia.app.core.log

/** 日志级别。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** 统一日志 Tag。 */
object LogTags {
    const val KUAIXIA = "Kuaixia"
    const val PARSER = "Parser"
    const val YTDLP = "YtDlp"
    const val SERVER = "Server"
    const val DOWNLOAD = "Download"
    const val HTTP = "Http"
    const val M3U8 = "M3U8"
    const val FFMPEG = "FFmpeg"
    const val MEDIASTORE = "MediaStore"
    const val CRASH = "Crash"
    const val WEBVIEW = "WebView"
    const val DOUYIN = "Douyin"
}

/** 下载/处理阶段（用于崩溃上下文定位）。 */
object Stages {
    const val IDLE = "IDLE"
    const val CREATE_TASK = "DOWNLOAD_CREATE_TASK"
    const val DOWNLOADING_VIDEO = "DOWNLOADING_VIDEO"
    const val DOWNLOADING_AUDIO = "DOWNLOADING_AUDIO"
    const val MERGING = "MERGING"
    const val MEDIASTORE = "MEDIASTORE"
}

/** 一条应用日志。 */
data class AppLog(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    /** 完整 stacktrace（已脱敏），仅 ERROR 级常见。 */
    val throwable: String? = null,
)
