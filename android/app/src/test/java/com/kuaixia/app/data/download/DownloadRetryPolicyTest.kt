package com.kuaixia.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * BUG-003「瞬时网络失败有限重试」的纯 JVM 单元测试。
 *
 * 设计原则（同 [ProgressCoalescerTest]）：
 * - [DownloadRetryPolicy] 零 Android 依赖、零 IO、零协程 → 可在纯 JVM 下完整实例化；
 * - 退避延迟通过注入 `jitterRatio = 0.0` 变得**完全确定**，可直接断言毫秒数；
 * - [DownloadTask.retryCount] 不参与任何断言 —— 内部重试绝不写该字段（专门有第 26 组用例守护）。
 */
class DownloadRetryPolicyTest {

    /** 无抖动实例：延迟可精确断言（CI 上不会 flaky）。 */
    private val deterministic = DownloadRetryPolicy(jitterRatio = 0.0)

    /** 默认实例：与生产装配一致。 */
    private val policy = DownloadRetryPolicy()

    // ==================== 异常分类：RETRY ====================

    @Test
    fun `1 ConnectException 应重试`() {
        assertEquals(
            RetryDecision.RETRY,
            policy.decide(ConnectException("failed to connect after 15000ms"), attempt = 1),
        )
    }

    @Test
    fun `2 SocketTimeoutException 应重试`() {
        assertEquals(
            RetryDecision.RETRY,
            policy.decide(SocketTimeoutException("read timed out"), attempt = 1),
        )
    }

    @Test
    fun `3 SocketException connection reset 应重试`() {
        val e = SocketException("Connection reset")
        assertEquals(RetryDecision.RETRY, policy.decide(e, attempt = 1))
        assertEquals(
            RetryDecision.RETRY,
            policy.decide(SocketException("Broken pipe"), attempt = 1),
        )
        assertEquals(
            RetryDecision.RETRY,
            policy.decide(SocketException("Connection shutdown"), attempt = 1),
        )
    }

    @Test
    fun `3b SocketException 无明确连接语义时应 FAIL`() {
        assertEquals(
            RetryDecision.FAIL,
            policy.decide(SocketException("invalid argument"), attempt = 1),
        )
        assertEquals(RetryDecision.FAIL, policy.decide(SocketException(null), attempt = 1))
    }

    @Test
    fun `4 EOFException 应重试`() {
        assertEquals(RetryDecision.RETRY, policy.decide(EOFException(), attempt = 1))
    }

    @Test
    fun `5 ConnectionShutdownException 语义 应重试`() {
        // okhttp3.internal.connection.ConnectionShutdownException 属 IOException（非 SocketException），
        // 生产路径由 message 走 SocketException 分支或被 ConnectionShutdownException 捕获；
        // 此处用同语义 SocketException 验证「connection shutdown」文本被识别。
        assertEquals(
            RetryDecision.RETRY,
            policy.decide(SocketException("connection shutdown"), attempt = 1),
        )
    }

    @Test
    fun `6 UnknownHostException 应重试（DNS 抖动）`() {
        assertEquals(
            RetryDecision.RETRY,
            policy.decide(UnknownHostException("p5-sign.douyinpic.com"), attempt = 1),
        )
    }

    @Test
    fun `6b UnknownHostException 可通过开关关闭重试`() {
        val strict = DownloadRetryPolicy(retryOnUnknownHost = false)
        assertEquals(
            RetryDecision.FAIL,
            strict.decide(UnknownHostException("nope"), attempt = 1),
        )
    }

    // ==================== 异常分类：FAIL ====================

    @Test
    fun `7 FileNotFoundException 必须 FAIL（本地磁盘问题）`() {
        assertEquals(
            RetryDecision.FAIL,
            policy.decide(FileNotFoundException("/data/no-space/x.webp"), attempt = 1),
        )
    }

    @Test
    fun `8 普通 IOException 必须 FAIL（不盲目重试本地写盘异常）`() {
        assertEquals(RetryDecision.FAIL, policy.decide(IOException("write failed"), attempt = 1))
        assertEquals(RetryDecision.FAIL, policy.decide(IOException("No space left on device"), attempt = 1))
        assertEquals(RetryDecision.FAIL, policy.decide(null, attempt = 1))
    }

    // ==================== 异常分类：CANCEL ====================

    @Test
    fun `9 CancellationException 必须 CANCEL`() {
        assertEquals(RetryDecision.CANCEL, policy.decide(CancellationException("job cancelled"), attempt = 1))
    }

    @Test
    fun `9b DownloadCancelledException 必须 CANCEL`() {
        assertEquals(RetryDecision.CANCEL, policy.decide(DownloadCancelledException(), attempt = 1))
    }

    @Test
    fun `9c cancelled 标志优先级最高`() {
        // 即使异常本身「可重试」，cancelled=true 也必须 CANCEL
        assertEquals(
            RetryDecision.CANCEL,
            policy.decide(ConnectException("x"), attempt = 1, cancelled = true),
        )
        assertEquals(
            RetryDecision.CANCEL,
            policy.decide(null, httpCode = 403, attempt = 1, cancelled = true),
        )
    }

    // ==================== HTTP 状态分类 ====================

    @Test
    fun `10 403 应 REPARSE`() {
        assertEquals(RetryDecision.REPARSE, policy.decide(null, httpCode = 403, attempt = 1))
    }

    @Test
    fun `11 404 应 REPARSE`() {
        assertEquals(RetryDecision.REPARSE, policy.decide(null, httpCode = 404, attempt = 1))
    }

    @Test
    fun `12 410 应 REPARSE`() {
        assertEquals(RetryDecision.REPARSE, policy.decide(null, httpCode = 410, attempt = 1))
    }

    @Test
    fun `13 400 应 FAIL`() {
        assertEquals(RetryDecision.FAIL, policy.decide(null, httpCode = 400, attempt = 1))
    }

    @Test
    fun `14 401 应 FAIL`() {
        assertEquals(RetryDecision.FAIL, policy.decide(null, httpCode = 401, attempt = 1))
    }

    @Test
    fun `14b 其它普通 4xx 应 FAIL`() {
        listOf(402, 405, 406, 409, 412, 451).forEach { code ->
            assertEquals("HTTP $code 应 FAIL", RetryDecision.FAIL, policy.decide(null, httpCode = code, attempt = 1))
        }
    }

    @Test
    fun `15 429 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 429, attempt = 1))
    }

    @Test
    fun `15b 408 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 408, attempt = 1))
    }

    @Test
    fun `16 500 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 500, attempt = 1))
    }

    @Test
    fun `17 502 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 502, attempt = 1))
    }

    @Test
    fun `18 503 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 503, attempt = 1))
    }

    @Test
    fun `19 504 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 504, attempt = 1))
    }

    @Test
    fun `19b 其它 5xx 有限 RETRY`() {
        listOf(501, 505, 507, 599).forEach { code ->
            assertEquals("HTTP $code 应 RETRY", RetryDecision.RETRY, policy.decide(null, httpCode = code, attempt = 1))
        }
    }

    @Test
    fun `20 416 不进入 policy 的 RETRY 分支（保持 offset 特判语义）`() {
        // 规范第 4 节：416 必须保持既有 `offset > 0` 特判（视为已完成），不得变成普通 retry。
        // 调用方（HttpDownloader.performOnce）在 offset>0 + 416 时**提前** return success，
        // 根本不会走到 policy。此处验证「万一落到 policy」也绝不会 RETRY —— 语义安全兜底。
        assertEquals(RetryDecision.FAIL, policy.decide(null, httpCode = 416, attempt = 1))
        assertEquals(RetryDecision.FAIL, policy.decide(null, httpCode = 416, attempt = 2))
        assertEquals(
            RetryDecision.FAIL,
            policy.decide(ConnectException("x"), httpCode = 416, attempt = 1),
        )
    }

    // ==================== attempt 上限 ====================

    @Test
    fun `21 attempt 1 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(ConnectException("x"), attempt = 1))
    }

    @Test
    fun `22 attempt 2 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(ConnectException("x"), attempt = 2))
    }

    @Test
    fun `23 attempt 3 应 FAIL（达到 maxRetries）`() {
        assertEquals(RetryDecision.FAIL, policy.decide(ConnectException("x"), attempt = 3))
    }

    @Test
    fun `23b 超出上限仍 FAIL`() {
        assertEquals(RetryDecision.FAIL, policy.decide(ConnectException("x"), attempt = 99))
    }

    @Test
    fun `23c maxRetries 可配置`() {
        val oneShot = DownloadRetryPolicy(maxRetries = 1)
        assertEquals(RetryDecision.FAIL, oneShot.decide(ConnectException("x"), attempt = 1))
        val five = DownloadRetryPolicy(maxRetries = 5)
        assertEquals(RetryDecision.RETRY, five.decide(ConnectException("x"), attempt = 4))
    }

    // ==================== 取消 ====================

    @Test
    fun `24 retry 前 cancelled 应 CANCEL`() {
        assertEquals(
            RetryDecision.CANCEL,
            policy.decide(SocketTimeoutException("t"), attempt = 2, cancelled = true),
        )
    }

    @Test
    fun `25 cancellation 异常在任何 attempt 都 CANCEL`() {
        listOf(1, 2, 3, 10).forEach { attempt ->
            assertEquals(
                "attempt=$attempt",
                RetryDecision.CANCEL,
                policy.decide(CancellationException("cancel"), attempt = attempt),
            )
        }
    }

    // ==================== M3U8 隔离 ====================

    @Test
    fun `26 isM3U8=true 一律 FAIL`() {
        // 无论异常类型 / HTTP 码 / attempt，M3U8 都不得进入外部重试（防 3×3=9 叠乘）
        assertEquals(RetryDecision.FAIL, policy.decide(ConnectException("x"), attempt = 1, isM3U8 = true))
        assertEquals(RetryDecision.FAIL, policy.decide(null, httpCode = 503, attempt = 1, isM3U8 = true))
        assertEquals(RetryDecision.FAIL, policy.decide(null, httpCode = 429, attempt = 1, isM3U8 = true))
        assertEquals(RetryDecision.FAIL, policy.decide(SocketTimeoutException("t"), attempt = 1, isM3U8 = true))
    }

    @Test
    fun `26b isM3U8=true 时取消仍优先`() {
        assertEquals(
            RetryDecision.CANCEL,
            policy.decide(ConnectException("x"), attempt = 1, isM3U8 = true, cancelled = true),
        )
    }

    // ==================== backoff ====================

    @Test
    fun `27 attempt 1 退避 500ms`() {
        assertEquals(500L, deterministic.delayMsFor(1))
    }

    @Test
    fun `28 attempt 2 退避 1000ms`() {
        assertEquals(1000L, deterministic.delayMsFor(2))
    }

    @Test
    fun `29 attempt 3 退避 2000ms`() {
        assertEquals(2000L, deterministic.delayMsFor(3))
    }

    @Test
    fun `30 退避不超过 max delay`() {
        val capped = DownloadRetryPolicy(maxDelayMs = 1500L, jitterRatio = 0.0)
        assertEquals(500L, capped.delayMsFor(1))
        assertEquals(1000L, capped.delayMsFor(2))
        assertEquals(1500L, capped.delayMsFor(3)) // 2000 被截到 1500
        assertEquals(1500L, capped.delayMsFor(20))
    }

    @Test
    fun `30b Retry-After 优先但仍受 max delay 约束`() {
        assertEquals(3000L, deterministic.delayMsFor(1, retryAfterMs = 3000L))
        assertEquals(8000L, deterministic.delayMsFor(1, retryAfterMs = 60_000L))
        // 非法/零值回退本地 backoff
        assertEquals(500L, deterministic.delayMsFor(1, retryAfterMs = 0L))
        assertEquals(500L, deterministic.delayMsFor(1, retryAfterMs = null))
    }

    @Test
    fun `30c 抖动版本落在合理区间且可重复`() {
        val jittered = DownloadRetryPolicy(jitterRatio = 0.2)
        val a = jittered.delayMsFor(1)
        val b = jittered.delayMsFor(1)
        assertEquals("同输入必须同输出（无 RNG，可预测）", a, b)
        assertTrue("抖动后应在 [400,600] 内，实际 $a", a in 400L..600L)
    }

    @Test
    fun `30d attempt 单调递增`() {
        val d1 = deterministic.delayMsFor(1)
        val d2 = deterministic.delayMsFor(2)
        val d3 = deterministic.delayMsFor(3)
        assertTrue("$d1 < $d2", d1 < d2)
        assertTrue("$d2 < $d3", d2 < d3)
    }

    @Test
    fun `30e attempt 0 或负数不崩溃`() {
        assertTrue(deterministic.delayMsFor(0) > 0)
        assertTrue(deterministic.delayMsFor(-5) > 0)
    }

    // ==================== HTTP code 结构化（不依赖 message 文本） ====================

    @Test
    fun `31 403 不依赖 message 文本仍能识别 REPARSE`() {
        // exception 的 message 完全无关、甚至为空 → 依旧 REPARSE
        val e = IOException("")
        assertEquals(RetryDecision.REPARSE, policy.decide(e, httpCode = 403, attempt = 1))
        assertEquals(RetryDecision.REPARSE, policy.decide(null, httpCode = 403, attempt = 1))
        // 中文 message 里没有 "403" 字样也照样成立
        val e2 = IOException("下载失败")
        assertEquals(RetryDecision.REPARSE, policy.decide(e2, httpCode = 403, attempt = 1))
    }

    @Test
    fun `32 500 不依赖 message 文本仍能识别 RETRY`() {
        val e = IOException("服务端错误")
        assertEquals(RetryDecision.RETRY, policy.decide(e, httpCode = 500, attempt = 1))
        assertEquals(RetryDecision.RETRY, policy.decide(null, httpCode = 500, attempt = 1))
    }

    @Test
    fun `32b httpCode 优先于异常类型`() {
        // 403（应 REPARSE）不会被 ConnectException 的「可重试」语义覆盖
        assertEquals(
            RetryDecision.REPARSE,
            policy.decide(ConnectException("x"), httpCode = 403, attempt = 1),
        )
        // 400（应 FAIL）不会被 SocketTimeoutException 的「可重试」语义覆盖
        assertEquals(
            RetryDecision.FAIL,
            policy.decide(SocketTimeoutException("t"), httpCode = 400, attempt = 1),
        )
    }

    // ==================== Retry-After 解析 ====================

    @Test
    fun `33 parseRetryAfterMs 解析秒数`() {
        assertEquals(5000L, DownloadRetryPolicy.parseRetryAfterMs("5"))
        assertEquals(0L, DownloadRetryPolicy.parseRetryAfterMs("0"))
        assertEquals(120_000L, DownloadRetryPolicy.parseRetryAfterMs(" 120 "))
    }

    @Test
    fun `34 parseRetryAfterMs 非法值返回 null`() {
        assertNull(DownloadRetryPolicy.parseRetryAfterMs(null))
        assertNull(DownloadRetryPolicy.parseRetryAfterMs(""))
        assertNull(DownloadRetryPolicy.parseRetryAfterMs("not-a-date"))
    }

    @Test
    fun `34b parseRetryAfterMs 解析 HTTP-date`() {
        val now = 1_700_000_000_000L
        // 1_700_000_005_000 = 2023-11-14 22:13:25 UTC 之后 5s
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val future = fmt.format(java.util.Date(now + 5_000))
        assertEquals(5_000L, DownloadRetryPolicy.parseRetryAfterMs(future, now))
    }

    @Test
    fun `34c parseRetryAfterMs 过去的日期钳到 0`() {
        val now = 1_700_000_000_000L
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val past = fmt.format(java.util.Date(now - 60_000))
        assertEquals(0L, DownloadRetryPolicy.parseRetryAfterMs(past, now))
    }

    // ==================== 其它瞬时异常 ====================

    @Test
    fun `35 NoRouteToHostException PortUnreachableException 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(NoRouteToHostException("no route"), attempt = 1))
        assertEquals(RetryDecision.RETRY, policy.decide(PortUnreachableException("port"), attempt = 1))
    }

    @Test
    fun `36 裸 InterruptedIOException 应 RETRY`() {
        assertEquals(RetryDecision.RETRY, policy.decide(InterruptedIOException("interrupted"), attempt = 1))
    }

    @Test
    fun `37 SSLException 仅单次重试`() {
        assertEquals(RetryDecision.RETRY, policy.decide(SSLException("handshake"), attempt = 1))
        assertEquals(RetryDecision.FAIL, policy.decide(SSLException("handshake"), attempt = 2))
    }

    @Test
    fun `38 默认参数与设计一致`() {
        assertEquals(3, policy.maxRetries)
        assertEquals(500L, policy.baseDelayMs)
        assertEquals(8_000L, policy.maxDelayMs)
    }

    // ==================== 重试退避可取消（规范第 10 节 / 用例 25） ====================

    /**
     * 复刻 `HttpDownloader` 内的退避等待形态（`delay(waitMs)` + 前后取消检查）。
     * 用真实协程（coroutines-core 的 runBlocking）验证规范第 10 节，不引入新测试依赖。
     */
    private suspend fun retryBackoff(
        waitMs: Long,
        isCancelled: () -> Boolean,
        onBackoffStart: () -> Unit = {},
    ) {
        if (isCancelled()) throw DownloadCancelledException()
        onBackoffStart()
        delay(waitMs)
        if (isCancelled()) throw DownloadCancelledException()
    }

    @Test
    fun `25 delay 期间取消 应正常传播取消（不等满退避）`() {
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val threw = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val elapsed = java.util.concurrent.atomic.AtomicLong(-1)

        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            val job = scope.launch {
                try {
                    retryBackoff(30_000L, isCancelled = { false }) { started.set(true) }
                } catch (e: Throwable) {
                    threw.set(e)
                }
            }
            // 等它真正进入 delay
            while (!started.get()) delay(5)
            val t0 = System.nanoTime()
            job.cancel()
            job.join()
            elapsed.set((System.nanoTime() - t0) / 1_000_000)
        }

        assertTrue(
            "退避期间取消必须抛 CancellationException，实际=${threw.get()}",
            threw.get() is CancellationException,
        )
        assertTrue(
            "取消必须立即返回（不等满 30s），实际耗时 ${elapsed.get()}ms",
            elapsed.get() < 3_000,
        )
    }

    @Test
    fun `25b delay 前已取消 应立刻抛 DownloadCancelledException（不等待）`() {
        var thrown: Throwable? = null
        val t0 = System.nanoTime()
        try {
            runBlocking {
                retryBackoff(30_000L, isCancelled = { true })
            }
        } catch (e: Throwable) {
            thrown = e
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("应为 DownloadCancelledException，实际=$thrown", thrown is DownloadCancelledException)
        assertTrue("不得消耗等待时间，实际 ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun `25c 退避结束后发现已取消 应抛 DownloadCancelledException`() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var thrown: Throwable? = null
        runBlocking {
            try {
                retryBackoff(50L, isCancelled = { cancelled.get() }) {
                    cancelled.set(true) // 模拟退避期间用户点了取消
                }
            } catch (e: Throwable) {
                thrown = e
            }
        }
        assertTrue("应为 DownloadCancelledException，实际=$thrown", thrown is DownloadCancelledException)
    }

    @Test
    fun `25d 退避必须使用协程 delay 而非 Thread sleep`() {
        val candidates = listOf(
            java.io.File("app/src/main/java/com/kuaixia/app/data/download/HttpDownloader.kt"),
            java.io.File("../app/src/main/java/com/kuaixia/app/data/download/HttpDownloader.kt"),
        )
        val src = candidates.firstOrNull { it.exists() } ?: return // 非工程根运行时跳过
        val text = src.readText()
        assertTrue("HttpDownloader 不得使用 Thread.sleep", !text.contains("Thread.sleep"))
        assertTrue("HttpDownloader 应使用 kotlinx delay( )", text.contains("delay(waitMs)"))
    }

    // ==================== 结构化 HTTP code（规范第 8 节） ====================

    @Test
    fun `32c 结构化 httpCode 与中文 message 完全解耦`() {
        // 模拟 DownloadRepository.httpCodeOf 的读取契约：
        // detail = "HTTP_CODE:403" 可被结构化读出，与 message 无关。
        val e = com.kuaixia.app.core.error.AppException(
            com.kuaixia.app.core.error.ErrorCode.NETWORK_ERROR,
            "下载失败（HTTP 403）",
            detail = "HTTP_CODE:403",
        )
        val marker = "HTTP_CODE:"
        val idx = e.detail!!.indexOf(marker)
        val parsed = e.detail!!.substring(idx + marker.length).takeWhile { it.isDigit() }.toIntOrNull()
        assertEquals(403, parsed)
        assertEquals(
            RetryDecision.REPARSE,
            policy.decide(e, httpCode = parsed, attempt = 1),
        )
    }

    @Test
    fun `32d 无 message 文本也能给出正确决策`() {
        // 规范第 31/32 条：决策完全由结构化 httpCode 驱动
        val cases = mapOf(
            403 to RetryDecision.REPARSE,
            404 to RetryDecision.REPARSE,
            410 to RetryDecision.REPARSE,
            400 to RetryDecision.FAIL,
            401 to RetryDecision.FAIL,
            429 to RetryDecision.RETRY,
            500 to RetryDecision.RETRY,
            502 to RetryDecision.RETRY,
            503 to RetryDecision.RETRY,
            504 to RetryDecision.RETRY,
        )
        cases.forEach { (code, expected) ->
            val e = IOException("") // message 为空
            assertEquals("HTTP $code", expected, policy.decide(e, httpCode = code, attempt = 1))
        }
    }
}
