package com.kuaixia.app.core.util

import java.net.URI
import java.util.Locale

/**
 * 服务器地址规范化与校验（需求：自动补 /、去掉重复 /、校验 URI、仅允许 http/https）。
 */
object ServerUrl {

    private val duplicateSlashes = Regex("/{2,}")

    /**
     * 规范化服务器地址，返回可直接作为 Retrofit baseUrl 的字符串（以 / 结尾）。
     * 非法输入返回 null。
     *
     * 规则：
     *  - 无 scheme 时自动补 http://
     *  - 仅允许 http / https，拒绝 file:// content:// 等本地协议
     *  - 去掉路径中重复的 /
     *  - 保证末尾有 /
     */
    fun normalize(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        var s = raw
        if (!s.startsWith("http://", ignoreCase = true) &&
            !s.startsWith("https://", ignoreCase = true)
        ) {
            s = "http://$s"
        }

        val uri = try {
            URI(s)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""

        val path = (uri.path ?: "").replace(duplicateSlashes, "/").trimEnd('/')

        return "$scheme://$host$port$path/"
    }

    /** 是否为合法的媒体链接（用于首页输入校验）。 */
    fun isHttpUrl(input: String): Boolean {
        val raw = input.trim()
        if (raw.isEmpty()) return false
        return runCatching {
            val uri = URI(raw)
            (uri.scheme?.lowercase(Locale.ROOT) == "http" ||
                uri.scheme?.lowercase(Locale.ROOT) == "https") && uri.host != null
        }.getOrDefault(false)
    }
}
