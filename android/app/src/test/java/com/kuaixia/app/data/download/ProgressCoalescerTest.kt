package com.kuaixia.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-001 最小修复的单元测试。
 *
 * 覆盖进度合并与「关键状态不丢」两条边界：
 * - 高频 progress 合并、中间帧跳过；
 * - 终态跃迁（COMPLETED/FAILED/CANCELLED）绝不因合并而丢失。
 *
 * 注：DownloadRepository 依赖 Android（Room/Context），无法在纯 JVM 下实例化；
 * 因此把可测的纯逻辑抽成 [ProgressCoalescer]，配合纯 [DownloadTask] 的 withProgress/withState 验证组合语义。
 */
class ProgressCoalescerTest {

    private fun task(id: String, state: DownloadState = DownloadState.QUEUED) = DownloadTask(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.webp",
        saveDir = "/tmp",
        state = state,
    )

    private fun p(bytes: Long, total: Long = 1000) = DownloadProgress(
        downloadedBytes = bytes,
        totalBytes = total,
        speed = 1,
    )

    /** 模拟 System.currentTimeMillis() 的真实量级（epoch 毫秒），避免 lastFlushAt 初始 0 的干扰。 */
    private val base = 1_700_000_000_000L

    @Test
    fun coalescesHighFrequencyProgressWithinWindow() {
        val c = ProgressCoalescer(flushIntervalMs = 300)
        // 同一 id 在窗口内连续报 100 次进度（间隔 1ms，均 < 300ms），只应 flush 一次（首次）
        var flushes = 0
        for (i in 0 until 100) {
            if (c.offer("t1", p(i.toLong()), nowMs = base + i)) flushes++
        }
        assertEquals(1, flushes)
        val drained = c.drain()
        assertEquals(1, drained.size)
    }

    @Test
    fun skipsIntermediateProgress() {
        val c = ProgressCoalescer(flushIntervalMs = 300)
        c.offer("t1", p(10), nowMs = base)       // 触发 flush 标记
        c.offer("t1", p(50), nowMs = base + 100) // 合并（跳过中间值 50）
        c.offer("t1", p(90), nowMs = base + 200) // 合并（最终值 90）
        val drained = c.drain()
        // 中间 50 被跳过，最终保留最新 90
        assertEquals(90L, drained["t1"]!!.downloadedBytes)
    }

    @Test
    fun flushesAtIntervalBoundaries() {
        val c = ProgressCoalescer(flushIntervalMs = 300)
        assertTrue(c.offer("t1", p(10), nowMs = base))
        assertFalse(c.offer("t1", p(50), nowMs = base + 299))
        assertTrue(c.offer("t1", p(90), nowMs = base + 300))
        assertFalse(c.offer("t1", p(99), nowMs = base + 599))
    }

    @Test
    fun takeRemovesPendingForTerminalTransition() {
        val c = ProgressCoalescer(flushIntervalMs = 300)
        c.offer("t1", p(70), nowMs = 0)
        c.offer("t1", p(95), nowMs = 100)
        // 终态跃迁取走最新待写进度
        assertEquals(95L, c.take("t1")!!.downloadedBytes)
        // 取走后 pending 清空，drain 为空，再取为 null
        assertTrue(c.drain().isEmpty())
        assertNull(c.take("t1"))
    }

    @Test
    fun completedNotDropped_carriesLatestProgress() {
        val c = ProgressCoalescer(flushIntervalMs = 300)
        val base = task("t1", DownloadState.DOWNLOADING)
        c.offer("t1", p(60), nowMs = 0)
        c.offer("t1", p(100), nowMs = 100)
        // 模拟 setStateInternal：先 take 待写进度，再 withState(COMPLETED)
        val pending = c.take("t1")
        val final = (pending?.let { base.withProgress(it) } ?: base)
            .withState(DownloadState.COMPLETED)
        assertEquals(DownloadState.COMPLETED, final.state)
        assertEquals(100L, final.progress.downloadedBytes)
    }

    @Test
    fun failedAndCancelledNotDropped() {
        val base = task("t1", DownloadState.DOWNLOADING)
        val failed = base.withState(DownloadState.FAILED, errorMessage = "err")
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals("err", failed.errorMessage)

        val cancelled = base.withState(DownloadState.CANCELLED)
        assertEquals(DownloadState.CANCELLED, cancelled.state)
        // 状态跃迁保留 id，不丢任务身份
        assertEquals("t1", cancelled.id)
        assertEquals("t1", failed.id)
    }

    @Test
    fun finalListStateCorrect_acrossTwoTasks() {
        val c = ProgressCoalescer(flushIntervalMs = 300)
        val t1 = task("t1", DownloadState.DOWNLOADING)
        val t2 = task("t2", DownloadState.DOWNLOADING)
        var list = listOf(t1, t2)

        // 高频进度合并后 flush（模拟 flushProgress 的整表应用）
        c.offer("t1", p(50), nowMs = 0)
        c.offer("t1", p(88), nowMs = 100)
        c.offer("t2", p(100), nowMs = 0)
        val snap = c.drain()
        list = list.map { t -> snap[t.id]?.let { t.withProgress(it) } ?: t }

        assertEquals(88L, list.first { it.id == "t1" }.progress.downloadedBytes)
        assertEquals(100L, list.first { it.id == "t2" }.progress.downloadedBytes)

        // 终态跃迁（模拟 setStateInternal），不经过合并器
        list = list.map { t -> if (t.id == "t1") t.withState(DownloadState.COMPLETED) else t }
        assertEquals(DownloadState.COMPLETED, list.first { it.id == "t1" }.state)
        assertNotNull(list.first { it.id == "t1" }.completedAt)
    }
}
