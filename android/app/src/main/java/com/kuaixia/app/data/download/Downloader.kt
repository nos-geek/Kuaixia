package com.kuaixia.app.data.download

import java.io.File

/**
 * 下载器统一接口。
 *
 * 未来可扩展实现：HttpDownloader（HTTP 直链）、M3U8Downloader、AriaDownloader、ServerDownloader。
 * 上层（DownloadRepository / UI）只依赖本接口，不绑定具体下载实现。
 */
interface Downloader {

    /**
     * 下载 [task]，边下载边通过 [progress] 回调进度。
     *
     * @param offset   续传偏移字节数（首次下载传 0）。下载器据此加 Range 头。
     * @param progress 进度回调（在 IO 线程调用）。
     * @return 成功时返回已落盘的 [File]；失败返回带中文提示的 Result。
     *         主动取消时抛 [DownloadCancelledException]（区别于网络错误）。
     */
    suspend fun download(
        task: DownloadTask,
        offset: Long = 0,
        progress: (DownloadProgress) -> Unit,
        /** 响应头回调（图片下载等按真实 Content-Type 定稿用；默认无）。 */
        onContentType: (String?) -> Unit = {},
    ): Result<File>

    /** 取消指定任务的进行中请求（中断底层网络调用）。 */
    fun cancel(taskId: String)
}

/** 表示下载被主动取消（用户点了暂停/取消），非网络错误。 */
class DownloadCancelledException : Exception("download cancelled")
