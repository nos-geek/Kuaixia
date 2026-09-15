package com.kuaixia.app.data.download

/**
 * 下载进度快照。
 *
 * @param downloadedBytes 已下载字节数
 * @param totalBytes      总字节数（未知时为 -1 或 0，此时 [percent] 返回 0）
 * @param speed           即时速度（字节/秒）
 * @param segmentDone     已完成分片数（M3U8；非分片任务为 -1）
 * @param segmentTotal    总分段数（M3U8；非分片任务为 -1）
 */
data class DownloadProgress(
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speed: Long = 0,
    val segmentDone: Int = -1,
    val segmentTotal: Int = -1,
) {
    val percent: Int
        get() {
            // M3U8：优先按已完成分片/总分段（字节总量通常未知）
            if (segmentTotal > 0) {
                val s = (segmentDone.coerceIn(0, segmentTotal) * 100) / segmentTotal
                return s.coerceIn(0, 100)
            }
            if (totalBytes <= 0) return 0
            val p = (downloadedBytes * 100) / totalBytes
            return p.coerceIn(0, 100).toInt()
        }
}
