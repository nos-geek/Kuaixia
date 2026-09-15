package com.kuaixia.app.data.model

import com.kuaixia.app.core.model.MediaType

/**
 * 统一解析结果模型（领域模型）。
 *
 * 本地 yt-dlp 解析与服务器解析最终都转换成这个模型，UI 只消费它，
 * 不感知数据来自本地还是服务器，也不感知 yt-dlp 的具体字段。
 */
data class VideoInfo(
    val id: String,
    val title: String? = null,
    val author: String? = null,
    val webpageUrl: String,
    val duration: Long? = null,
    val thumbnail: String? = null,
    val platform: String,
    val streams: List<StreamInfo> = emptyList(),
    /** 媒体类型（见 [MediaType]；WebView 实际捕获到图片资源时置 IMAGE/IMAGE_COLLECTION 等）。 */
    val mediaType: MediaType = MediaType.UNKNOWN,
    /** WebView 实际捕获到的图片资源（图集顺序=捕获顺序，已去重）。 */
    val imageItems: List<ImageResource> = emptyList(),
    /**
     * 低可信解析结果标记（默认 false；当前仅 WebView 视频线路会置 true）。
     *
     * true = 本次解析「有视频候选，但没有足够可信的候选」——典型场景：页面 JSON 唯一给出的
     * 视频直链是 playwm 水印地址（WebViewParser 侧 trustedVideoCount == 0）。
     *
     * 用途：**仅供解析编排决策**（低可信时先试 yt-dlp、失败再回退本结果），
     * 不参与任何质量排序、不进 UI、不影响下载语义。
     */
    val lowConfidence: Boolean = false,
)
