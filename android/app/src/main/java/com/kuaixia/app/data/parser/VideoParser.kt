package com.kuaixia.app.data.parser

import com.kuaixia.app.data.model.VideoInfo

/**
 * 统一解析接口。
 *
 * 所有解析器（本地 yt-dlp、服务器，以及未来的 WebView 嗅探 / 平台专用 Parser /
 * 自定义服务器 Parser）都实现该接口，产出统一的 [VideoInfo]。
 * UI 与 [ParserManager] 只依赖此接口，不绑定任何具体解析引擎。
 *
 * 扩展点（设计预留，尚未实现）：
 * - [support]：声明该解析器能否处理某个 URL。未来 ParserManager 可据此在多解析器间路由，
 *   而不必为每种解析器写分支。默认返回 true 表示「均可尝试」，避免破坏现有双解析器编排。
 * - 未来可加入 [WebViewParser]（网页嗅探）、[ServerCustomParser]（自定义协议服务器）、
 *   平台专用 Parser（如只处理抖音），实现各自的 [support] + [parse] 即可。
 */
interface VideoParser {

    /**
     * 该解析器是否支持处理给定 URL。
     *
     * 默认 true（所有解析器都可尝试）；平台专用 / 能力受限的解析器可覆写，
     * 让 ParserManager 按能力路由。
     */
    fun support(url: String): Boolean = true

    suspend fun parse(url: String): Result<VideoInfo>
}
