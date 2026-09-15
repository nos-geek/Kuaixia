package com.kuaixia.app.core.log

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器。
 *
 * 职责：崩溃时把完整信息（时间/线程/异常类型/消息/stacktrace/当前阶段与任务）
 * 同步写入内部存储，并记入日志系统；随后交还给系统默认处理器。
 */
class CrashHandler(
    context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    init {
        CrashLogStore.init(context.applicationContext)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            val report = buildReport(thread, throwable)
            CrashLogStore.write(report)
            AppLogRepository.logSync(
                LogLevel.ERROR,
                LogTags.CRASH,
                "未捕获异常：${throwable::class.java.simpleName}: ${throwable.message}",
                throwable,
            )
        }
        // 交还默认处理器（弹出停止运行对话框 / 终止进程）
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildReport(thread: Thread, t: Throwable): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
        appendLine("=== 快夏崩溃报告 ===")
        appendLine("时间：${fmt.format(Date())}")
        appendLine("线程：${thread.name} (id=${thread.id})")
        appendLine("异常类型：${t::class.java.name}")
        appendLine("消息：${t.message}")
        appendLine()
        append(DebugContext.snapshot())
        appendLine()
        appendLine("完整 stacktrace：")
        var cause: Throwable? = t
        var depth = 0
        while (cause != null) {
            if (depth > 0) appendLine("Caused by (${depth}): ${cause::class.java.name}: ${cause.message}")
            appendLine(LogSanitizer.sanitize(cause.stackTraceToString()))
            cause = cause.cause
            depth++
        }
    }
}
