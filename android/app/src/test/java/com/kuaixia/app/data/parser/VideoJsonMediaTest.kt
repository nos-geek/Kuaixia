package com.kuaixia.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P8.1：VIDEO 页 PAGE_JSON 视频候选纯逻辑测试（VideoJsonMedia.collect）。
 * 纪律：只消费真实 URL、不改写、playwm 只标水印语义不冒充无水印、无候选 → 空（fallback）。
 */
class VideoJsonMediaTest {

    private val PLAY = "https://p3-sign.douyinpic.com/aweme/v1/playwm/?video_id=v0300f&x-signature=abc"
    private val PLAY2 = "https://p9-sign.douyinpic.com/aweme/v1/playwm/?video_id=v0300f&x-signature=def"
    private val DOWNLOAD = "https://v3-dy.ixigua.com/aweme/v1/play/?video_id=v0300f&x-signature=ghi"

    @Test
    fun normalPlayAddrRefsAreCollected() {
        // 1. 正常 video.play_addr.url_list 多条 → 全部保留（field=play_addr）
        val out = VideoJsonMedia.collect(listOf("play_addr" to PLAY, "play_addr" to PLAY2), emptyList())
        assertEquals(2, out.size)
        assertTrue(out.all { it.field == "play_addr" })
        assertEquals(PLAY, out[0].url)
    }

    @Test
    fun missingPlayAddrYieldsEmpty() {
        // 2. video 无 play_addr（refs 空）→ 无候选 → 调用方保持 yt-dlp fallback
        assertTrue(VideoJsonMedia.collect(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun emptyUrlListYieldsEmpty() {
        // 3. url_list 为空 → 空（JS 端不会产出 refs；此处兜底验证）
        assertTrue(VideoJsonMedia.collect(listOf("play_addr" to ""), emptyList()).isEmpty())
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        // 4. blob:/data: 等非 http(s) 一律拒绝
        val out = VideoJsonMedia.collect(
            listOf(
                "play_addr" to PLAY,
                "play_addr" to "blob:https://www.douyin.com/1a2b3c",
                "play_addr" to "data:video/mp4;base64,AAAA",
            ),
            emptyList(),
        )
        assertEquals(1, out.size)
        assertEquals(PLAY, out[0].url)
    }

    @Test
    fun candidateCarriesVideoFieldForClassifier() {
        // 5. collect 产出的候选由接入层构造 MediaCandidate(VIDEO) —— 此处验证 url/field 完整可交付
        val out = VideoJsonMedia.collect(listOf("play_addr" to PLAY, "download_addr" to DOWNLOAD), emptyList())
        assertEquals(2, out.size)
        assertEquals("play_addr", out[0].field)
        assertEquals("download_addr", out[1].field)
    }

    @Test
    fun capturedConflictIsDeduplicatedAndEmptyMeansFallback() {
        // 6. 与已捕获候选 URL 重复 → 跳过；全部重复 → 空（保持 WebView NO_VIDEO → yt-dlp）
        val out = VideoJsonMedia.collect(listOf("play_addr" to PLAY), listOf(PLAY))
        assertTrue(out.isEmpty())
    }

    @Test
    fun playwmIsNeverAutoMarkedClean() {
        // 7. 可疑 playwm URL 必须带水印语义提示；普通 URL 的 false 也仅是「未证实」，不是「无水印」断言
        val playWm = VideoJsonMedia.collect(listOf("play_addr" to PLAY), emptyList()).single()
        assertTrue("playwm 必须 watermarked=true", playWm.watermarkHint)
        val plain = VideoJsonMedia.collect(listOf("download_addr" to DOWNLOAD), emptyList()).single()
        assertFalse("非 playwm 只回 false（未证实 clean），调用方不得宣称无水印", plain.watermarkHint)
    }
}
