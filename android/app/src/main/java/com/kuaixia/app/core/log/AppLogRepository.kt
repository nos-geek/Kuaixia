package com.kuaixia.app.core.log

import android.content.Context
import android.os.Environment
import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一日志仓库（日志 V2）：内存环形缓冲 + 文件持久化（App 内部存储）+ logcat。
 *
 * V2 变化：
 * - **标准 / 详细两级**（[LogPolicy]）：标准日志过滤「详细类 tag」（WebView）与 DEBUG，
 *   解析/下载以 PARSE SUMMARY / DOWNLOAD SUMMARY 收口；详细日志全量输出。两者写同一文件。
 * - **有界写盘队列**：队列满时丢弃最旧日志（[ThreadPoolExecutor.DiscardOldestPolicy]），
 *   绝不阻塞业务线程、绝不无限增长。
 * - **统一脱敏**由 [LogSanitizer] 在 [makeEntry] 完成（消息与 stacktrace）。
 * - **导出**：按时间范围（最近 1/6/24 小时/全部）生成文本或诊断 ZIP（kuaixia.log + device.txt + README.txt）；
 *   UI 经系统 SAF 选择保存位置（见 [LogExport]）。
 * - 文件**大小轮转**：[LogPolicy.MAX_FILE_BYTES] × [LogPolicy.MAX_FILES]；
 * - 文件写入异步化（单线程 writer），日志系统自身任何异常都被吞掉（业务不受影响）。
 */
object AppLogRepository {

    private const val LOG_DIR = "logs"

    private val seq = AtomicLong(0)
    private val _logs = MutableStateFlow<List<AppLog>>(emptyList())
    val logs: StateFlow<List<AppLog>> = _logs.asStateFlow()

    /** 「详细日志」开关（设置项 debugLogging）：false=标准日志，true=详细日志。 */
    @Volatile
    var verboseEnabled: Boolean = false
        private set

    /** 当前生效最低级别（供日志页展示）。 */
    val minLevel: LogLevel get() = LogPolicy.effectiveMinLevel(verboseEnabled)

    /** 开启「详细日志」（允许 DEBUG 与详细类 tag）。 */
    fun setDebugLogging(enabled: Boolean) {
        verboseEnabled = enabled
    }

    @Volatile
    private var logFile: File? = null
    private var contextRef: Context? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /** 单线程写盘（有界队列，满则丢弃最旧），保证顺序且不阻塞调用方。 */
    private var writer: ThreadPoolExecutor? = null

    fun init(context: Context, debugBuild: Boolean) {
        if (writer == null) {
            val ctx = context.applicationContext
            contextRef = ctx
            logFile = File(File(ctx.filesDir, LOG_DIR), LogPolicy.LOG_FILE)
            writer = ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(LogPolicy.QUEUE_CAPACITY),
                { r -> Thread(r, "kuaixia-log-writer").apply { isDaemon = true } },
                ThreadPoolExecutor.DiscardOldestPolicy(),
            )
            verboseEnabled = debugBuild
            AndroidLog.i("Log", "AppLogRepository init verbose=$verboseEnabled file=${logFile?.absolutePath}")
        }
    }

    // ---- 便捷方法 ----------------------------------------------------------

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, throwable)

    // ---- 核心 --------------------------------------------------------------

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        // 日志系统自身绝不抛异常影响业务
        runCatching {
            if (!LogPolicy.shouldLog(level, tag, verboseEnabled)) return
            val entry = makeEntry(level, tag, message, throwable)
            _logs.update { list -> (list + entry).takeLast(LogPolicy.MAX_IN_MEMORY) }

            when (level) {
                LogLevel.DEBUG -> AndroidLog.d(tag, entry.message)
                LogLevel.INFO -> AndroidLog.i(tag, entry.message)
                LogLevel.WARN -> AndroidLog.w(tag, entry.message)
                LogLevel.ERROR -> AndroidLog.e(tag, entry.message, throwable)
            }

            appendAsync(entry)
        }
    }

    /**
     * 崩溃场景：冲刷已投递日志后同步写入（崩溃必为 ERROR，永不被过滤）。
     */
    fun logSync(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        runCatching {
            val entry = makeEntry(LogLevel.ERROR, tag, message, throwable)
            _logs.update { list -> (list + entry).takeLast(LogPolicy.MAX_IN_MEMORY) }
            AndroidLog.e(tag, entry.message, throwable)
            flush()
            appendSync(entry)
        }
    }

    fun clear() {
        runCatching {
            _logs.value = emptyList()
            logFile?.delete()
        }
    }

    /**
     * 导出为纯文本（内存缓冲）。
     * @param sinceMs 起始时间戳（null=全部）；UI 由 [LogExport.Range] 决定。
     */
    fun exportText(sinceMs: Long? = null): String {
        val entries = LogExport.filterSince(_logs.value, sinceMs)
        val sb = StringBuilder()
        sb.appendLine("快夏诊断日志（V2）")
        sb.appendLine("导出时间：${dateFmt.format(Date())}")
        sb.appendLine("日志条数：${entries.size}" +
            (if (sinceMs != null) "（筛选起始：${dateFmt.format(Date(sinceMs))}）" else "（全部）"))
        sb.appendLine("日志级别：${if (verboseEnabled) "详细（DEBUG+）" else "标准（INFO+）"}")
        sb.appendLine("=".repeat(60))
        entries.forEach { entry ->
            sb.appendLine(format(entry))
            entry.throwable?.let { sb.appendLine(it) }
        }
        return sb.toString()
    }

    /** 导出到 App 专属 Downloads 目录（零权限；文件名带时间戳）。 */
    fun exportToFile(context: Context, sinceMs: Long? = null): File? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val file = File(dir, LogExport.textFileName(System.currentTimeMillis()))
        return runCatching {
            flush()
            file.writeText(exportText(sinceMs), Charsets.UTF_8)
            file
        }.getOrNull()
    }

    /**
     * 写出诊断 ZIP（kuaixia.log + device.txt + README.txt）到指定 [out]（通常为 SAF 选中的 Uri 输出流）。
     * 日志与设备摘要均已统一脱敏，不含 Cookie。
     */
    fun exportZipTo(
        out: OutputStream,
        deviceText: String,
        sinceMs: Long? = null,
    ): Boolean = runCatching {
        flush()
        LogExport.buildZip(out, exportText(sinceMs), deviceText)
        true
    }.getOrDefault(false)

    /** 设备摘要文本（导出 ZIP 的 device.txt；也用于日志页展示/复制）。 */
    fun deviceTextOf(fields: List<DeviceSummary.Field>): String = DeviceSummary.render(fields)

    // ---- 内部 --------------------------------------------------------------

    private fun makeEntry(level: LogLevel, tag: String, message: String, throwable: Throwable?): AppLog {
        val safeMessage = LogSanitizer.sanitize(message)
        val safeThrowable = throwable?.stackTraceToString()?.let(LogSanitizer::sanitize)
        return AppLog(
            id = seq.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = safeMessage,
            throwable = safeThrowable,
        )
    }

    private fun appendAsync(entry: AppLog) {
        val w = writer ?: return
        val text = buildString {
            append(format(entry))
            append('\n')
            entry.throwable?.let { append(it).append('\n') }
        }
        runCatching { w.execute { writeWithRotation(text) } }
    }

    private fun appendSync(entry: AppLog) {
        val text = buildString {
            append(format(entry))
            append('\n')
            entry.throwable?.let { append(it).append('\n') }
        }
        writeWithRotation(text)
    }

    /** 等待已投递的异步日志全部写盘（崩溃/导出前调用）。 */
    private fun flush() {
        val w = writer ?: return
        runCatching {
            val f = w.submit {}
            f.get(3, TimeUnit.SECONDS)
        }
    }

    /** 追加一行；若当前文件超限则轮转。全程 runCatching，日志系统自身绝不抛异常。 */
    private fun writeWithRotation(text: String) {
        runCatching {
            val file = logFile ?: return
            file.parentFile?.mkdirs()
            rotateIfNeeded(file)
            file.appendText(text, Charsets.UTF_8)
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.length() < LogPolicy.MAX_FILE_BYTES) return
        val d = file.parentFile ?: return
        // .1 -> .2，.log -> .1，删除超出 MAX_FILES 的旧档
        for (i in LogPolicy.MAX_FILES - 1 downTo 1) {
            val src = if (i == 1) File(d, "${LogPolicy.LOG_FILE}.1")
            else File(d, "${LogPolicy.LOG_FILE}.${i - 1}")
            val dst = File(d, "${LogPolicy.LOG_FILE}.$i")
            if (i == LogPolicy.MAX_FILES - 1) dst.delete() else src.copyTo(dst, overwrite = true)
        }
        file.copyTo(File(d, "${LogPolicy.LOG_FILE}.1"), overwrite = true)
        file.delete()
    }

    fun format(entry: AppLog): String =
        "[${timeFmt.format(Date(entry.timestamp))}] ${entry.level.name}/${entry.tag} ${entry.message}"

    fun formatFullDate(timestamp: Long): String = dateFmt.format(Date(timestamp))
}
