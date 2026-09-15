package com.kuaixia.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ================= P8.2：bit_rate[] 档位元数据 =================

    private val BITRATE_1080 = "https://v3-dy.ixigua.com/aweme/v1/play/?video_id=v0300f&ratio=1080p&x-signature=hd1"
    private val BITRATE_720 = "https://v3-dy.ixigua.com/aweme/v1/play/?video_id=v0300f&ratio=720p&x-signature=hd2"

    private fun refs(
        url: String,
        gear: String? = null,
        width: Int? = null,
        height: Int? = null,
        bitrate: Double? = null,
    ) = VideoJsonMedia.collectRefs(
        listOf(
            VideoJsonRef(
                field = "play_addr",
                url = url,
                source = VideoJsonCandidate.SOURCE_BIT_RATE,
                gear = gear,
                width = width,
                height = height,
                bitrate = bitrate,
            ),
        ),
        emptyList(),
    )

    @Test
    fun bitRateRefCarriesGearHeightAndNormalizedBitrate() {
        // 8. bit_rate 候选必须带上 gear/width/height/码率（页面 bps → kbps 归一），且仍标为 bit_rate 来源
        val out = refs(BITRATE_1080, gear = "1080p", width = 1920, height = 1080, bitrate = 2_048_000.0).single()
        assertEquals(1080, out.height ?: -1)
        assertEquals(1920, out.width ?: -1)
        assertEquals(2048.0, out.bitrate ?: -1.0, 0.001)
        assertEquals("1080p", out.gear)
        assertEquals("play_addr", out.field)
        assertTrue(out.fromBitRate)
        assertFalse("非 playwm 仍只回 false（未证实无水印）", out.watermarkHint)
    }

    @Test
    fun explicitHeightWinsAndGearIsWhitelistFallback() {
        // 9. 真实 height 优先；缺失时仅用 gear_name 中的已知高度白名单兜底（白名单外不猜）
        assertEquals(1080, refs(BITRATE_1080, gear = "720p", height = 1080).single().height ?: -1)
        assertEquals(720, refs(BITRATE_720, gear = "normal_720_0").single().height ?: -1)
        assertEquals(1080, refs(BITRATE_1080, gear = "adapt_1080_1").single().height ?: -1)
        assertNull("gear 中非已知高度不得当作 height", refs(BITRATE_720, gear = "gear_9999_0").single().height)
    }

    @Test
    fun sameUrlIsMergedKeepingBitrateMetadata() {
        // 10. play_addr 与 bit_rate[i].play_addr 指向同一资源时：仍只出一条候选，但补齐档位元数据
        val out = VideoJsonMedia.collectRefs(
            listOf(
                VideoJsonRef(field = "play_addr", url = BITRATE_1080, source = "play_addr"),
                VideoJsonRef(
                    field = "play_addr",
                    url = BITRATE_1080,
                    source = VideoJsonCandidate.SOURCE_BIT_RATE,
                    gear = "1080p",
                    height = 1080,
                    bitrate = 3_000_000.0,
                ),
            ),
            emptyList(),
        )
        assertEquals("同 URL 只保留一条候选", 1, out.size)
        val only = out.single()
        assertEquals(1080, only.height ?: -1)
        assertEquals(3000.0, only.bitrate ?: -1.0, 0.001)
        assertTrue("合并后携带档位元数据 → 计为 bit_rate 候选", only.fromBitRate)
    }

    @Test
    fun capturedConflictAndSchemeRulesUnchangedForBitRateRefs() {
        // 11. P8.1 纪律对 bit_rate 候选同样生效：已捕获 URL 跳过、非 http(s) 拒绝
        assertTrue(
            VideoJsonMedia.collectRefs(
                listOf(VideoJsonRef(field = "play_addr", url = BITRATE_1080, source = "bit_rate", height = 1080)),
                listOf(BITRATE_1080),
            ).isEmpty(),
        )
        assertTrue(refs("blob:https://www.douyin.com/1a2b3c").isEmpty())
    }

    @Test
    fun bitRatePlayWmStillOnlyWatermarkHint() {
        // 12. bit_rate 候选若为 playwm：仍只标水印语义，绝不宣称无水印（也不提高可信度）
        val out = VideoJsonMedia.collectRefs(
            listOf(
                VideoJsonRef(
                    field = "play_addr",
                    url = PLAY,
                    source = VideoJsonCandidate.SOURCE_BIT_RATE,
                    gear = "1080p",
                    height = 1080,
                ),
            ),
            emptyList(),
        ).single()
        assertTrue(out.watermarkHint)
        assertEquals(1080, out.height ?: -1)
    }

    @Test
    fun legacyCollectHasNoMetadata() {
        // 13. 旧入口（无档位元数据）行为保持不变：height/width/bitrate 全为 null
        val out = VideoJsonMedia.collect(listOf("play_addr" to PLAY), emptyList()).single()
        assertNull(out.height)
        assertNull(out.width)
        assertNull(out.bitrate)
        assertFalse(out.fromBitRate)
    }
}
