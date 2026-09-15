package com.kuaixia.app.core.network.dto

import kotlinx.serialization.Serializable

/** 解析请求体。 */
@Serializable
data class ParseRequest(
    val url: String,
)
