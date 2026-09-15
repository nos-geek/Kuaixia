package com.kuaixia.app.data.parser

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 解析会话世代守卫（**纯逻辑，零 Android 依赖**，可 JVM 单测）。
 *
 * ## 为什么需要它（P2-001 根因之一）
 * `HomeViewModel.parseUrl()` 里 `parserManager.parse(url).onSuccess { _uiState.update { result = … } }`
 * 之间**没有挂起点**（`Result.onSuccess` 是 inline 非挂起），因此存在竞态：
 * ```
 * 用户点取消 → cancel() 已把 isLoading=false 写回
 * 解析刚好完成 → onSuccess 又把 result 写回来 ⇒ UI 又显示解析结果
 * ```
 * 世代守卫把「这次解析是否仍属于当前会话」变成可判定的事实：
 * `begin()` 拿号 → 结果落地前用 `acceptResult(token)` 校验 → 取消/新解析后旧号一律不接受。
 *
 * 语义：
 * - `begin()`：开启新会话，返回新世代号（单调递增，不复用）；
 * - `invalidate()`：作废当前世代（取消 / 新解析抢占 / VM 清理）→ 之前所有 token 立即失效；
 * - `isCurrent(token)`：token 是否仍是当前世代。
 *
 * 设计约束：不缓存「上一个 token」、不提供 `restore()`——只有"当前"才有意义，
 * 避免出现"旧 token 被重新变有效"的路径。
 */
class ParserSessionGuard {

    private val generation = AtomicLong(0L)

    /** 开启新会话并返回其世代号（旧 token 从此失效）。 */
    fun begin(): Long = generation.incrementAndGet()

    /** 作废当前世代（幂等；返回作废后的新世代号）。 */
    fun invalidate(): Long = generation.incrementAndGet()

    /** token 是否仍是当前世代。 */
    fun isCurrent(token: Long): Boolean = generation.get() == token

    /**
     * 结果落地闸门：仅当 token 仍是当前世代才允许把结果写进 UI。
     * 取消 / 新一轮解析后调用必然返回 false（这是 P2-001「取消后不得出现 parse success」的判据）。
     */
    fun acceptResult(token: Long): Boolean = isCurrent(token)

    /** 当前世代号（仅诊断/日志用）。 */
    fun current(): Long = generation.get()
}

/**
 * 幂等一次性执行闸（**纯逻辑，零 Android 依赖**，可 JVM 单测）。
 *
 * 用于 teardown：`cancel()` / `finally` / 超时 / Activity destroy 多条路径都可能触发收尾，
 * 必须保证「第一个到场的执行，其余全部 no-op」，且**恰好执行一次**。
 */
class OnceGate {

    private val done = AtomicBoolean(false)

    /** 首次调用执行 [block] 并返回 true；后续调用不执行并返回 false。 */
    fun tryRun(block: () -> Unit): Boolean {
        if (!done.compareAndSet(false, true)) return false
        block()
        return true
    }

    val hasRun: Boolean get() = done.get()
}

/**
 * 单次解析会话（**纯逻辑，零 Android 依赖**，可 JVM 单测）。
 *
 * 把「这个 WebView / 这份 collector / 这个 JS 桥是否还属于有效解析」收敛成一个对象，
 * 供 [WebViewParser] 的页面回调与 JS 桥在入口处判定，替代散落的 ad-hoc 布尔量。
 *
 * 职责：
 * - [invalidate]：会话作废（取消 / 页面被抢占）；
 * - [allowCallback]：页面回调 / JS 桥闸门 —— 会话失效后一律丢弃并计数；
 * - [teardownOnce]：收尾幂等（多个触发路径只真正执行一次）。
 */
class ParseSession(val generation: Long = nextGeneration()) {

    private val invalidated = AtomicBoolean(false)
    private val teardownGate = OnceGate()
    private val callbackDrops = AtomicInteger(0)

    /** 会话是否仍然有效（未被取消）。 */
    val isValid: Boolean get() = !invalidated.get()

    /** 作废会话（幂等）。 */
    fun invalidate() {
        invalidated.set(true)
    }

    /**
     * 回调闸门。返回 true = 允许处理；false = 会话已失效，本次回调被丢弃。
     * 丢弃次数计入 [droppedCallbacks]（供日志与单测断言「旧 session callback 被丢弃」）。
     */
    fun allowCallback(): Boolean {
        if (invalidated.get()) {
            callbackDrops.incrementAndGet()
            return false
        }
        return true
    }

    /** 已被丢弃的回调次数。 */
    val droppedCallbacks: Int get() = callbackDrops.get()

    /** 收尾幂等：首次执行 [block] 返回 true，重复调用返回 false 且不执行。 */
    fun teardownOnce(block: () -> Unit): Boolean = teardownGate.tryRun(block)

    /** 是否已完成收尾。 */
    val isTornDown: Boolean get() = teardownGate.hasRun

    private companion object {
        /** 会话序号（单调递增，仅用于日志里把同一次解析的 WebView / 取消 / 收尾串起来）。 */
        private val counter = AtomicLong(0L)

        private fun nextGeneration(): Long = counter.incrementAndGet()
    }
}
