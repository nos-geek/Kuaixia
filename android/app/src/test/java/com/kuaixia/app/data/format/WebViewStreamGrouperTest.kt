package com.kuaixia.app.data.format

import com.kuaixia.app.data.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8.1 回归修复测试：WebView/页面 JSON 直链（无 vcodec、URL 无 .mp4 后缀）必须能形成可下载视频格式；
 * audio-only 与 vcodec=none 仍被排除；「残缺结果」不得通过缓存准入判据。
 */
class WebViewStreamGrouperTest {

    /** 模拟 P8.1 PAGE_JSON 候选经既有 WebView 链路构造出的 StreamInfo。 */
    private fun webViewPlayWmStream(
        url: String = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0300&ratio=720p&line=0",
    ) = StreamInfo(
        formatId = "webview-1",
        url = url,
        quality = null,
        ext = "mp4",              // 既有 extFor(VIDEO) 结果
        mimeType = "video/mp4",   // P8.1 与 classify() 默认一致
        vcodec = null,            // WebView 无法得知编码
        acodec = null,
        hasAudio = true,
        protocol = "http",
    )

    @Test
    fun webViewDirectLinkWithoutMp4SuffixStillGroups() {
        // 1/2/3/5：URL 无 .mp4 后缀也能识别为视频；formatGroups 必须非 0；videoUrl（url）不丢失
        val groups = FormatDisplayGrouper.group(listOf(webViewPlayWmStream()))
        assertEquals("formatGroups 不得因 PAGE_JSON 候选变为 0", 1, groups.size)
        val rep = groups.first().representative
        assertTrue(rep.url.startsWith("https://aweme.snssdk.com/aweme/v1/playwm/"))
        assertEquals("mp4", rep.ext)
        assertTrue(rep.mimeType!!.startsWith("video/"))
        assertNull(groups.first().height)
        assertEquals(FormatDisplayGrouper.OTHER_LABEL, groups.first().label)
    }

    @Test
    fun audioOnlyIsStillExcluded() {
        // 4：audio-only（mime 以 audio/ 开头）不得被当成视频
        val audio = StreamInfo(
            formatId = "webview-2",
            url = "https://example.com/audio/only.mp4",
            ext = "mp4",
            mimeType = "audio/mp4",
            vcodec = null,
            acodec = "mp4a",
        )
        assertTrue(FormatDisplayGrouper.group(listOf(audio)).isEmpty())
    }

    @Test
    fun noneVideoCodecStillExcluded() {
        // vcodec 显式 none → 排除（原语义保持）
        val noneCodec = webViewPlayWmStream().copy(vcodec = "none")
        assertTrue(FormatDisplayGrouper.group(listOf(noneCodec)).isEmpty())
    }

    @Test
    fun knownCodecBehaviourUnchanged() {
        // 回归：yt-dlp 风格流（vcodec 有值 + height）仍按高度分组为 1080P
        val yt = StreamInfo(
            formatId = "137",
            url = "https://x/video.mp4",
            ext = "mp4",
            mimeType = "video/mp4",
            height = 1080,
            vcodec = "avc1.640028",
            acodec = "none",
            bitrate = 2500.0,
        )
        val groups = FormatDisplayGrouper.group(listOf(yt))
        assertEquals(1, groups.size)
        assertEquals("1080P", groups.first().label)
        assertEquals(1080, groups.first().height)
    }

    @Test
    fun completenessRejectsIncompleteVideoResult() {
        // 6：残缺结果不得进入成功缓存（判据与 UI 分组口径一致）
        assertFalse(ResultCompleteness.hasDownloadableVideo(emptyList()))
        assertFalse(
            ResultCompleteness.hasDownloadableVideo(
                listOf(webViewPlayWmStream().copy(vcodec = "none")),
            ),
        )
        assertTrue(ResultCompleteness.hasDownloadableVideo(listOf(webViewPlayWmStream())))
    }
}
