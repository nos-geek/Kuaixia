package com.kuaixia.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-001 取消解析 / P2-007 session 防护 / teardown 幂等 —— 纯逻辑单测。
 *
 * 覆盖用户要求的 6 项中的 1~5：
 * 1. cancel 使 session 失效
 * 2. 旧 session callback 被丢弃
 * 3. 新 session callback 正常
 * 4. teardown 幂等
 * 5. cancel 后不能产生 parse success
 * （第 6 项 timer/interval teardown 见 `WebViewProbeScriptTest`）
 */
class ParserSessionTest {

    // ============================ 1. cancel 使 session 失效 ============================

    @Test
    fun `1a begin 返回单调递增的世代号`() {
        val guard = ParserSessionGuard()
        val g1 = guard.begin()
        val g2 = guard.begin()
        val g3 = guard.begin()
        assertTrue("世代号必须递增", g1 < g2 && g2 < g3)
    }

    @Test
    fun `1b invalidate 后旧 token 立即失效`() {
        val guard = ParserSessionGuard()
        val token = guard.begin()
        assertTrue("刚 begin 的 token 必须有效", guard.isCurrent(token))
        guard.invalidate()
        assertFalse("取消后旧 token 必须失效", guard.isCurrent(token))
        assertFalse("取消后旧 token 不得被接受为结果", guard.acceptResult(token))
    }

    @Test
    fun `1c invalidate 幂等且世代不复用`() {
        val guard = ParserSessionGuard()
        val token = guard.begin()
        guard.invalidate()
        val afterFirst = guard.current()
        guard.invalidate()
        guard.invalidate()
        assertTrue("重复 invalidate 不再改变世代语义（仍持续前进）", guard.current() >= afterFirst)
        assertFalse("旧 token 永远不能复活", guard.isCurrent(token))

        val next = guard.begin()
        assertNotEquals("新世代号不得复用旧的", token, next)
        assertTrue("新世代有效", guard.isCurrent(next))
    }

    // ===================== 2. 旧 session callback 被丢弃（JS 桥 / 页面回调） =====================

    @Test
    fun `2a 会话失效后 allowCallback 一律返回 false 并计数`() {
        val session = ParseSession(generation = 1L)
        assertTrue("有效会话允许回调", session.allowCallback())
        assertEquals("有效会话不应有丢弃计数", 0, session.droppedCallbacks)

        session.invalidate()
        assertFalse("取消后回调必须被丢弃", session.allowCallback())
        assertFalse("取消后回调必须被丢弃（第二次）", session.allowCallback())
        assertFalse("取消后回调必须被丢弃（第三次）", session.allowCallback())
        assertEquals("丢弃次数必须被如实计数", 3, session.droppedCallbacks)
    }

    @Test
    fun `2b 旧 session 的晚到回调全部丢弃（模拟 JS 桥 onMedia_onImageGroup_onImgStates_onDomProbe）`() {
        val session = ParseSession(generation = 7L)
        session.invalidate()
        // 5 个 JS 桥入口（onMedia/onTitle/onImage/onImageGroup/onImgStates/onDomProbe 的最小共同语义）
        repeat(6) { assertFalse(session.allowCallback()) }
        assertEquals(6, session.droppedCallbacks)
        assertTrue("旧会话必须停止推进状态（isValid=false）", !session.isValid)
    }

    @Test
    fun `2c 会话失效后 isValid 为 false（网络回调 shouldInterceptRequest 的判据）`() {
        val session = ParseSession()
        assertTrue(session.isValid)
        session.invalidate()
        assertFalse(session.isValid)
    }

    // ============================ 3. 新 session callback 正常 ============================

    @Test
    fun `3a 新一轮解析的新 token 被接受`() {
        val guard = ParserSessionGuard()
        val old = guard.begin()
        guard.invalidate()                 // 用户取消
        val fresh = guard.begin()          // 用户重新解析
        assertFalse("旧 token 仍应失效", guard.acceptResult(old))
        assertTrue("新 token 必须被接受", guard.acceptResult(fresh))
    }

    @Test
    fun `3b 新会话的 allowCallback 正常放行`() {
        val oldSession = ParseSession(generation = 1L)
        oldSession.invalidate()
        val newSession = ParseSession(generation = 2L)
        assertFalse("旧会话丢弃", oldSession.allowCallback())
        assertTrue("新会话放行", newSession.allowCallback())
        assertEquals("新会话无丢弃", 0, newSession.droppedCallbacks)
    }

    @Test
    fun `3c 会话之间互不影响（两个 ParseSession 独立）`() {
        val s1 = ParseSession(generation = 1L)
        val s2 = ParseSession(generation = 2L)
        s1.invalidate()
        assertFalse(s1.isValid)
        assertTrue("s1 取消不得影响 s2", s2.isValid)
        assertTrue(s2.allowCallback())
    }

    // ============================ 4. teardown 幂等 ============================

    @Test
    fun `4a OnceGate 只执行一次`() {
        val gate = OnceGate()
        val runs = AtomicInteger(0)
        assertTrue("首次执行返回 true", gate.tryRun { runs.incrementAndGet() })
        assertFalse("第二次返回 false", gate.tryRun { runs.incrementAndGet() })
        assertFalse("第三次返回 false", gate.tryRun { runs.incrementAndGet() })
        assertEquals("block 恰好执行一次", 1, runs.get())
        assertTrue(gate.hasRun)
    }

    @Test
    fun `4b ParseSession teardownOnce 幂等（cancel + finally + 超时 多路径）`() {
        val session = ParseSession(generation = 3L)
        val teardowns = AtomicInteger(0)
        val block: () -> Unit = { teardowns.incrementAndGet() }

        assertTrue("cancel 路径首次收尾", session.teardownOnce(block))
        assertFalse("finally 路径重复收尾被抑制", session.teardownOnce(block))
        assertFalse("超时路径重复收尾被抑制", session.teardownOnce(block))
        assertFalse("Activity destroy 路径重复收尾被抑制", session.teardownOnce(block))
        assertEquals("实际收尾恰好一次", 1, teardowns.get())
        assertTrue(session.isTornDown)
    }

    @Test
    fun `4c 并发触发收尾仍然只执行一次`() {
        val session = ParseSession(generation = 9L)
        val threads = 16
        val barrier = CyclicBarrier(threads)
        val latch = CountDownLatch(threads)
        val teardowns = AtomicInteger(0)
        repeat(threads) {
            Thread {
                barrier.await()
                session.teardownOnce { teardowns.incrementAndGet() }
                latch.countDown()
            }.start()
        }
        assertTrue("并发收尾应在 5s 内结束", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("并发下仍恰好收尾一次", 1, teardowns.get())
    }

    // ================ 5. cancel 后不能产生 parse success（§8 竞态闸门） ================

    @Test
    fun `5a 场景复现 取消与完成同时发生 结果必须被丢弃`() {
        // 模拟 HomeViewModel.parseUrl 的真实时序：
        //   parse() 返回后到写 uiState 之间没有挂起点 ⇒ onSuccess 仍会被调用
        val guard = ParserSessionGuard()
        val token = guard.begin()                  // 用户点解析
        guard.invalidate()                         // 用户点取消（cancel() 已写 isLoading=false）
        // 解析此时刚好完成 → onSuccess 执行 → 闸门必须拒绝
        assertFalse("取消后不得把 result 写回 UI", guard.acceptResult(token))
    }

    @Test
    fun `5b 未取消时结果正常接受（闸门不能误伤正常路径）`() {
        val guard = ParserSessionGuard()
        val token = guard.begin()
        assertTrue("正常完成的解析结果必须被接受", guard.acceptResult(token))
    }

    @Test
    fun `5c 上一轮被取消 不影响下一轮成功`() {
        val guard = ParserSessionGuard()
        val t1 = guard.begin()
        guard.invalidate()
        assertFalse(guard.acceptResult(t1))

        val t2 = guard.begin()
        assertTrue(guard.acceptResult(t2))
    }

    @Test
    fun `5d 连续多轮解析只有最新一轮的结果被接受`() {
        val guard = ParserSessionGuard()
        val t1 = guard.begin()
        val t2 = guard.begin()      // 抢占：parseUrl 开头会 cancel 上一轮
        val t3 = guard.begin()
        assertFalse(guard.acceptResult(t1))
        assertFalse(guard.acceptResult(t2))
        assertTrue(guard.acceptResult(t3))
    }

    @Test
    fun `5e 取消后失败态也不得写回`() {
        val guard = ParserSessionGuard()
        val token = guard.begin()
        guard.invalidate()
        // onFailure 与 onSuccess 共用同一个闸门
        assertFalse(guard.acceptResult(token))
    }
}
