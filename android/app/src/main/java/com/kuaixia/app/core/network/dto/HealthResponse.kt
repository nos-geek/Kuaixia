package com.kuaixia.app.core.network.dto

import kotlinx.serialization.Serializable

/** 健康检查响应。 */
@Serializable
data class HealthResponse(
    val status: String,
    val name: String,
    val version: String,
    val apiVersion: String,
)
