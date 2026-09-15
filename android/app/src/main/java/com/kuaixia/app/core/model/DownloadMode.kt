package com.kuaixia.app.core.model

import kotlinx.serialization.Serializable

/** 下载模式。默认 DIRECT，只有 Android 无法直连时才用 SERVER_PROXY。 */
@Serializable
enum class DownloadMode {
    DIRECT,
    SERVER_PROXY,
}
