package com.kuaixia.app.core.model

/** 下载状态。Phase 1 暂未使用，为后续下载功能预留。 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    MERGING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
