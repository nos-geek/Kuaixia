package com.kuaixia.app.data.download

/**
 * 下载任务状态。
 *
 * Phase 3 第一阶段只有 HTTP 直链下载，状态机：
 * QUEUED → DOWNLOADING → (COMPLETED | FAILED | CANCELLED)
 * DOWNLOADING ⇄ PAUSED
 */
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}
