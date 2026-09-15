package com.kuaixia.app.core.model

import kotlinx.serialization.Serializable

/** 解析结果。字段名与 Server 端 Pydantic 模型一致。 */
@Serializable
data class MediaParseResult(
    val platform: String,
    val originalUrl: String,
    val title: String? = null,
    val author: String? = null,
    val thumbnail: String? = null,
    val duration: Long? = null,
    val mediaType: String,
    val streams: List<MediaStream> = emptyList(),
)
