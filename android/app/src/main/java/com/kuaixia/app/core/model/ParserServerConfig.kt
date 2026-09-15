package com.kuaixia.app.core.model

import kotlinx.serialization.Serializable

/** 解析服务器配置。允许保存多个服务器，可启用/禁用/修改/删除/设默认。 */
@Serializable
data class ParserServerConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
)
