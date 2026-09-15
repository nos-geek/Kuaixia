package com.kuaixia.app.core.model

import androidx.annotation.StringRes

/**
 * UI 文本：ViewModel 不保存已翻译文本，只保存「资源 ID + 参数」或不可翻译的动态值，
 * 由 UI 层（Composable）负责最终翻译，保证语言切换即时生效。
 */
sealed interface UiText {

    /** 可资源化文本：[id] 指向 strings.xml，[args] 为格式化参数。 */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** 不可翻译的动态文本（如系统/网络异常 message、服务端返回文案）。 */
    data class Dynamic(val value: String) : UiText
}
