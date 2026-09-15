package com.kuaixia.app.core.log

/**
 * 应用日志门面：统一委托到 [AppLogRepository]。
 *
 * 好处：现有代码（ParserManager / YtDlpEngine / DownloadRepository 等）
 * 无需改动即自动接入「内存 + 文件 + logcat」三合一的日志系统，且经过脱敏。
 *
 * 不得输出 Cookie / Authorization / Token 等敏感信息（由 LogSanitizer 兜底脱敏）。
 */
object AppLogger {

    fun d(message: String, tag: String = LogTags.KUAIXIA) = AppLogRepository.d(tag, message)
    fun i(message: String, tag: String = LogTags.KUAIXIA) = AppLogRepository.i(tag, message)
    fun w(message: String, tag: String = LogTags.KUAIXIA) = AppLogRepository.w(tag, message)
    fun e(message: String, throwable: Throwable? = null, tag: String = LogTags.KUAIXIA) =
        AppLogRepository.e(tag, message, throwable)
}
