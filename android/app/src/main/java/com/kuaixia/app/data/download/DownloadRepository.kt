package com.kuaixia.app.data.download

import android.content.Context
import android.content.Intent
import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.DebugContext
import com.kuaixia.app.core.log.DownloadSummary
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.core.log.Stages
import com.kuaixia.app.data.download.db.DownloadTaskDao
import com.kuaixia.app.data.download.db.DownloadTaskMapper
import com.kuaixia.app.data.model.ImageResource
import com.kuaixia.app.data.model.StreamInfo
import com.kuaixia.app.data.model.VideoInfo
import com.kuaixia.app.data.parser.ParserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Downloader"

/** 进度整表传播的合并窗口（BUG-001）：窗口内多次进度只 flush 一次，全局统一节流。 */
private const val PROGRESS_FLUSH_INTERVAL_MS = 300L

/**
 * 下载仓库：下载任务的**唯一状态管理者**（UI/ViewModel 不得直接改任务状态）。
 *
 * - 单文件直链：HttpDownloader 下载 → MediaStore 保存（相册可见）。
 * - DASH 分离流：下载视频 + 下载音频 → FFmpeg 合并 → MediaStore 保存。
 *
 * ## Phase 3.6 能力
 * - **持久化**：创建/状态/进度（低频）/终态全量写 Room（[dao]），App 重启后恢复历史；
 * - **恢复策略**：重启时 DOWNLOADING 一律转 PAUSED（保留临时文件可续传），
 *   不假装"还在下载"；FAILED 允许重试；COMPLETED 直接显示历史；
 * - **断点续传**：按目标临时文件实际长度计算 Range offset（206 续传 / 200 重下 / 416 视为完成）；
 * - **CDN 失效自动重解析**：HTTP 4xx（403/404 等）或 416@offset0 时，若 [originalUrl] 存在，
 *   自动用 ParserManager 重新解析换新直链再试一次（只一次，防无限循环）；
 * - **前台服务**：进入下载时拉起 [DownloadService] 保活并展示通知，Service 只观察、不复制下载逻辑；
 * - **删除**：支持「仅删记录」或「删记录 + MediaStore 文件（ContentResolver）」。
 *
 * 健壮性：任何异常（IO / 非法参数 / FFmpeg / 取消）都被捕获并记录，绝不让 App 崩溃。
 */
class DownloadRepository(
    context: Context,
    private val dao: DownloadTaskDao,
    private val parserManager: ParserManager,
    private val downloader: Downloader = HttpDownloader(),
    private val m3u8Downloader: Downloader = M3U8Downloader(),
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** 活跃任务数（QUEUED/DOWNLOADING）——通知与 Service 生命周期依据。 */
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    /** 临时下载目录（cacheDir，纯 ASCII）。 */
    private val tempDir: File = File(appContext.cacheDir, "downloads")

    /** 进度写库节流：上次持久化时间。 */
    private val lastPersistAt = ConcurrentHashMap<String, Long>()

    /** 高频进度合并（BUG-001）：合并中间进度帧，仅低频整表传播。 */
    private val progressCoalescer = ProgressCoalescer(PROGRESS_FLUSH_INTERVAL_MS)

    init {
        tempDir.mkdirs()
        // 启动恢复：一次性从 Room 载入历史
        scope.launch {
            val restored = dao.getAll().map(DownloadTaskMapper::entityToTask)
            val recovered = restored.map { t ->
                when (t.state) {
                    // 进程被杀时仍在下载：绝不假装继续，转 PAUSED 保留临时文件（可续传）
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                        AppLogRepository.w(
                            LogTags.DOWNLOAD,
                            "恢复任务(中断→暂停) id=${t.id} title=${t.title?.take(30)} " +
                                "stage=${t.stage} retry=${t.retryCount}",
                        )
                        t.withState(
                            DownloadState.PAUSED,
                            errorMessage = "上次下载中断，可「继续」（断点续传）或刷新链接后重试",
                        )
                    }
                    else -> t
                }
            }
            _tasks.value = recovered
            recomputeActive()
            // 修正后的状态回写（DOWNLOADING→PAUSED）
            if (recovered.any { it.updatedAt != restored.first { r -> r.id == it.id }.updatedAt }) {
                dao.upsertAll(recovered.map(DownloadTaskMapper::taskToEntity))
            }
            AppLogRepository.i(
                LogTags.DOWNLOAD,
                "启动恢复完成 tasks=${recovered.size} active=${recovered.count { it.state == DownloadState.PAUSED }}",
            )
        }
    }

    // ======================= 任务创建 =======================

    /** 由解析结果 + 选中的流创建下载任务（不落库，由 enqueue 统一入队落库）。 */
    fun createTask(video: VideoInfo, stream: StreamInfo): DownloadTask {
        val kind = kindOf(stream)
        // HLS 最终输出一律 mp4；DASH/直链按流扩展名
        val ext = if (kind == DownloadKind.M3U8) "mp4"
        else stream.ext?.takeIf { it.isNotBlank() }?.lowercase() ?: "mp4"
        val base = MediaFileNaming.baseName(video.title, video.id, video.webpageUrl)
        val quality = stream.quality ?: stream.formatId
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val dashVideoUrl = if (kind == DownloadKind.DASH) stream.videoUrl else null
        val dashAudioUrl = if (kind == DownloadKind.DASH) stream.audioUrl else null
        val task = DownloadTask(
            id = id,
            url = stream.url,
            originalUrl = video.webpageUrl,
            fileName = "${base}_${quality}.$ext",
            saveDir = tempDir.absolutePath,
            kind = kind,
            source = video.platform,
            title = video.title,
            quality = quality,
            mimeType = "video/$ext",
            videoUrl = dashVideoUrl,
            audioUrl = dashAudioUrl,
            audioFileName = if (kind == DownloadKind.DASH) {
                "$id.audio.${stream.audioExt?.takeIf { it.isNotBlank() } ?: "m4a"}"
            } else null,
            audioCodec = if (kind == DownloadKind.DASH) stream.audioAcodec else null,
            httpHeaders = stream.videoHeaders,
            audioHeaders = stream.audioHeaders,
            createdAt = now,
            updatedAt = now,
        )

        DebugContext.enter(Stages.CREATE_TASK, id, stream.url)
        AppLogRepository.i(
            LogTags.DOWNLOAD,
            "创建任务 id=$id kind=${task.kind} title=${video.title?.take(40)} quality=$quality " +
                "fileName=${task.fileName} " +
                "originalUrl=${LogSanitizer.sanitizeUrl(task.originalUrl)} " +
                "videoUrl=${if (task.videoUrl != null) "有" else "无"} " +
                "audioUrl=${if (task.audioUrl != null) "有" else "无"} " +
                "protocol=${stream.protocol} " +
                "videoHeaders=[${LogSanitizer.headerKeys(task.httpHeaders)}] " +
                "audioHeaders=[${LogSanitizer.headerKeys(task.audioHeaders)}]",
        )
        return task
    }

    /** 由流的 HLS/DASH 特征决定下载管线类型。 */
    private fun kindOf(stream: StreamInfo): DownloadKind = when {
        stream.isM3u8 -> DownloadKind.M3U8
        stream.isDash -> DownloadKind.DASH
        else -> DownloadKind.DIRECT
    }

    /** 流的最终输出扩展名（HLS 一律 mp4）。 */
    private fun targetExt(stream: StreamInfo): String =
        if (kindOf(stream) == DownloadKind.M3U8) "mp4"
        else stream.ext?.takeIf { it.isNotBlank() }?.lowercase() ?: "mp4"

    /**
     * 由真实图片资源创建单张图片下载任务（图集第 index/1 起，共 total 张）。
     * 文件名：单图「标题.ext」，多图「标题_01.ext…」；扩展名取推断值，实际格式以响应为准。
     */
    fun createImageTask(video: VideoInfo, res: ImageResource, index: Int, total: Int): DownloadTask {
        val base = MediaFileNaming.baseName(video.title, video.id, video.webpageUrl)
        val ext = res.extension?.takeIf { it.isNotBlank() }?.lowercase() ?: "jpg"
        val fileName = if (total <= 1) "$base.$ext" else "${base}_%02d.%s".format(index, ext)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        return DownloadTask(
            id = id,
            url = res.url,
            originalUrl = video.webpageUrl,
            fileName = fileName,
            saveDir = tempDir.absolutePath,
            kind = DownloadKind.IMAGE,
            source = video.platform,
            title = video.title,
            quality = if (total > 1) "图片 ${index}/$total" else "图片",
            mimeType = res.mimeType ?: "image/$ext",
            httpHeaders = res.httpHeaders,
            createdAt = now,
            updatedAt = now,
        ).also { task ->
            AppLogRepository.i(
                LogTags.DOWNLOAD,
                "创建图片任务 id=$id fileName=$fileName mime=${task.mimeType} " +
                    "url=${LogSanitizer.sanitizeUrl(res.url)} " +
                    "headers=[${LogSanitizer.headerKeys(task.httpHeaders)}]",
            )
        }
    }

    /** 把图集/单图的全部图片资源入队（每个资源一个任务，可并发下载）。 */
    fun enqueueImages(video: VideoInfo, resources: List<ImageResource>) {
        if (resources.isEmpty()) return
        val total = resources.size
        resources.forEachIndexed { idx, res ->
            val task = createImageTask(video, res, idx + 1, total)
            enqueue(task)
        }
    }

    /** 加入队列：先落库再启动（App 被杀也不丢任务）。 */
    fun enqueue(task: DownloadTask) {
        if (_tasks.value.any { it.id == task.id }) return
        _tasks.update { listOf(task) + it }
        recomputeActive()
        persist(task.id, force = true)
        AppLogger.i("enqueue task=${task.id} kind=${task.kind}", TAG)
        start(task.id)
    }

    // ======================= 状态机（唯一入口） =======================

    fun pause(id: String) {
        AppLogRepository.i(LogTags.DOWNLOAD, "暂停任务 id=$id")
        downloader.cancel(id)
        m3u8Downloader.cancel(id)
        setStateInternal(id) { t ->
            t.withState(DownloadState.PAUSED, errorMessage = null)
        }
    }

    /** 继续（PAUSED 断点续传）。 */
    fun resume(id: String) {
        val task = current(id) ?: return
        if (task.state != DownloadState.PAUSED) return
        if (task.kind != DownloadKind.M3U8 && task.stage == DownloadStage.MERGING) {
            // DASH 合并中断：临时分片已齐，清掉残缺合并产物直接重跑合并（M3U8 合并幂等，无需清）
            deleteQuietly(File(task.saveDir, "${task.id}.merged.mp4"))
        }
        AppLogRepository.i(LogTags.DOWNLOAD, "继续任务 id=$id kind=${task.kind} stage=${task.stage}")
        setStateInternal(id) { it.withState(DownloadState.QUEUED, errorMessage = null) }
        start(id)
    }

    /** 取消：停止下载并删除临时文件。 */
    fun cancel(id: String) {
        AppLogRepository.i(LogTags.DOWNLOAD, "取消任务 id=$id")
        downloader.cancel(id)
        m3u8Downloader.cancel(id)
        jobs.remove(id)?.cancel()
        deleteTempFiles(id)
        setStateInternal(id) { t ->
            t.withState(DownloadState.CANCELLED, errorMessage = null)
        }
    }

    /** 重试（FAILED/PAUSED/CANCELLED）：清理临时文件后重新下载（若 URL 失效会自动重解析）。 */
    fun retry(id: String) {
        val task = current(id) ?: return
        AppLogRepository.i(
            LogTags.DOWNLOAD,
            "重试任务 id=$id state=${task.state} retryCount=${task.retryCount}",
        )
        if (task.state == DownloadState.CANCELLED) {
            // 已取消任务重试 = 复位为全新任务（保留描述信息）
            val reset = task.copy(
                state = DownloadState.QUEUED,
                stage = DownloadStage.QUEUED,
                errorMessage = null,
                mediaStoreUri = null,
                retryCount = 0,
                progress = DownloadProgress(),
                updatedAt = System.currentTimeMillis(),
                completedAt = null,
            )
            setStateInternal(reset.id) { reset }
            start(reset.id)
            return
        }
        // FAILED / PAUSED：清残留后从头（FAILED 的文件不可信）
        deleteTempFiles(id)
        setStateInternal(id) { t ->
            t.withState(DownloadState.QUEUED, errorMessage = null, stage = DownloadStage.QUEUED)
                .copy(progress = DownloadProgress(), retryCount = t.retryCount + 1, completedAt = null)
        }
        start(id)
    }

    /**
     * 删除任务记录的**唯一共享实现**：[delete] 与 [deleteBatch] 共用，避免两套逻辑漂移。
     *
     * 顺序：取消下载（downloader / m3u8 / job）→ 清理未完成任务的缓存临时文件 → 移除内存记录 → 删 DAO 记录。
     *
     * **只删除任务记录与缓存临时文件；绝不删除已保存到系统相册的最终媒体**
     * （视频 `Movies/快夏`、图片 `Pictures/快夏`，由 [MediaStoreSaver] 独立落地，用户可自行在相册/文件管理器管理）。
     */
    private fun deleteInternal(id: String, logPrefix: String) {
        val task = current(id)
        AppLogRepository.i(LogTags.DOWNLOAD, "${logPrefix}任务 id=$id state=${task?.state}")
        downloader.cancel(id)
        m3u8Downloader.cancel(id)
        jobs.remove(id)?.cancel()
        deleteTempFiles(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
        recomputeActive()
        persistScope.launch { runCatching { dao.deleteById(id) } }
    }

    /** 删除单个任务记录（不删除已保存的最终媒体文件）。 */
    fun delete(id: String) {
        deleteInternal(id, logPrefix = "删除")
    }

    /** 批量删除任务记录（共用 [deleteInternal]；不删除已保存的最终媒体文件）。 */
    fun deleteBatch(ids: List<String>) {
        if (ids.isEmpty()) return
        AppLogRepository.i(LogTags.DOWNLOAD, "批量删除任务 count=${ids.size}")
        ids.forEach { id -> deleteInternal(id, logPrefix = "批量删除") }
    }

    /** 全部状态集中在内存 + Room，UI 只读 [tasks]。以下为内部实现。 */

    // ======================= 内部：下载调度 =======================

    private fun start(id: String) {
        val task = current(id) ?: return
        if (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.COMPLETED) return
        // 拉起前台服务（下载期间保活 + 通知）
        DownloadService.start(appContext)

        // 进入下载态（唯一入口置 DOWNLOADING）
        _tasks.update { list ->
            list.map { if (it.id == id) it.withState(DownloadState.DOWNLOADING) else it }
        }
        recomputeActive()

        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            try {
                val result = when (task.kind) {
                    DownloadKind.M3U8 -> downloadM3U8(task)
                    DownloadKind.DASH -> downloadDash(task)
                    DownloadKind.DIRECT -> downloadSingle(task)
                    DownloadKind.IMAGE -> downloadImage(task)
                }
                result.fold(
                    onSuccess = { file ->
                        AppLogRepository.i(LogTags.DOWNLOAD, "任务完成 id=$id")
                        setStateInternal(id) {
                            it.withState(DownloadState.COMPLETED, stage = DownloadStage.DONE)
                        }
                        deleteTempFiles(id)
                    },
                    onFailure = { e ->
                        handleFailure(task, e)
                    },
                )
            } catch (e: DownloadCancelledException) {
                AppLogRepository.i(LogTags.DOWNLOAD, "任务取消（抛出） id=$id")
            } catch (e: CancellationException) {
                AppLogRepository.i(LogTags.DOWNLOAD, "协程取消 id=$id")
            } catch (e: Throwable) {
                // 兜底：任何未预期异常都完整记录并标记失败，绝不崩溃
                AppLogRepository.e(
                    LogTags.DOWNLOAD,
                    "下载未捕获异常 id=$id stage=${DebugContext.currentStage}",
                    e,
                )
                setStateInternal(id) {
                    it.withState(
                        DownloadState.FAILED,
                        errorMessage = "下载失败：${e.message ?: e::class.java.simpleName}",
                    )
                }
                deleteTempFiles(id)
            } finally {
                DebugContext.clear()
                recomputeActive()
            }
        }
    }

    /**
     * 失败处理：
     * - WebView 嗅探捕获的任务（source=webview）403/404/410 **不自动重解析**：
     *   WebView 直链带页面上下文/会话 Cookie，交给 yt-dlp/ServerParser 无意义；
     *   直接 FAILED 保留原始错误，用户可「重试」或重新嗅探。
     * - 其它（yt-dlp/server）疑似 CDN URL 失效（HTTP 403/404/410）且从未重解析过、
     *   且存在 originalUrl → 自动重新解析换新直链再试一次（只一次）。
     */
    private suspend fun handleFailure(task: DownloadTask, e: Throwable) {
        val msg = e.message ?: "下载失败"
        // BUG-003：HTTP code 取 AppException.detail 的「HTTP_CODE:<n>」结构化字段，
        // **不再依赖中文 message 正则**。REPARSE 判定完全由结构化 code 决定。
        val httpCode = httpCodeOf(e)
        val urlLikelyExpired = isUrlExpiredError(httpCode, msg)
        val canReparse = task.source != "webview" &&
            task.originalUrl != null && task.retryCount == 0 && urlLikelyExpired

        if (canReparse) {
            AppLogRepository.w(
                LogTags.DOWNLOAD,
                "疑似 CDN URL 失效($msg)，自动重新解析 id=${task.id} url=${LogSanitizer.sanitizeUrl(task.originalUrl)}",
            )
            deleteTempFiles(task.id)
            reparseInternal(task, reason = msg)
        } else {
            AppLogRepository.e(LogTags.DOWNLOAD, "任务失败 id=${task.id} err=$msg", e)
            // 日志 V2：统一 DOWNLOAD SUMMARY（platform/type/quality/http/elapsedMs/error；仅日志，不改业务）
            runCatching {
                AppLogRepository.i(
                    LogTags.DOWNLOAD,
                    DownloadSummary.render(
                        DownloadSummary.Fields(
                            timeMs = System.currentTimeMillis(),
                            platform = DownloadSummary.platformOf(task.originalUrl ?: task.url),
                            type = task.kind.name,
                            quality = task.quality,
                            http = httpCode,
                            elapsedMs = (System.currentTimeMillis() - task.createdAt).coerceAtLeast(0),
                            error = msg,
                        ),
                    ),
                )
            }
            setStateInternal(task.id) {
                it.withState(DownloadState.FAILED, errorMessage = friendlyError(msg))
            }
            deleteTempFiles(task.id)
        }
    }

    /** 用户主动「刷新链接并重试」：重解析原始 URL 换新直链再下载。WebView 任务无该通道。 */
    fun reparseAndResume(id: String) {
        val task = current(id) ?: return
        if (task.source == "webview") {
            AppLogRepository.w(
                LogTags.DOWNLOAD,
                "WebView 嗅探任务无重解析通道，请重新嗅探 id=$id",
            )
            setStateInternal(id) {
                it.withState(
                    DownloadState.FAILED,
                    errorMessage = "该资源来自 WebView 嗅探，请回「WebView 嗅探测试」重新捕获",
                )
            }
            return
        }
        if (task.originalUrl == null) {
            AppLogRepository.w(LogTags.DOWNLOAD, "无法重解析：无 originalUrl id=$id")
            return
        }
        AppLogRepository.i(LogTags.DOWNLOAD, "用户触发重新解析 id=$id")
        deleteTempFiles(id)
        reparseInternal(task, reason = "用户操作")
    }

    /** 执行重解析：ParserManager.parse(originalUrl) → 匹配 quality → 更新 URL/headers → 重下。 */
    private fun reparseInternal(task: DownloadTask, reason: String) {
        jobs[task.id]?.cancel()
        jobs[task.id] = scope.launch {
            DebugContext.enter(Stages.IDLE, task.id, task.originalUrl)
            val outcome = parserManager.parse(task.originalUrl!!)
            outcome.fold(
                onSuccess = { oc ->
                    val picked = pickStream(oc.info, task.quality)
                    if (picked == null) {
                        AppLogRepository.e(LogTags.DOWNLOAD, "重解析后无匹配清晰度 id=${task.id}")
                        setStateInternal(task.id) {
                            it.withState(
                                DownloadState.FAILED,
                                errorMessage = "链接已失效，请回到解析页重新解析",
                            )
                        }
                        return@launch
                    }
                    AppLogRepository.i(
                        LogTags.DOWNLOAD,
                        "重解析成功 id=${task.id} quality=${picked.quality ?: picked.formatId} " +
                            "source=${oc.source} reason=$reason",
                    )
                    val now = System.currentTimeMillis()
                    val newKind = kindOf(picked)
                    val newExt = if (newKind == DownloadKind.M3U8) "mp4"
                    else picked.ext?.takeIf { it.isNotBlank() } ?: "mp4"
                    setStateInternal(task.id) { old ->
                        old.copy(
                            kind = newKind,
                            url = picked.url,
                            videoUrl = if (newKind == DownloadKind.DASH) picked.videoUrl else null,
                            audioUrl = if (newKind == DownloadKind.DASH) picked.audioUrl else null,
                            audioFileName = if (newKind == DownloadKind.DASH) {
                                "${old.id}.audio.${picked.audioExt?.takeIf { it.isNotBlank() } ?: "m4a"}"
                            } else null,
                            audioCodec = if (newKind == DownloadKind.DASH) picked.audioAcodec else null,
                            httpHeaders = picked.videoHeaders,
                            audioHeaders = picked.audioHeaders,
                            mimeType = "video/$newExt",
                            fileName = "${MediaFileNaming.baseName(oc.info.title, oc.info.id, oc.info.webpageUrl)}_" +
                                "${picked.quality ?: picked.formatId}.$newExt",
                            title = oc.info.title,
                            state = DownloadState.QUEUED,
                            stage = DownloadStage.QUEUED,
                            errorMessage = null,
                            mediaStoreUri = null,
                            progress = DownloadProgress(),
                            retryCount = old.retryCount + 1,
                            completedAt = null,
                            updatedAt = now,
                        )
                    }
                    start(task.id)
                },
                onFailure = { e ->
                    AppLogRepository.e(LogTags.DOWNLOAD, "重解析失败 id=${task.id} err=${e.message}", e)
                    setStateInternal(task.id) {
                        it.withState(
                            DownloadState.FAILED,
                            errorMessage = "链接已失效，重新解析失败：${(e as? AppException)?.message ?: e.message}",
                        )
                    }
                    deleteTempFiles(task.id)
                },
            )
        }
    }

    /** 从重解析结果中挑选与原任务清晰度匹配的流。 */
    private fun pickStream(info: VideoInfo, qualityHint: String?): StreamInfo? {
        if (qualityHint.isNullOrBlank()) {
            return info.streams.firstOrNull { it.isDash } ?: info.streams.firstOrNull()
        }
        return info.streams.firstOrNull { (it.quality ?: it.formatId) == qualityHint }
            ?: info.streams.firstOrNull { it.isDash }
            ?: info.streams.firstOrNull()
    }

    // ======================= 内部：下载执行 =======================

    /** 单文件直链下载（兼容普通 MP4）。offset 按临时文件实际长度（断点续传）。 */
    private suspend fun downloadSingle(task: DownloadTask): Result<File> {
        setStage(task.id, DownloadStage.DOWNLOADING_VIDEO)
        DebugContext.enter(Stages.DOWNLOADING_VIDEO, task.id, task.url)
        AppLogRepository.i(LogTags.DOWNLOAD, "开始下载（单文件） id=${task.id} url=${LogSanitizer.sanitizeUrl(task.url)}")
        val file = File(task.saveDir, task.fileName)
        val offset = file.length().coerceAtLeast(0)
        val result = downloader.download(task, offset, { p -> updateProgress(task.id, p) })
        if (result.isFailure) return result
        return finalizeToMediaStore(task, result.getOrThrow())
    }

    /** 单张图片直下：HttpDownloader 拉取 → 用真实 Content-Type 定稿扩展名/MIME → Pictures/快夏。 */
    private suspend fun downloadImage(task: DownloadTask): Result<File> {
        setStage(task.id, DownloadStage.DOWNLOADING_VIDEO)
        DebugContext.enter(Stages.DOWNLOADING_VIDEO, task.id, task.url)
        AppLogRepository.i(
            LogTags.DOWNLOAD,
            "开始下载图片 id=${task.id} fileName=${task.fileName} hint=${task.mimeType} " +
                "url=${LogSanitizer.sanitizeUrl(task.url)}",
        )
        val file = File(task.saveDir, task.fileName)
        var responseContentType: String? = null
        val result = downloader.download(task, 0, { p -> updateProgress(task.id, p) }) { ct ->
            responseContentType = ct?.substringBefore(';')?.trim()
        }
        if (result.isFailure) return result

        val realMime = responseContentType.orEmpty()
        // 明确非图片且非泛化类型（如 text/html 页面被误当图片）→ 立即失败并清理，绝不落 Pictures
        if (realMime.isNotBlank() &&
            !ImageTypeSniffer.isImageMime(realMime) &&
            !ImageTypeSniffer.isGenericMime(realMime)
        ) {
            AppLogRepository.e(
                LogTags.DOWNLOAD,
                "图片下载失败 code=NOT_IMAGE_RESOURCE mime=$realMime " +
                    "url=${LogSanitizer.sanitizeUrl(task.url)}",
            )
            deleteQuietly(file)
            return Result.failure(
                AppException(
                    ErrorCode.NETWORK_ERROR,
                    "下载失败：非图片资源（$realMime）。该资源不是真实图片。",
                ),
            )
        }

        // 定稿：真实响应头 image/* 优先；响应缺失/泛化(octet-stream) 时用文件魔数兜底确认；
        // 两者皆无 → 退回解析阶段的 hint；仍不认识 → 失败（防 HTML/错误页存成假图片）。
        var finalMime: String? = null
        var sniffed: ImageTypeSniffer.Type? = null
        if (realMime.isBlank() || ImageTypeSniffer.isGenericMime(realMime)) {
            sniffed = runCatching {
                file.inputStream().use { ins -> ins.readNBytes(64) }
            }.getOrNull()?.let { ImageTypeSniffer.sniff(it) }
        }
        when {
            ImageTypeSniffer.isImageMime(realMime) -> finalMime = realMime
            sniffed != null -> finalMime = sniffed!!.mime
            !task.mimeType.isNullOrBlank() && ImageTypeSniffer.isImageMime(task.mimeType) -> {
                finalMime = task.mimeType
            }
            sniffed == null && realMime.isNotBlank() -> {
                // image/* 以外的明确类型已在上方拦截；走到这里说明头部识别失败
            }
        }
        if (finalMime == null) {
            AppLogRepository.e(
                LogTags.DOWNLOAD,
                "图片下载失败 code=NOT_IMAGE_RESOURCE mime=UNKNOWN url=${LogSanitizer.sanitizeUrl(task.url)}",
            )
            deleteQuietly(file)
            return Result.failure(
                AppException(ErrorCode.NETWORK_ERROR, "下载失败：无法确认真实图片格式（响应非图片或魔数未知）。"),
            )
        }
        val finalExt = ImageTypeSniffer.extOf(finalMime)
            ?: extensionForImageMime(finalMime)
        val saveTask = task.copy(
            fileName = replaceExtension(task.fileName, finalExt),
            mimeType = finalMime,
        )
        AppLogRepository.i(
            LogTags.DOWNLOAD,
            "图片下载完成 id=${task.id} responseMime=${realMime.ifBlank { "(无头)" }} " +
                "sniffed=${sniffed?.ext ?: "-"} finalMime=$finalMime finalExt=$finalExt file=${file.name}",
        )
        return finalizeToMediaStore(saveTask, file)
    }

    /** 图片 MIME → 扩展名（最终命名依据；.ico 不会误存为 .avif）。 */
    private fun extensionForImageMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        "image/apng" -> "apng"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/vnd.microsoft.icon", "image/x-icon" -> "ico"
        "image/svg+xml" -> "svg"
        else -> "jpg"
    }

    private fun replaceExtension(fileName: String, ext: String): String {
        val slash = fileName.lastIndexOf('/')
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > slash) fileName.substring(0, dot) else fileName
        return "$base.$ext"
    }

    /** HLS/M3U8：委托 [m3u8Downloader] 下载分片并合并，产物写入 MediaStore（M3U8 输出固定 merged.mp4）。 */
    private suspend fun downloadM3U8(task: DownloadTask): Result<File> {
        setStage(task.id, DownloadStage.DOWNLOADING_VIDEO)
        DebugContext.enter(Stages.DOWNLOADING_VIDEO, task.id, task.url)
        AppLogRepository.i(
            LogTags.DOWNLOAD,
            "开始下载（M3U8/HLS） id=${task.id} url=${LogSanitizer.sanitizeUrl(task.url)}",
        )
        val result = m3u8Downloader.download(task, 0, { p -> updateProgress(task.id, p) })
        if (result.isFailure) return result
        // 产物为 <taskId>/merged.mp4（FFmpeg concat 在 M3U8Downloader 内完成，进度已按分片 X/N 上报）
        val merged = File(task.m3u8OutputPath)
        if (!merged.exists() || merged.length() <= 0) {
            return Result.failure(AppException(ErrorCode.PARSER_ERROR, "M3U8 合并产物缺失"))
        }
        return finalizeToMediaStore(task, merged)
    }

    /** DASH 分离流：下载视频 + 下载音频 → FFmpeg 合并 → MediaStore。支持分段断点续传。 */
    private suspend fun downloadDash(task: DownloadTask): Result<File> {
        val videoFile = File(task.saveDir, "${task.id}.video.mp4")
        val audioFile = File(task.saveDir, task.audioFileName!!)
        val mergedFile = File(task.saveDir, "${task.id}.merged.mp4")

        // BUG-003：DASH 每一段由本方法自身做分段续传 + 重试，不允许 HttpDownloader 叠加内部重试。
        // 仅当注入的具体实现是 HttpDownloader 时才走 downloadSegment（非 Http 实现行为不变）。
        val httpDownloader = downloader as? HttpDownloader

        // 1. 视频流：stage 已越过 DOWNLOADING_VIDEO 且文件非空 → 视为已完整，跳过
        val videoDone = task.stage.ordinal > DownloadStage.DOWNLOADING_VIDEO.ordinal &&
            videoFile.length() > 0
        if (!videoDone) {
            setStage(task.id, DownloadStage.DOWNLOADING_VIDEO)
            DebugContext.enter(Stages.DOWNLOADING_VIDEO, task.id, task.videoUrl)
            AppLogRepository.i(
                LogTags.DOWNLOAD,
                "开始下载视频 id=${task.id} offset=${videoFile.length()} " +
                    "url=${LogSanitizer.sanitizeUrl(task.videoUrl)}",
            )
            val videoTask = task.copy(url = task.videoUrl!!, fileName = videoFile.name)
            val videoResult = if (httpDownloader != null) {
                httpDownloader.downloadSegment(videoTask, videoFile.length(), { p ->
                    updateProgress(task.id, p)
                })
            } else {
                downloader.download(videoTask, videoFile.length(), { p ->
                    updateProgress(task.id, p)
                })
            }
            if (videoResult.isFailure) return videoResult
        } else {
            AppLogRepository.i(
                LogTags.DOWNLOAD,
                "视频分片已完整跳过 id=${task.id} size=${videoFile.length()}",
            )
        }

        // 2. 音频流
        val audioDone = task.stage == DownloadStage.MERGING && audioFile.length() > 0
        if (!audioDone) {
            setStage(task.id, DownloadStage.DOWNLOADING_AUDIO)
            DebugContext.enter(Stages.DOWNLOADING_AUDIO, task.id, task.audioUrl)
            AppLogRepository.i(
                LogTags.DOWNLOAD,
                "开始下载音频 id=${task.id} offset=${audioFile.length()} " +
                    "url=${LogSanitizer.sanitizeUrl(task.audioUrl)}",
            )
            val audioTask = task.copy(
                url = task.audioUrl!!,
                fileName = audioFile.name,
                httpHeaders = task.audioHeaders,
            )
            val audioResult = if (httpDownloader != null) {
                httpDownloader.downloadSegment(audioTask, audioFile.length(), { p ->
                    updateProgress(task.id, p)
                })
            } else {
                downloader.download(audioTask, audioFile.length(), { p ->
                    updateProgress(task.id, p)
                })
            }
            if (audioResult.isFailure) return audioResult
        } else {
            AppLogRepository.i(
                LogTags.DOWNLOAD,
                "音频分片已完整跳过 id=${task.id} size=${audioFile.length()}",
            )
        }

        // 3. FFmpeg 合并（幂等：-y 覆盖输出）
        setStage(task.id, DownloadStage.MERGING)
        DebugContext.enter(Stages.MERGING, task.id, null)
        val mergeResult = FFmpegMerger.merge(videoFile, audioFile, mergedFile)
        if (mergeResult.isFailure) return mergeResult

        // 4. 写入 MediaStore（相册可见）
        return finalizeToMediaStore(task, mergeResult.getOrThrow())
    }

    /** 写入 MediaStore（图片 → Pictures/快夏；音视频 → Movies/快夏），返回成功/失败，并把 uri 记录回任务。 */
    private fun finalizeToMediaStore(task: DownloadTask, source: File): Result<File> {
        DebugContext.enter(Stages.MEDIASTORE, task.id, null)
        val targetName = uniqueDisplayName(task.fileName)
        val isImage = task.kind == DownloadKind.IMAGE
        val relativePath = if (isImage) "Pictures/快夏" else "Movies/快夏"
        val uri = if (isImage) {
            MediaStoreSaver.saveToPictures(appContext, source, targetName, task.mimeType ?: "image/jpeg")
        } else {
            MediaStoreSaver.saveToMovies(appContext, source, targetName)
        }
        return if (uri != null) {
            AppLogRepository.i(
                LogTags.MEDIASTORE,
                "保存成功 displayName=$targetName mimeType=${task.mimeType ?: if (isImage) "image/jpeg" else "video/mp4"} " +
                    "relativePath=$relativePath uri=$uri",
            )
            if (targetName != task.fileName) {
                setStateInternal(task.id) { it.copy(fileName = targetName) }
            }
            Result.success(source)
        } else {
            AppLogRepository.e(LogTags.MEDIASTORE, "保存失败 displayName=$targetName")
            Result.failure(AppException(ErrorCode.PARSER_ERROR, if (isImage) "保存图片到相册失败" else "保存到相册失败"))
        }
    }

    // ======================= 状态更新（唯一路径 + 落库） =======================

    private fun setStateInternal(id: String, transform: (DownloadTask) -> DownloadTask) {
        // BUG-001：终态跃迁前先取走该 id 的待写进度，保证终态携带最新进度（不丢关键状态）。
        val pendingProgress = progressCoalescer.take(id)
        _tasks.update { list ->
            list.map { t ->
                if (t.id == id) {
                    val base = pendingProgress?.let { t.withProgress(it) } ?: t
                    transform(base)
                } else t
            }
        }
        recomputeActive()
        persist(id, force = true)
    }

    /** 活跃任务 = QUEUED + DOWNLOADING（前台服务与通知依据）。 */
    private fun recomputeActive() {
        val n = _tasks.value.count { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
        if (_activeCount.value != n) _activeCount.value = n
    }

    private fun setStage(id: String, stage: DownloadStage) {
        _tasks.update { list ->
            list.map { if (it.id == id) it.withStage(stage) else it }
        }
        persist(id, force = true)
    }

    private fun updateProgress(id: String, progress: DownloadProgress) {
        // BUG-001：合并高频进度帧；只在超过窗口时整表 flush 一次，避免每次进度 O(n) 重建 + 发射。
        if (progressCoalescer.offer(id, progress, System.currentTimeMillis())) {
            flushProgress()
        }
    }

    /** 把合并窗口内积累的进度一次性应用到整表（O(n) 仅每 300ms 一次）。 */
    private fun flushProgress() {
        val snapshot = progressCoalescer.drain()
        if (snapshot.isEmpty()) return
        _tasks.update { list ->
            list.map { t -> snapshot[t.id]?.let { t.withProgress(it) } ?: t }
        }
        // 进度落库仍走既有 500ms 节流（force=false），Room 行为不变。
        snapshot.keys.forEach { id -> persist(id, force = false) }
    }

    private fun current(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    /** 异步落库；force=false 时按 ~500ms 节流（进度高频更新不刷爆 Room）。 */
    private fun persist(id: String, force: Boolean) {
        val task = current(id) ?: return
        val now = System.currentTimeMillis()
        val last = lastPersistAt[id] ?: 0L
        if (!force && now - last < 500) return
        lastPersistAt[id] = now
        persistScope.launch {
            runCatching { dao.upsert(DownloadTaskMapper.taskToEntity(task)) }
                .onFailure { AppLogger.w("persist fail id=$id err=${it.message}", TAG) }
        }
    }

    // ======================= 工具 =======================

    private fun deleteTempFiles(id: String) {
        val task = current(id) ?: return
        val files = mutableListOf<String>()
        files += task.filePath
        task.audioFilePath?.let { files += it }
        files += File(task.saveDir, "$id.video.mp4").absolutePath
        files += File(task.saveDir, "$id.merged.mp4").absolutePath
        if (task.kind == DownloadKind.M3U8) {
            // M3U8：分片目录 + 合并产物
            runCatching { File(task.segmentDir).deleteRecursively() }
                .onFailure { AppLogger.w("delete segment dir fail err=${it.message}", TAG) }
            files += task.m3u8OutputPath
        }
        files.forEach { path ->
            runCatching { File(path).delete() }
                .onFailure { AppLogger.w("delete temp fail path=$path err=${it.message}", TAG) }
        }
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim()
        val maxLen = 80
        return when {
            cleaned.length > maxLen -> cleaned.substring(0, maxLen)
            cleaned.isNotEmpty() -> cleaned
            else -> "video"
        }
    }

    /** MediaStore 重名检测：已存在同名则自动 xxx (1).mp4 / (2).mp4，绝不覆盖。 */
    private fun uniqueDisplayName(fileName: String): String {
        if (!fileName.contains('.')) return fileName
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.')
        val exists = { name: String ->
            runCatching {
                val collection = android.provider.MediaStore.Video.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY,
                )
                val projection = arrayOf(android.provider.MediaStore.Video.Media._ID)
                appContext.contentResolver.query(
                    collection,
                    projection,
                    "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} = ?",
                    arrayOf(name),
                    null,
                )?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
        }
        if (!exists(fileName)) return fileName
        var i = 1
        while (true) {
            val candidate = "$base ($i).$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }

    /**
     * 判断是否为「URL 已失效」类错误（403/404/410）。
     *
     * BUG-003：**以结构化 [httpCode] 为主**（不再依赖中文 message 文本）；
     * 仅在结构化 code 缺失（历史异常构造路径）时，才回退 message 匹配兼容。
     */
    private fun isUrlExpiredError(httpCode: Int?, msg: String): Boolean {
        if (httpCode != null) return httpCode == 403 || httpCode == 404 || httpCode == 410
        return msg.contains("HTTP 403") || msg.contains("HTTP 404") || msg.contains("HTTP 410")
    }

    /**
     * BUG-003：从失败异常中**结构化**提取 HTTP 状态码。
     *
     * 优先读 [AppException.detail] 的 `HTTP_CODE:<n>`（由 `HttpDownloader` 写入，
     * 与中文提示文本完全解耦）；仅在缺失时回退旧的 message 正则，保持向后兼容。
     */
    private fun httpCodeOf(e: Throwable): Int? {
        val detail = (e as? AppException)?.detail
        if (!detail.isNullOrBlank()) {
            val marker = "HTTP_CODE:"
            val idx = detail.indexOf(marker)
            if (idx >= 0) {
                return detail.substring(idx + marker.length)
                    .takeWhile { it.isDigit() }
                    .toIntOrNull()
            }
        }
        val msg = e.message ?: return null
        return Regex("(?:HTTP|status)[^0-9]{0,6}(\\d{3})", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun friendlyError(raw: String): String = raw
}
