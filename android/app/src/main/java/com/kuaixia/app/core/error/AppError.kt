package com.kuaixia.app.core.error

/**
 * 统一错误码（与 Server 端一致）。UI 显示中文，不显示 StackTrace。
 *
 * 解析相关错误分类（Phase 2.5 细化）：
 * - INVALID_URL           链接无效
 * - UNSUPPORTED_PLATFORM  平台不支持 / 链接无法识别
 * - PLATFORM_BLOCKED      平台风控拦截（如 B 站 412、抖音需 Cookie）
 * - COOKIE_REQUIRED       平台要求活跃 Cookie 会话（如抖音 Fresh cookies）
 * - NETWORK_ERROR         网络不可达
 * - TIMEOUT               解析超时
 * - LOGIN_REQUIRED        需要登录
 * - ACCESS_DENIED         地区限制 / 会员权限
 * - MEDIA_NOT_FOUND       视频不存在或已删除
 * - UNKNOWN_ERROR         未归类错误
 */
object ErrorCode {
    const val INVALID_URL = "INVALID_URL"
    const val UNSUPPORTED_PLATFORM = "UNSUPPORTED_PLATFORM"
    const val PLATFORM_BLOCKED = "PLATFORM_BLOCKED"
    const val COOKIE_REQUIRED = "COOKIE_REQUIRED"
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val TIMEOUT = "TIMEOUT"
    const val LOGIN_REQUIRED = "LOGIN_REQUIRED"
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val MEDIA_NOT_FOUND = "MEDIA_NOT_FOUND"
    const val UNKNOWN_ERROR = "UNKNOWN_ERROR"

    // 服务器 / 本地组件错误
    const val SERVER_ERROR = "SERVER_ERROR"
    const val PARSER_ERROR = "PARSER_ERROR"

    // 客户端本地错误（非服务器返回）
    const val NO_SERVER = "NO_SERVER"
    const val CONNECTION_FAILED = "CONNECTION_FAILED"
    const val YTDLP_NOT_AVAILABLE = "YTDLP_NOT_AVAILABLE"
    const val YTDLP_START_FAILED = "YTDLP_START_FAILED"

    // WebView 嗅探（Phase 5）
    const val WEBVIEW_TIMEOUT = "WEBVIEW_TIMEOUT"
    const val WEBVIEW_CANCELLED = "WEBVIEW_CANCELLED"
    const val WEBVIEW_LOAD_FAILED = "WEBVIEW_LOAD_FAILED"
}

/**
 * 带中文提示的应用异常。
 *
 * @param code   机器可读错误码（用于日志 / 后续 UI 分支）
 * @param message 用户可读中文提示（UI 展示）
 * @param detail  开发调试详情（如 yt-dlp 原始 stderr）。仅用于日志，UI 不展示。
 */
class AppException(
    val code: String,
    override val message: String,
    val detail: String? = null,
) : Exception(message)
