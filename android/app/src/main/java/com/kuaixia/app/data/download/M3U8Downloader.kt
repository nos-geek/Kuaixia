package com.kuaixia.app.data.download

import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.data.download.m3u8.M3U8PlaylistParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "M3U8"

/**
 * HLS/M3U8 VOD 下载器（Phase 4）。
 *
 * 流程：
 * 1. 拉取 playlist（OkHttp，跟随 302/301，用**最终 URL** 作相对地址基准）；
 * 2. Master → 选清晰度（匹配任务 quality 的 RESOLUTION，否则最高 BANDWIDTH）→ 取 Media；
 *    直接 Media → 用之；
 * 3. 解析分片（保序）→ 检测 AES-128/DRM/EXT-X-MAP：
 *    - AES-128/SAMPLE-AES/DRM → 明确失败（不静默下载错误文件）；
 *    - EXT-X-MAP → 先下载 init，合并时 init 在前；
 * 4. Semaphore 限并发（默认 6）下载分片到 `cacheDir/downloads/<taskId>/segments/`：
 *    - 写入 `.part` 成功后原子 rename → **已完成分片保留**（暂停/重启后跳过 = 断点续传）；
 *    - 单个分片瞬时网络错误重试 ≤3 次；4xx 不重试；
 * 5. FFmpeg concat demuxer `-c copy` 合成 mp4（复用现有 FFmpegKit，不引入第二套）；
 * 6. 返回最终文件，由 Repository 写入 MediaStore。
 *
 * 下载请求头统一使用 [DownloadTask.httpHeaders]（Map 透传，无平台特判），日志脱敏。
 */
class M3U8Downloader : Downloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val semaphore = Semaphore(CONCURRENCY)

    // taskId -> 进行中 call / 取消标记（语义同 HttpDownloader）
    private val calls = ConcurrentHashMap<String, MutableSet<Call>>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override suspend fun download(
        task: DownloadTask,
        offset: Long,
        progress: (DownloadProgress) -> Unit,
        @Suppress("UNUSED_PARAMETER") onContentType: (String?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        // 新一轮下载不继承上一次暂停/取消的残留标志（任务级单 job，Repository 已保证互斥）
        cancelled.remove(task.id)
        runCatchingWithLog(task) { execute(task, progress) }
    }

    override fun cancel(taskId: String) {
        cancelled.add(taskId)
        calls[taskId]?.forEach { runCatching { it.cancel() } }
    }

    // ======================= 主流程 =======================

    private suspend fun execute(task: DownloadTask, progress: (DownloadProgress) -> Unit): File {
        val baseDir = File(task.saveDir, task.id).apply { mkdirs() }
        val segDir = File(baseDir, "segments").apply { mkdirs() }

        // 1. 拉 master/media playlist
        AppLogRepository.i(LogTags.M3U8, "开始解析 playlist url=${LogSanitizer.sanitizeUrl(task.url)}")
        var (text, finalUrl) = fetchText(task, task.url)
        var parsed = M3U8PlaylistParser.parse(text, finalUrl)
        val selectedVariant: M3U8PlaylistParser.Variant? = (parsed as? M3U8PlaylistParser.Playlist.Master)?.let { master ->
            AppLogRepository.i(
                LogTags.M3U8,
                "检测到 Master playlist variants=${master.variants.size} 选择清晰度=${selectLabel(task, master)}",
            )
            pickVariant(task, master).also {
                AppLogRepository.i(LogTags.M3U8, "选定变体 uri=${LogSanitizer.sanitizeUrl(it.uri)}")
            }
        }
        if (selectedVariant != null) {
            val (mediaText, mediaUrl) = fetchText(task, selectedVariant.uri)
            parsed = M3U8PlaylistParser.parse(mediaText, mediaUrl)
        }
        val media = parsed as M3U8PlaylistParser.Playlist.Media

        val segments = media.segments
        AppLogRepository.i(
            LogTags.M3U8,
            "Media playlist 分片=${segments.size} init=${media.initUri != null} " +
                "url=${LogSanitizer.sanitizeUrl(finalUrl)}",
        )

        // 2. init（fMP4，EXT-X-MAP）
        val initFile: File? = media.initUri?.let { uri ->
            val f = File(segDir, "init")
            downloadSegmentFile(task, f, uri, isInit = true)
            AppLogRepository.i(LogTags.M3U8, "EXT-X-MAP init 下载完成 uri=${LogSanitizer.sanitizeUrl(uri)}")
            f
        }

        // 3. 并发下载分片（保序索引）
        AppLogRepository.i(LogTags.M3U8, "开始下载分片 total=${segments.size} concurrency=$CONCURRENCY")

        // 先统计已有完整分片（暂停/重启恢复：断点续传），字节与计数用于进度
        var initialBytes = 0L
        var initialDone = 0
        segments.forEach { seg ->
            val f = segmentFile(segDir, seg.index)
            if (segDone(f)) {
                initialDone++
                initialBytes += f.length()
            }
        }
        if (initialDone > 0) {
            AppLogRepository.i(
                LogTags.M3U8,
                "续传：已有 $initialDone/${segments.size} 个完整分片",
            )
        }
        val downloaded = AtomicLong(initialBytes)
        val doneCounter = AtomicInteger(initialDone)
        val lastReportAt = AtomicLong(System.currentTimeMillis())
        val lastReportBytes = AtomicLong(initialBytes)

        val failRef = AtomicReference<Throwable?>()
        coroutineScope {
            segments.map { seg ->
                async {
                    val finalSeg = segmentFile(segDir, seg.index)
                    if (segDone(finalSeg)) return@async  // 已统计过，无需下载
                    if (failRef.get() != null || cancelled.contains(task.id)) return@async
                    semaphore.withPermit {
                        try {
                            downloadWithRetry(task, seg, finalSeg)
                            downloaded.addAndGet(finalSeg.length())
                            doneCounter.incrementAndGet()
                            reportProgress(
                                progress, downloaded, doneCounter, lastReportAt, lastReportBytes,
                                segments.size,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: DownloadCancelledException) {
                            throw e
                        } catch (e: Throwable) {
                            if (failRef.compareAndSet(null, e)) {
                                abortInFlight(task.id)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        if (cancelled.contains(task.id)) {
            cancelled.remove(task.id)
            cleanupParts(segDir)
            AppLogger.i("m3u8 cancelled task=${task.id}", TAG)
            throw DownloadCancelledException()
        }
        failRef.get()?.let { throw it }

        // 4. 完整性检查
        val done = segDoneCount(segDir, segments.size)
        if (done != segments.size) {
            val e = AppException(ErrorCode.NETWORK_ERROR, "分片下载不完整（$done/${segments.size}）")
            AppLogger.w("m3u8 incomplete task=${task.id} ${e.message}", TAG)
            throw e
        }

        // 5. FFmpeg concat 合并
        AppLogRepository.i(LogTags.M3U8, "分片全部完成，开始合并 segments=$done")
        val outputFile = File(baseDir, "merged.mp4")
        val mergeResult = FFmpegMerger.concatSegments(
            segmentFiles = segments.map { segmentFile(segDir, it.index) },
            initFile = initFile,
            outputFile = outputFile,
            logTag = TAG,
        )
        return mergeResult.getOrThrow()
    }

    // ======================= 分片下载 =======================

    /** 单个分片下载（瞬时网络错误重试 ≤3 次；HTTP 4xx 不重试）。 */
    private suspend fun downloadWithRetry(
        task: DownloadTask,
        seg: M3U8PlaylistParser.Segment,
        target: File,
    ) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                downloadSegmentFile(task, target, seg.uri, isInit = false)
                return
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= MAX_SEGMENT_RETRIES) {
                    AppLogRepository.w(
                        LogTags.M3U8,
                        "分片下载重试耗尽 seg=${seg.index} err=${e.message}",
                    )
                    throw e
                }
                AppLogRepository.w(
                    LogTags.M3U8,
                    "分片下载失败，重试 ${attempt}/$MAX_SEGMENT_RETRIES seg=${seg.index} err=${e.message}",
                )
            }
        }
    }

    /** 下载单个文件（init 或分片）。写入 .part 后原子 rename。 */
    private suspend fun downloadSegmentFile(
        task: DownloadTask,
        target: File,
        url: String,
        isInit: Boolean,
    ) {
        if (target.exists() && target.length() > 0) return
        val part = File(target.parentFile, target.name + ".part")
        part.delete()

        val requestBuilder = Request.Builder().url(url)
        task.httpHeaders.forEach { (name, value) ->
            HttpHeaders.addSafely(requestBuilder, name, value) { w ->
                AppLogRepository.w(LogTags.HTTP, "[M3U8] $w")
            }
        }
        val request = requestBuilder.build()
        val call = client.newCall(request)
        val set = calls.computeIfAbsent(task.id) { ConcurrentHashMap.newKeySet() }
        set.add(call)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException(if (isInit) "init 下载失败（HTTP ${response.code}）" else "下载失败（HTTP ${response.code}）")
            }
            response.body?.let { body ->
                val contentLength = body.contentLength()
                if (contentLength == 0L) {
                    body.close()
                    throw IOException(if (isInit) "init 响应为空" else "分片响应为空")
                }
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val len = input.read(buffer)
                            if (len == -1) break
                            output.write(buffer, 0, len)
                            total += len
                        }
                        if (total == 0L) {
                            throw IOException("写入 0 字节")
                        }
                    }
                }
            } ?: throw IOException("响应体为空")
            if (!part.renameTo(target)) {
                // Windows/Android renameTo 失败时回退 copy
                if (!part.copyTo(target, overwrite = true).exists()) {
                    throw IOException("分片落盘失败")
                }
                part.delete()
            }
        } catch (e: IOException) {
            if (cancelled.contains(task.id)) {
                throw DownloadCancelledException()
            }
            part.delete()
            throw e
        } finally {
            set.remove(call)
            if (set.isEmpty()) calls.remove(task.id)
        }
    }

    // ======================= playlist 拉取 =======================

    /** 拉取 playlist 文本，返回 (文本, 最终 URL) —— 重定向后基准 URL。 */
    private suspend fun fetchText(task: DownloadTask, url: String): Pair<String, String> {
        val requestBuilder = Request.Builder().url(url)
        task.httpHeaders.forEach { (name, value) ->
            HttpHeaders.addSafely(requestBuilder, name, value) { w ->
                AppLogRepository.w(LogTags.HTTP, "[M3U8] $w")
            }
        }
        val request = requestBuilder.build()
        val call = client.newCall(request)
        val set = calls.computeIfAbsent(task.id) { ConcurrentHashMap.newKeySet() }
        set.add(call)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("playlist 下载失败（HTTP ${response.code}）")
            }
            val finalUrl = response.request.url.toString()
            val text = response.body?.string() ?: throw IOException("playlist 响应为空")
            if (text.length > MAX_PLAYLIST_CHARS) {
                throw IOException("playlist 异常巨大（${text.length} 字符）")
            }
            return text to finalUrl
        } finally {
            set.remove(call)
            if (set.isEmpty()) calls.remove(task.id)
        }
    }

    // ======================= Master 选择 =======================

    private fun pickVariant(
        task: DownloadTask,
        master: M3U8PlaylistParser.Playlist.Master,
    ): M3U8PlaylistParser.Variant {
        val wantHeight = task.quality?.let { q ->
            Regex("(\\d{2,4})[pP]").find(q)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (wantHeight != null) {
            master.variants.firstOrNull { it.height == wantHeight }?.let { return it }
        }
        return master.variants.maxByOrNull { it.bandwidth ?: 0 }
            ?: master.variants.first()
    }

    private fun selectLabel(task: DownloadTask, master: M3U8PlaylistParser.Playlist.Master): String {
        val v = pickVariant(task, master)
        return "resolution=${v.resolution ?: "?"} bandwidth=${v.bandwidth ?: "?"} uri=${LogSanitizer.sanitizeUrl(v.uri)}"
    }

    // ======================= 工具 =======================

    private fun segmentFile(segDir: File, index: Int): File =
        File(segDir, String.format(Locale.ROOT, "%06d", index))

    private fun segDone(f: File): Boolean = f.exists() && f.length() > 0

    private fun segDoneCount(segDir: File, total: Int): Int {
        var n = 0
        for (i in 0 until total) if (segDone(segmentFile(segDir, i))) n++
        return n
    }

    private fun cleanupParts(segDir: File) {
        segDir.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
    }

    private fun abortInFlight(taskId: String) {
        calls[taskId]?.forEach { runCatching { it.cancel() } }
    }

    private fun reportProgress(
        progress: (DownloadProgress) -> Unit,
        downloaded: AtomicLong,
        doneCounter: java.util.concurrent.atomic.AtomicInteger,
        lastAt: AtomicLong,
        lastBytes: AtomicLong,
        totalSegments: Int,
    ) {
        val now = System.currentTimeMillis()
        val bytes = downloaded.get()
        val dt = (now - lastAt.get()).coerceAtLeast(1)
        val speed = if (dt >= 500) {
            ((bytes - lastBytes.get()) * 1000 / dt).coerceAtLeast(0).also {
                lastBytes.set(bytes)
                lastAt.set(now)
            }
        } else 0L
        progress(
            DownloadProgress(
                downloadedBytes = bytes,
                totalBytes = 0, // HLS 字节总量未知
                speed = speed,
                segmentDone = doneCounter.get().coerceIn(0, totalSegments),
                segmentTotal = totalSegments,
            ),
        )
    }

    private suspend fun runCatchingWithLog(
        task: DownloadTask,
        block: suspend () -> File,
    ): Result<File> = try {
        Result.success(block())
    } catch (e: DownloadCancelledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: M3U8PlaylistParser.M3U8UnsupportedException) {
        AppLogRepository.e(LogTags.M3U8, "不支持的 HLS 特性：${e.label} msg=${e.message}")
        Result.failure(AppException(ErrorCode.PARSER_ERROR, e.message ?: "该 HLS 特性暂不支持", e.label))
    } catch (e: M3U8PlaylistParser.M3U8ParseException) {
        AppLogRepository.e(LogTags.M3U8, "playlist 解析失败 ${e.message}")
        Result.failure(AppException(ErrorCode.PARSER_ERROR, "M3U8 解析失败：${e.message}"))
    } catch (e: AppException) {
        Result.failure(e)
    } catch (e: IOException) {
        AppLogRepository.e(LogTags.M3U8, "网络错误 task=${task.id} err=${e.message}", e)
        Result.failure(AppException(ErrorCode.NETWORK_ERROR, "M3U8 下载失败：${e.message}"))
    } catch (e: Throwable) {
        AppLogRepository.e(LogTags.M3U8, "未捕获异常 task=${task.id}", e)
        Result.failure(AppException(ErrorCode.UNKNOWN_ERROR, "下载失败：${e.message ?: e::class.java.simpleName}"))
    }

    private companion object {
        const val CONCURRENCY = 6
        const val MAX_SEGMENT_RETRIES = 3
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_PLAYLIST_CHARS = 2 * 1024 * 1024
    }
}
