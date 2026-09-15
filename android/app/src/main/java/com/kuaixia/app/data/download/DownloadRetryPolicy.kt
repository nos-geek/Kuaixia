package com.kuaixia.app.data.download

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * 单次下载失败后的处置决策（BUG-003）。
 *
 * 语义严格区分：
 * - [RETRY]   **同一 URL** 重发请求（瞬时网络错误 / 限流 / 服务端瞬时 5xx）；
 * - [REPARSE] URL 已失效，需重新解析原始页面换新直链（403/404/410）；
 * - [FAIL]    确定性失败，重试与换链均无意义，直接标记失败；
 * - [CANCEL]  用户主动取消或协程取消，**绝不重试**。
 */
enum class RetryDecision { RETRY, REPARSE, FAIL, CANCEL }

/**
 * 普通直链下载的**瞬时网络失败有限重试**策略（BUG-003）。
 *
 * ## 设计约束（来自 BUG-003 取证报告）
 * - **零 Android 依赖、零 IO、零协程**：纯逻辑，100% JVM 可测（仿 [ProgressCoalescer] 范式）；
 * - **不污染 [DownloadTask.retryCount]**：[retryCount] 被 `DownloadRepository.canReparse`
 *   的 `== 0` 守卫复用（403/404/410 只重解析一次）。内部重试次数由调用方局部变量
 *   `internalAttempt` 持有，**绝不落库**；
 * - **排除 M3U8/DASH**：`isM3U8 = true` 一律 [RetryDecision.FAIL]。[M3U8Downloader] 自身已做
 *   3 次分片重试，外部再叠加会变成 3×3=9 次；
 * - **不把普通 [IOException] 当瞬时错误**：无法可靠确认的 IO 异常（含磁盘写失败
 *   [FileNotFoundException]）一律 [RetryDecision.FAIL]，防止本地故障被反复重试。
 *
 * @param maxRetries     最大内部重试次数（默认 3，与 M3U8 先例一致）
 * @param baseDelayMs    首次重试退避基数（默认 500ms）
 * @param maxDelayMs     单次退避上限（默认 8000ms）
 * @param jitterRatio    抖动比例（默认 0.2；设 0 可得确定性延迟，便于单测）
 * @param retryOnUnknownHost [UnknownHostException] 是否允许有限重试（DNS 抖动通常瞬时）
 */
class DownloadRetryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val jitterRatio: Double = DEFAULT_JITTER_RATIO,
    val retryOnUnknownHost: Boolean = true,
) {

    /**
     * 判定一次失败。
     *
     * @param error    抛出的异常（可为 null —— 例如只有 HTTP 状态码而没抛异常）
     * @param httpCode HTTP 状态码（**结构化传入**，不再从中文 message 正则回捞）
     * @param attempt  第几次尝试（首次 = 1）。已达 [maxRetries] 则不再 [RetryDecision.RETRY]
     * @param isM3U8   是否属于 M3U8/DASH 链路（true → 一律 FAIL，禁止叠加）
     * @param cancelled 是否已被用户取消（true → CANCEL，最高优先级）
     */
    fun decide(
        error: Throwable?,
        httpCode: Int? = null,
        attempt: Int = 1,
        isM3U8: Boolean = false,
        cancelled: Boolean = false,
    ): RetryDecision {
        // 1. 取消最高优先：不重试、不换链、不失败
        if (cancelled) return RetryDecision.CANCEL
        if (error is CancellationException) return RetryDecision.CANCEL
        if (error is DownloadCancelledException) return RetryDecision.CANCEL

        // 2. M3U8/DASH 已自带重试，外部绝不叠加
        if (isM3U8) return RetryDecision.FAIL

        // 3. HTTP 状态码优先（比异常类型更可靠）
        if (httpCode != null) {
            when (httpCode) {
                403, 404, 410 -> return RetryDecision.REPARSE
                408, 429 -> return retryOrFail(attempt)
                in 500..599 -> return retryOrFail(attempt)
                in 400..499 -> return RetryDecision.FAIL
                in 300..399 -> return RetryDecision.FAIL
                in 200..299 -> return RetryDecision.FAIL // 理论上不会走到这里
            }
        }

        // 4. 异常类型分类
        when (error) {
            // 明确瞬时网络异常 → 有限重试
            is ConnectException,
            is SocketTimeoutException,
            is EOFException,
            is NoRouteToHostException,
            is PortUnreachableException,
            -> return retryOrFail(attempt)

            // SocketException 仅在明确连接异常时重试（reset / broken pipe / shutdown）
            is SocketException -> {
                return if (isTransientSocketMessage(error.message)) {
                    retryOrFail(attempt)
                } else {
                    RetryDecision.FAIL
                }
            }

            // DNS 抖动通常瞬时，但需单独可配（默认允许有限重试）
            is UnknownHostException ->
                return if (retryOnUnknownHost) retryOrFail(attempt) else RetryDecision.FAIL

            // 证书错误：重试无效，但 TLS 握手偶发失败可单次重试
            is SSLException -> return if (attempt < 2) retryOrFail(attempt) else RetryDecision.FAIL

            // 裸 InterruptedIOException（非 SocketTimeout）：确认是超时语义才重试
            is InterruptedIOException -> return retryOrFail(attempt)

            // 本地磁盘/文件问题：绝不能重试（磁盘满、无权限、路径非法）
            is FileNotFoundException -> return RetryDecision.FAIL
        }

        // 5. 无法可靠确认属于瞬时网络错误的普通 IOException → FAIL（防本地写盘异常被重试）
        return RetryDecision.FAIL
    }

    /**
     * 第 [attempt] 次重试前的退避时长（指数退避 + 抖动）。
     *
     * attempt 1 → 500ms / attempt 2 → 1000ms / attempt 3 → 2000ms（默认参数下），
     * 上限 [maxDelayMs]。
     *
     * @param attempt      即将重试的序号（1 起）
     * @param retryAfterMs 响应头 `Retry-After` 解析值（若有则优先，仍受 [maxDelayMs] 约束）
     */
    fun delayMsFor(attempt: Int, retryAfterMs: Long? = null): Long {
        val exp = baseDelayMs shl (attempt.coerceAtLeast(1) - 1).coerceAtMost(20)
        val backoff = exp.coerceAtMost(maxDelayMs)
        val base = retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(maxDelayMs) ?: backoff
        if (jitterRatio <= 0.0) return base
        val span = (base * jitterRatio).toLong()
        if (span <= 0L) return base
        return (base - span) + (jitterBucket(attempt) % (span * 2 + 1))
    }

    /** 抖动确定性来源：**不引入 RNG**，保证同一 (base, attempt) 结果可预测、可单测。 */
    private fun jitterBucket(attempt: Int): Long = attempt.toLong() * 2_654_435_761L

    /** 已达上限 → FAIL，否则 RETRY。 */
    private fun retryOrFail(attempt: Int): RetryDecision =
        if (attempt >= maxRetries) RetryDecision.FAIL else RetryDecision.RETRY

    /** 从 `Retry-After` 响应头解析等待毫秒数（秒数或 HTTP-date）。解析失败返回 null。 */
    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_BASE_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
        const val DEFAULT_JITTER_RATIO = 0.2

        /** 明确表示连接异常的 SocketException 文本特征（大小写不敏感）。 */
        private val TRANSIENT_SOCKET_HINTS = listOf(
            "connection reset",
            "broken pipe",
            "connection shutdown",
            "software caused connection abort",
            "connection aborted",
        )

        internal fun isTransientSocketMessage(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            val lower = message.lowercase()
            return TRANSIENT_SOCKET_HINTS.any { lower.contains(it) }
        }

        /**
         * 解析 `Retry-After` 头。
         * 支持「秒数」与「HTTP-date」两种形式；解析失败返回 null（调用方回退本地 backoff）。
         */
        fun parseRetryAfterMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
            if (value.isNullOrBlank()) return null
            value.trim().toLongOrNull()?.let { return (it * 1000).coerceAtLeast(0) }
            return runCatching {
                val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                val at = fmt.parse(value.trim())?.time ?: return null
                (at - nowMs).coerceAtLeast(0)
            }.getOrNull()
        }
    }
}
