package com.kuaixia.app.core.model

import androidx.annotation.StringRes
import com.kuaixia.app.R

/**
 * 解析方式（解析优先级策略）。
 *
 * 说明：
 * - 本地优先：yt-dlp → 失败 → 默认服务器
 * - 服务器优先：默认服务器 → 失败 → yt-dlp
 * - 仅本地：只 yt-dlp
 * - 仅服务器：只默认服务器
 *
 * 任何模式都不会自动无限切换服务器；服务器失败即提示。
 *
 * 展示文案走资源（[labelRes] / [descriptionRes]），由 UI 层翻译。
 */
enum class ParseMode(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    LOCAL_FIRST(R.string.parse_mode_local_first, R.string.parse_mode_local_first_desc),
    SERVER_FIRST(R.string.parse_mode_server_first, R.string.parse_mode_server_first_desc),
    LOCAL_ONLY(R.string.parse_mode_local_only, R.string.parse_mode_local_only_desc),
    SERVER_ONLY(R.string.parse_mode_server_only, R.string.parse_mode_server_only_desc),
}
