package com.kuaixia.app.data.download

/**
 * 进度合并器（BUG-001 最小修复）。
 *
 * 背景：`HttpDownloader` 每个任务每 ~500ms 报一次进度，50 并发 ≈ 100 次/秒；
 * 若每次进度都触发 `_tasks.update { list.map {...} }` 整表重建 + StateFlow 发射，
 * 历史任务量大时（O(n) × 高频）会压垮主线程消费者（DownloadService / Compose）。
 *
 * 职责：把高频「进度帧」合并成低频的整表传播。**只合并进度帧**——
 * 终态跃迁（QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED）不经由本类，
 * 仍走 `setStateInternal` 的整表更新，保证绝不丢关键状态。
 *
 * 线程安全：下载协程并发调用（synchronized 保护 pending 表）。
 */
class ProgressCoalescer(private val flushIntervalMs: Long) {

    private val lock = Any()
    private val pending = LinkedHashMap<String, DownloadProgress>()
    private var lastFlushAt = 0L

    /** 记录一次进度；返回 true 表示已超过合并窗口，应执行 flush。 */
    fun offer(id: String, progress: DownloadProgress, nowMs: Long): Boolean {
        synchronized(lock) {
            pending[id] = progress
            if (nowMs - lastFlushAt >= flushIntervalMs) {
                lastFlushAt = nowMs
                return true
            }
            return false
        }
    }

    /** 取出并清空全部待写进度（用于 flush 到整表）。 */
    fun drain(): Map<String, DownloadProgress> {
        synchronized(lock) {
            if (pending.isEmpty()) return emptyMap()
            val m = HashMap(pending)
            pending.clear()
            return m
        }
    }

    /** 终态跃迁前取走该 id 的最新待写进度（保证终态携带正确进度），并从 pending 移除。 */
    fun take(id: String): DownloadProgress? {
        synchronized(lock) { return pending.remove(id) }
    }
}
