package com.kuaixia.app.core.model

import kotlinx.serialization.Serializable

/** 解析服务器集合 + 默认服务器。持久化到 DataStore。 */
@Serializable
data class ServerSettings(
    val servers: List<ParserServerConfig> = emptyList(),
    val defaultServerId: String? = null,
) {
    /** 解析当前生效的服务器：优先默认服务器，否则第一个启用的服务器。 */
    fun currentServer(): ParserServerConfig? {
        defaultServerId?.let { id ->
            servers.firstOrNull { it.id == id && it.enabled }?.let { return it }
        }
        return servers.firstOrNull { it.enabled }
    }
}

/**
 * 应用设置（当前生效的解析服务器地址）。
 * 对应需求：不要写死服务器地址，用户可在设置中自行填写。
 */
data class AppSettings(
    val parserServerUrl: String,
)
