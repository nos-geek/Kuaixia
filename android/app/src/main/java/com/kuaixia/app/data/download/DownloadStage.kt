package com.kuaixia.app.data.download

/**
 * 下载细分阶段（用于 UI 展示进度文案，区别于最终状态 [DownloadState]）。
 *
 * DASH 任务依次经历：DOWNLOADING_VIDEO → DOWNLOADING_AUDIO → MERGING → DONE。
 * 单文件直链任务：DOWNLOADING_VIDEO → DONE。
 */
enum class DownloadStage {
    QUEUED,
    DOWNLOADING_VIDEO,
    DOWNLOADING_AUDIO,
    MERGING,
    DONE,
}
