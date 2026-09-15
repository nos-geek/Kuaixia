package com.kuaixia.app.core.model

import kotlinx.serialization.Serializable

/** 单条可下载媒体流（清晰度）。字段名与 Server 端 Pydantic 模型一致。 */
@Serializable
data class MediaStream(
    val id: String,
    val url: String,
    val quality: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Long? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val hasAudio: Boolean = false,
    val downloadMode: DownloadMode = DownloadMode.DIRECT,
    val headers: Map<String, String> = emptyMap(),
)
