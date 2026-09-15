package com.kuaixia.app.data.model

/**
 * WebView 捕获到的真实图片资源（Phase 综合补丁 A）。
 *
 * 仅保存**实际捕获到的请求**，不按页面 ID 拼接、不猜 URL 结构。
 * - [mimeType] 为按 URL 扩展名/Accept 的**推断**值（WebView 拿不到响应头；真实格式以响应 Content-Type 为准）
 * - [httpHeaders] 仅保留非敏感（Referer/User-Agent/Accept 等）必要请求头；Cookie 不在此处，仍由 Cookie Jar 机制管理
 */
data class ImageResource(
    val url: String,
    /** 推断 MIME（可能为 null；下载后可用真实 Content-Type 校正）。 */
    val mimeType: String? = null,
    /** 展示/临时文件扩展名推断（如 jpeg/webp；null 时下载层按 mime/默认处理）。 */
    val extension: String? = null,
    /** 图片请求头（非敏感子集）。 */
    val httpHeaders: Map<String, String> = emptyMap(),
)
