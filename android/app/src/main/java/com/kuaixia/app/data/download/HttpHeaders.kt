package com.kuaixia.app.data.download

import okhttp3.Request

/**
 * OkHttp 请求头安全添加工具。
 *
 * OkHttp 对 header name/value 有严格限制：name 必须为可见 ASCII（0x21–0x7E），
 * value 不得含控制字符或非 ASCII；违反会抛 [IllegalArgumentException]。
 * 所有「拿用户/yt-dlp 提供的 Map 设 header」的下载器都必须走这里：
 * 非法项跳过并记录，绝不因单个 header 让下载崩溃。
 */
object HttpHeaders {

    fun isNameValid(name: String): Boolean = name.all { it in '\u0021'..'\u007e' }

    fun isValueValid(value: String): Boolean = value.all { it > '\u001f' && it < '\u007f' }

    /**
     * @param onSkip 当某 header 被跳过时回调（调用方负责记日志）。
     * @return true=已设置；false=因非法被跳过。
     */
    fun addSafely(builder: Request.Builder, name: String, value: String, onSkip: (String) -> Unit): Boolean = when {
        name.isBlank() -> {
            onSkip("跳过空白 header name")
            false
        }
        !isNameValid(name) -> {
            onSkip("跳过非法 header name=${name.take(80)}（含非可见 ASCII）")
            false
        }
        !isValueValid(value) -> {
            onSkip("跳过非法 header value name=$name（含控制字符/非 ASCII，长度=${value.length}）")
            false
        }
        else -> try {
            builder.header(name, value)
            true
        } catch (e: IllegalArgumentException) {
            onSkip("设置 header 失败 name=$name err=${e.message}")
            false
        }
    }
}
