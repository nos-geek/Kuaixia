package com.kuaixia.app.core.log

/**
 * 日志 V2 策略（纯逻辑，可 JVM 单测）：标准 / 详细两级。
 *
 * - **标准日志**（多人测试用户默认）：`verboseEnabled=false` —— 只保留 INFO+ 且非「详细类 tag」，
 *   去掉 DOM probe / PAGE_JSON polling / WebView 内部状态等高频噪声；解析与下载以统一
 *   PARSE SUMMARY / DOWNLOAD SUMMARY 收口。
 * - **详细日志**（开发/测试人员）：`verboseEnabled=true` —— 全部级别、全部 tag。
 *
 * 两种级别都写入同一文件（用户无需切换文件即可导出），差别只在量。
 */
object LogPolicy {

    /**
     * 「详细类」tag：这些模块的日志高频、只在详细模式输出（标准模式由 SUMMARY 汇总代替）。
     * 目前仅 WebView（DOM probe / PAGE_JSON polling / WebView 状态）。
     */
    val VERBOSE_TAGS: Set<String> = setOf("WebView")

    /** 单日志文件上限（V2 保持 5MB：诊断导出体积可控，标准日志通常远小于此）。 */
    const val MAX_FILE_BYTES: Long = 5L * 1024 * 1024

    /** 保留历史日志份数（kuaixia.log + .1 + .2；超过的旧档在轮转时删除）。 */
    const val MAX_FILES: Int = 3

    /** 写盘队列容量（有界：满时丢弃最旧日志，绝不阻塞业务线程，绝不 OOM）。 */
    const val QUEUE_CAPACITY: Int = 2048

    /** 内存环形缓冲条数（日志页展示用）。 */
    const val MAX_IN_MEMORY: Int = 1500

    const val LOG_FILE = "kuaixia.log"

    fun isVerboseTag(tag: String): Boolean = tag in VERBOSE_TAGS

    /**
     * 是否输出该条日志。
     * @param verboseEnabled 「详细日志」开关（=设置里的 debugLogging）
     */
    fun shouldLog(level: LogLevel, tag: String, verboseEnabled: Boolean): Boolean {
        if (verboseEnabled) return true
        if (isVerboseTag(tag)) return false
        return level.ordinal >= LogLevel.INFO.ordinal
    }

    /** 当前生效的最低级别（供日志页展示）。 */
    fun effectiveMinLevel(verboseEnabled: Boolean): LogLevel =
        if (verboseEnabled) LogLevel.DEBUG else LogLevel.INFO
}
