package com.kuaixia.app.data.download

import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "Downloader"

/**
 * HTTP 直链下载器（Phase 3 第一阶段）。
 *
 * - 用 OkHttp 流式读取 ResponseBody，边下载边写文件；
 * - 通过 [Call] 保存实现取消；
 * - 支持 Range 断点续传（暂停后继续）。
 *
 * 仅支持 http/https 直链（MP4 等），不处理 M3U8/分片/合并（留给后续 Phase）。
 *
 * ## BUG-003：瞬时网络失败有限重试
 * 对**普通直链**（[DownloadKind.DIRECT] / [DownloadKind.IMAGE]，以及 DASH 的单条流）的
 * 瞬时网络错误（connect 超时 / read 超时 / connection reset / EOF 等）做**有限次**内部重试，
 * 由纯逻辑 [DownloadRetryPolicy] 决策。约束：
 * - 重试次数是**方法内局部变量** `internalAttempt`，**绝不写入** [DownloadTask.retryCount]
 *   （该字段是 403/404/410 重解析一次守卫，被 `DownloadRepository.canReparse` 复用）；
 * - 403/404/410 **不在此重试**，而是带结构化 httpCode 返回，交由 `handleFailure` 走原 reparse 流程；
 * - 重试期间**不改状态、不删文件、不重置进度**；取消检查优先于退避等待。
 *
 * [DownloadKind.M3U8] 由调用方传入 `isM3U8 = true` 排除（M3U8Downloader 自带 3 次分片重试）。
 */
class HttpDownloader(
    private val retryPolicy: DownloadRetryPolicy = DownloadRetryPolicy(),
) : Downloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // taskId -> 进行中的 Call（用于取消）；taskId -> 取消标记（用于区分取消 vs 网络错误）
    private val calls = ConcurrentHashMap<String, Call>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override suspend fun download(
        task: DownloadTask,
        offset: Long,
        progress: (DownloadProgress) -> Unit,
        onContentType: (String?) -> Unit,
    ): Result<File> = downloadInternal(task, offset, progress, onContentType, isM3U8 = false)

    /**
     * **分包链路专用**入口：与 [download] 行为完全一致，但关闭内部重试。
     *
     * DASH / M3U8 的每一段由调用方（[DownloadRepository.downloadDash] / `M3U8Downloader`）
     * 自行负责续传与重试，`HttpDownloader` 不再叠加，避免 3×3=9 次重试。
     *
     * 之所以独立成方法而非给 [Downloader.download] 加参数：`Downloader` 是公共接口，
     * 加参会波及所有实现与调用方，超出 BUG-003 的最小改动范围。
     */
    suspend fun downloadSegment(
        task: DownloadTask,
        offset: Long = 0,
        progress: (DownloadProgress) -> Unit,
        onContentType: (String?) -> Unit = {},
    ): Result<File> = downloadInternal(task, offset, progress, onContentType, isM3U8 = true)

    /**
     * 带 [isM3U8] 开关的下载实现。
     *
     * M3U8/DASH 分包链路（调用方已自行重试）传 `isM3U8 = true`：
     * 届时瞬时错误走 [DownloadRetryPolicy.decide] 的 `isM3U8` 短路（→ FAIL），
     * 避免与 `M3U8Downloader` 的 3 次分片重试叠乘成 3×3=9 次。
     */
    @Suppress("LongMethod")
    private suspend fun downloadInternal(
        task: DownloadTask,
        offset: Long,
        progress: (DownloadProgress) -> Unit,
        onContentType: (String?) -> Unit,
        isM3U8: Boolean,
    ): Result<File> = withContext(Dispatchers.IO) {
        val file = File(task.saveDir, task.fileName)
        file.parentFile?.mkdirs()

        val requestBuilder = Request.Builder().url(task.url)

        // 原样附加 yt-dlp 提供的请求头（如 Referer/User-Agent），规避 CDN 403。
        // 逐个校验：OkHttp 对 header name/value 有严格限制（name 须为可见 ASCII，
        // value 不能含控制字符或非 ASCII），非法值会导致 IllegalArgumentException 崩溃。
        // 这里跳过非法项并记录，绝不因单个 header 让整个下载崩溃。
        val headers = task.httpHeaders
        // 诊断专用（本轮只增证据）：显式暴露「本次实际发出的请求头」，便于区分
        // headersEmpty=true（构造性零请求头，即 PageJsonMerge 路径的 emptyMap()）
        // 与 headersEmpty=false（带 WebView 风格头，如 User-Agent/Referer/sec-ch-ua）。
        // headersToString 只输出 key=value 且敏感值已 <redacted>；headerKeys 只输出 key。
        AppLogRepository.i(
            com.kuaixia.app.core.log.LogTags.HTTP,
            "HTTP_PREPARE url=${LogSanitizer.sanitizeUrl(task.url)} " +
                "host=${runCatching { java.net.URI(task.url).host }.getOrNull().orEmpty()} " +
                "kind=${task.kind} headersEmpty=${headers.isEmpty()} " +
                "headersKeys=[${LogSanitizer.headerKeys(headers)}] " +
                "headers=[${LogSanitizer.headersToString(headers)}]",
        )
        headers.forEach { (name, value) ->
            HttpHeaders.addSafely(requestBuilder, name, value) { warn ->
                AppLogRepository.w(LogTags.HTTP, warn)
            }
        }
        if (offset > 0) requestBuilder.header("Range", "bytes=$offset-")
        val request = requestBuilder.build()

        // BUG-003：内部重试计数，**局部变量、绝不落库**（不污染 retryCount）
        var internalAttempt = 0

        while (true) {
            internalAttempt++
            if (cancelled.contains(task.id)) {
                AppLogger.i("download cancelled before attempt=$internalAttempt task=${task.id}", TAG)
                throw DownloadCancelledException()
            }

            val call = client.newCall(request)
            calls[task.id] = call
            try {
                return@withContext performOnce(
                    task = task,
                    call = call,
                    offset = offset,
                    file = file,
                    progress = progress,
                    onContentType = onContentType,
                    isM3U8 = isM3U8,
                    internalAttempt = internalAttempt,
                    request = request,
                )
            } catch (e: IOException) {
                if (cancelled.remove(task.id)) {
                    AppLogger.i("download cancelled task=${task.id}", TAG)
                    throw DownloadCancelledException()
                }
                AppLogger.w(
                    "download io error task=${task.id} attempt=$internalAttempt err=${e.message}",
                    TAG,
                )
                // RetryableHttpException 已由 performOnce 判定为可重试；
                // 其它 IOException 走 policy 分类（裸 IOException → FAIL，不盲目重试）
                val decision = if (e is RetryableHttpException) {
                    RetryDecision.RETRY
                } else {
                    retryPolicy.decide(
                        error = e,
                        httpCode = null,
                        attempt = internalAttempt,
                        isM3U8 = isM3U8,
                        cancelled = false,
                    )
                }
                if (decision == RetryDecision.RETRY) {
                    val waitMs = retryPolicy.delayMsFor(internalAttempt)
                    AppLogRepository.w(
                        LogTags.HTTP,
                        "${logCauseOf(e)}，${waitMs}ms 后重试 ${internalAttempt}/${retryPolicy.maxRetries} " +
                            "task=${task.id} err=${e.message}",
                    )
                    // 取消优先于等待：delay 是 suspend 的，协程取消会自然传播
                    if (cancelled.contains(task.id)) throw DownloadCancelledException()
                    delay(waitMs)
                    if (cancelled.remove(task.id)) {
                        AppLogger.i("download cancelled during backoff task=${task.id}", TAG)
                        throw DownloadCancelledException()
                    }
                    continue
                }
                return@withContext Result.failure(
                    AppException(
                        ErrorCode.NETWORK_ERROR,
                        "网络错误，下载中断：${e.message}",
                        detail = e.javaClass.simpleName,
                    ),
                )
            } finally {
                calls.remove(task.id)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        Result.failure(AppException(ErrorCode.UNKNOWN_ERROR, "下载失败"))
    }

    /**
     * 单次尝试：发请求 → 判状态码 → 流式落盘。
     *
     * 成功返回 [Result<File>]；瞬时失败抛出 [IOException] 交由外层重试循环决策；
     * 需重解析/确定失败则以 [`AppException.detail`] 携带**结构化 httpCode** 返回
     * （`HTTP_CODE:<n>`，不再依赖中文 message 正则回捞）。
     */
    private suspend fun performOnce(
        task: DownloadTask,
        call: Call,
        offset: Long,
        file: File,
        progress: (DownloadProgress) -> Unit,
        onContentType: (String?) -> Unit,
        isM3U8: Boolean,
        internalAttempt: Int,
        request: Request,
    ): Result<File> {
        val response: Response = call.execute()
        if (!response.isSuccessful) {
            // 416 = Range 不满足：说明文件已下载完整（offset >= 总大小），视为完成
            if (response.code == 416 && offset > 0) {
                AppLogger.i("416 range not satisfiable, treat as completed task=${task.id}", TAG)
                response.close()
                return Result.success(file)
            }
            val code = response.code
            AppLogRepository.e(
                LogTags.HTTP,
                "下载失败 HTTP $code url=${LogSanitizer.sanitizeUrl(task.url)} " +
                    "请求头keys=[${LogSanitizer.headerKeys(task.httpHeaders)}]",
            )
            // BUG-003：瞬时 HTTP（429 / 408 / 5xx）走同一有限重试策略（等待时长尊重 Retry-After）
            val decision = retryPolicy.decide(
                error = null,
                httpCode = code,
                attempt = internalAttempt,
                isM3U8 = isM3U8,
                cancelled = false,
            )
            val retryAfterMs = DownloadRetryPolicy.parseRetryAfterMs(response.header("Retry-After"))
            response.close()
            if (decision == RetryDecision.RETRY) {
                val waitMs = retryPolicy.delayMsFor(internalAttempt, retryAfterMs)
                AppLogRepository.w(
                    LogTags.HTTP,
                    "HTTP $code 可重试，${waitMs}ms 后重试 ${internalAttempt}/${retryPolicy.maxRetries} task=${task.id}",
                )
                if (cancelled.contains(task.id)) throw DownloadCancelledException()
                delay(waitMs)
                if (cancelled.remove(task.id)) {
                    AppLogger.i("download cancelled during backoff task=${task.id}", TAG)
                    throw DownloadCancelledException()
                }
                // 交回外层循环：以 IOException 语义传达「可重试」，避免落入 handleFailure
                throw RetryableHttpException(code)
            }
            // 303 等之外：REPARSE（403/404/410）或 FAIL —— 均以结构化 httpCode 返回
            return Result.failure(
                AppException(
                    ErrorCode.NETWORK_ERROR,
                    "下载失败（HTTP $code）",
                    detail = "HTTP_CODE:$code",
                ),
            )
        }

        val body = response.body
        if (body == null) {
            AppLogRepository.e(LogTags.HTTP, "响应体为空 url=${LogSanitizer.sanitizeUrl(task.url)}")
            response.close()
            return Result.failure(
                AppException(ErrorCode.PARSER_ERROR, "下载失败：响应为空"),
            )
        }

        AppLogRepository.i(
            LogTags.HTTP,
            "响应 code=${response.code} contentLength=${body.contentLength()} " +
                "contentType=${body.contentType()}",
        )
        onContentType(response.header("Content-Type"))

        // 206 = 续传成功；200 = 服务器忽略 Range（从头下载）
        val resumed = response.code == 206
        val contentLength = body.contentLength().takeIf { it > 0 }
        val totalBytes = when {
            resumed && contentLength != null -> offset + contentLength
            !resumed -> contentLength ?: -1L
            else -> -1L
        }

        body.byteStream().use { input ->
            FileOutputStream(file, resumed).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = if (resumed) offset else 0L
                var lastReportAt = System.currentTimeMillis()
                var lastReportedBytes = downloaded

                while (true) {
                    val len = input.read(buffer)
                    if (len == -1) break
                    output.write(buffer, 0, len)
                    downloaded += len

                    val now = System.currentTimeMillis()
                    val dt = now - lastReportAt
                    if (dt >= SPEED_INTERVAL_MS) {
                        val speed = ((downloaded - lastReportedBytes) * 1000) / dt.coerceAtLeast(1)
                        lastReportedBytes = downloaded
                        lastReportAt = now
                        progress(DownloadProgress(downloaded, totalBytes, speed))
                    }
                }
                progress(DownloadProgress(downloaded, totalBytes, 0))
            }
        }

        AppLogger.i("download ok task=${task.id} bytes=$totalBytes path=${file.absolutePath}", TAG)
        return Result.success(file)
    }

    /** 重试日志的原因短语（避免把两种来源混为一谈）。 */
    private fun logCauseOf(e: IOException): String =
        if (e is RetryableHttpException) "HTTP ${e.httpCode} 可重试" else "瞬时网络错误"

    override fun cancel(taskId: String) {
        cancelled.add(taskId)
        calls[taskId]?.cancel()
    }

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val SPEED_INTERVAL_MS = 500L
    }
}

/**
 * 表示「本次尝试因 HTTP 可重试状态码失败」——仅用于 [HttpDownloader] 内部
 * 把控制权交回重试循环，**不会**被 `DownloadRepository.handleFailure` 当作业务失败处理。
 *
 * @param httpCode 触发重试的 HTTP 状态码（429/408/5xx）
 */
internal class RetryableHttpException(val httpCode: Int) : IOException("retryable HTTP $httpCode")
