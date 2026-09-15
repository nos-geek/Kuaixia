package com.kuaixia.app.data.download.db

import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.data.download.DownloadKind
import com.kuaixia.app.data.download.DownloadProgress
import com.kuaixia.app.data.download.DownloadStage
import com.kuaixia.app.data.download.DownloadState
import com.kuaixia.app.data.download.DownloadTask
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room 实体 <-> 领域模型 [DownloadTask] 映射。
 *
 * 头序列化注意：入库前经 [LogSanitizer.persistableHeaders] 只保留非敏感头，
 * 避免 Cookie/Authorization/token 长期落库；从库恢复时缺失的头不补救
 * （由 Repository 在需要时按 originalUrl 重新解析生成）。
 */
object DownloadTaskMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun taskToEntity(task: DownloadTask): DownloadTaskEntity = DownloadTaskEntity(
        id = task.id,
        title = task.title,
        originalUrl = task.originalUrl,
        url = task.url,
        fileName = task.fileName,
        audioFileName = task.audioFileName,
        saveDir = task.saveDir,
        quality = task.quality,
        mimeType = task.mimeType,
        audioCodec = task.audioCodec,
        videoUrl = task.videoUrl,
        audioUrl = task.audioUrl,
        isDash = task.isDash,
        videoHeadersJson = encodeHeaders(task.httpHeaders),
        audioHeadersJson = encodeHeaders(task.audioHeaders),
        state = task.state.name,
        stage = task.stage.name,
        errorMessage = task.errorMessage,
        kind = task.kind.name,
        source = task.source ?: "",
        downloadedBytes = task.progress.downloadedBytes,
        totalBytes = task.progress.totalBytes,
        speed = task.progress.speed,
        mediaStoreUri = task.mediaStoreUri,
        createdAt = task.createdAt,
        updatedAt = task.updatedAt,
        completedAt = task.completedAt,
        retryCount = task.retryCount,
    )

    fun entityToTask(e: DownloadTaskEntity): DownloadTask = DownloadTask(
        id = e.id,
        title = e.title,
        originalUrl = e.originalUrl,
        url = e.url,
        fileName = e.fileName,
        audioFileName = e.audioFileName,
        saveDir = e.saveDir,
        quality = e.quality,
        mimeType = e.mimeType,
        audioCodec = e.audioCodec,
        videoUrl = e.videoUrl,
        audioUrl = e.audioUrl,
        source = e.source.takeIf { it.isNotBlank() },
        // kind 为 DIRECT 但带 videoUrl+audioUrl → 老版本 DASH 数据兼容推导
        kind = runCatching { DownloadKind.valueOf(e.kind) }.getOrDefault(DownloadKind.DIRECT)
            .let { if (it == DownloadKind.DIRECT && e.videoUrl != null && e.audioUrl != null) DownloadKind.DASH else it },
        state = runCatching { DownloadState.valueOf(e.state) }.getOrDefault(DownloadState.FAILED),
        stage = runCatching { DownloadStage.valueOf(e.stage) }.getOrDefault(DownloadStage.QUEUED),
        errorMessage = e.errorMessage,
        progress = DownloadProgress(e.downloadedBytes, e.totalBytes, e.speed),
        httpHeaders = decodeHeaders(e.videoHeadersJson),
        audioHeaders = decodeHeaders(e.audioHeadersJson),
        mediaStoreUri = e.mediaStoreUri,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        completedAt = e.completedAt,
        retryCount = e.retryCount,
    )

    private fun encodeHeaders(headers: Map<String, String>): String =
        runCatching {
            json.encodeToString(
                MapSerializer(String.serializer(), String.serializer()),
                LogSanitizer.persistableHeaders(headers),
            )
        }.getOrDefault("{}")

    private fun decodeHeaders(raw: String): Map<String, String> = runCatching {
        json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            raw,
        )
    }.getOrDefault(emptyMap())
}
