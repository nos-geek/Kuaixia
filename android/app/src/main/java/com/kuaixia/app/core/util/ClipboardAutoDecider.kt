package com.kuaixia.app.core.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * 自动解析剪贴板的「决策」纯逻辑（无 Android 依赖，可 JVM 单测）。
 *
 * 决策只发生在「设置已就绪」之后：调用方必须先取得真正的 autoParse 值，
 * 严禁用「未加载时的默认 false」提前决策。
 */
object ClipboardAutoDecider {

    enum class Decision {
        /** 开关关闭：不自动解析（可展示手动提示）。 */
        AUTO_DISABLED,

        /** 剪贴板无有效平台 URL。 */
        NO_URL,

        /** 同一 canonical URL 正在自动解析中。 */
        IN_FLIGHT,

        /** 该 canonical URL 已自动解析过（含失败），不再自动重试。 */
        DUPLICATE,

        /** 该 URL 平台不受支持（如图片 CDN / unknown）→ 不自动解析（手动解析不受影响）。 */
        UNSUPPORTED,

        /** 新 URL 且允许 → 自动解析。 */
        TRIGGER,
    }

    fun decide(
        autoEnabled: Boolean,
        canonicalUrl: String?,
        inFlightCanonical: String?,
        lastAutoParsedCanonical: String?,
        /** 平台是否受支持（douyin/kuaishou/xhs/bilibili/youtube 等）；unknown/CDN 不自动触发。 */
        supported: Boolean = true,
    ): Decision {
        if (!autoEnabled) return Decision.AUTO_DISABLED
        if (canonicalUrl == null || canonicalUrl.isBlank()) return Decision.NO_URL
        if (!supported) return Decision.UNSUPPORTED
        if (canonicalUrl == inFlightCanonical) return Decision.IN_FLIGHT
        if (canonicalUrl == lastAutoParsedCanonical) return Decision.DUPLICATE
        return Decision.TRIGGER
    }

    /**
     * 等待设置流真正产出一个值后再决策（DataStore 冷流首个发射 = 就绪）。
     * 用于测试「设置尚未就绪时必须等待、不得用默认 false」。
     */
    suspend fun decideWhenReady(
        autoEnabledFlow: Flow<Boolean>,
        canonicalUrl: String?,
        inFlightCanonical: String?,
        lastAutoParsedCanonical: String?,
        supported: Boolean = true,
    ): Decision = decide(autoEnabledFlow.first(), canonicalUrl, inFlightCanonical, lastAutoParsedCanonical, supported)
}
