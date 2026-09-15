package com.kuaixia.app.core.network.dto

import kotlinx.serialization.Serializable

/** 统一错误响应体。code 与 Server 端一致。 */
@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
)
