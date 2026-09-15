package com.kuaixia.app.data.download.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 下载任务持久化实体（Room）。
 *
 * 存储字段说明：
 * - [videoHeadersJson]/[audioHeadersJson]：仅存**非敏感**请求头（Referer/User-Agent/Origin 等），
 *   Cookie/Authorization/token/签名类头一律剔除 —— 会话级或临时签名上下文不长期落库，
 *   恢复时若 CDN 需要缺失的头，走「按 originalUrl 重新解析」。
 * - 敏感的 CDN 直链仅作「当前资源缓存」保存；任务的权威来源是 [originalUrl]。
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    @PrimaryKey val id: String,

    // ---- 描述信息 ----
    val title: String?,
    /** 任务原始来源（视频网页 URL），URL 失效重解析的锚点。 */
    val originalUrl: String?,
    /** 当前资源主 url（单文件直链或 DASH 视频流）——可能过期。 */
    val url: String,
    val fileName: String,
    val audioFileName: String?,
    val saveDir: String,
    val quality: String?,
    val mimeType: String?,
    val audioCodec: String?,

    // ---- DASH / 下载资源 ----
    val videoUrl: String?,
    val audioUrl: String?,
    val isDash: Boolean,

    // ---- 请求头（仅非敏感子集，JSON 序列化）----
    val videoHeadersJson: String,
    val audioHeadersJson: String,

    // ---- 状态机 ----
    val state: String,
    val stage: String,
    val errorMessage: String?,

    // ---- 下载类型（DIRECT/DASH/M3U8；v2 迁移加入）----
    val kind: String,

    // ---- 来源标记（webview/…；v3 迁移加入，用于失败恢复策略区分）----
    val source: String,

    // ---- 进度 ----
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speed: Long,

    // ---- MediaStore / 文件 ----
    val mediaStoreUri: String?,

    // ---- 生命周期 ----
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
    val retryCount: Int,
)
