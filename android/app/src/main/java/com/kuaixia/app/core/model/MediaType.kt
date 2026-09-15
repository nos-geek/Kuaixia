package com.kuaixia.app.core.model

import androidx.annotation.StringRes
import com.kuaixia.app.R

/**
 * 作品媒体类型（综合补丁 §9 预留）。
 *
 * 现状说明（诚实）：当前本地线路是 yt-dlp 2026.08.19 的 `--dump-single-json`，
 * 抖音图片/图集/实况/动图在结果里的**真实字段形态尚未取样验证**（缺真实 dump-json 样例），
 * 因此 [VideoInfo.mediaType] 目前对所有结果保持 [UNKNOWN]；
 * 待拿到抖音图集/实况/动图的真实 JSON 后再接线分类与对应下载（不做凭空捏造的 extractor）。
 *
 * 枚举为未来下载任务系统统一分发预留；展示文案走资源（[labelRes]），由 UI 层翻译。
 */
enum class MediaType(@StringRes val labelRes: Int) {
    /** 普通音视频作品。 */
    VIDEO(R.string.media_video),

    /** 单张图片。 */
    IMAGE(R.string.media_image),

    /** 多图图集。 */
    IMAGE_COLLECTION(R.string.media_image_collection),

    /** 实况照片（静态图 + 动态视频段）。 */
    LIVE_PHOTO(R.string.media_live_photo),

    /** GIF / 动画 WebP / 动画 AVIF / APNG 等动态图片。 */
    ANIMATED_IMAGE(R.string.media_animated_image),

    /** 未知（未识别）。 */
    UNKNOWN(R.string.media_unknown),
}
