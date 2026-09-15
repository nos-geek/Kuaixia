package com.kuaixia.app.data.parser.douyin

import com.kuaixia.app.data.format.FormatDisplayGrouper
import com.kuaixia.app.data.model.StreamInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖音 PC 形态映射器单测（纯 JVM）。
 *
 * 覆盖：19 档解析、空 `bit_rate`、非数组 `bit_rate`、`play_addr` 合并、height 映射、
 * quality 一致性（与 UI 分组 label 口径一致）、URL 零改写与 playwm 保守语义。
 */
class DouyinPcDetailMapperTest {

    private val u1080 = "https://v3-dy.example/aweme/v1/play/?video_id=v0200fg&ratio=1080p&sig=a"
    private val u720 = "https://v3-dy.example/aweme/v1/play/?video_id=v0200fg&ratio=720p&sig=b"
    private val uWm = "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=v0200fg&ratio=720p"

    private fun gearJson(name: String, w: Int, h: Int, bps: Double, urls: List<String>) =
        JSONObject().put("gear", name).put("w", w).put("h", h).put("bitrate", bps).put("size", 1234)
            .put("urls", JSONArray(urls))

    private fun addrJson(w: Int, h: Int, urls: List<String>) =
        JSONObject().put("w", w).put("h", h).put("size", 999).put("urls", JSONArray(urls))

    private fun payload(
        gears: List<JSONObject> = emptyList(),
        play: JSONObject? = null,
        download: JSONObject? = null,
    ): String = JSONObject()
        .put("ok", true)
        .put("reason", "")
        .put("title", "笔记本电脑也能本地跑大模型？ #ai")
        .put("cover", "https://p.example/cover.jpg")
        .put("videoId", "v0200fg10000dabir67og65vrsvi0190")
        .put("durationMs", 12345)
        .put("captureCount", 56)
        .put("detailReq", 1)
        .put("gears", JSONArray(gears))
        .apply { if (play != null) put("play", play); if (download != null) put("download", download) }
        .toString()

    @Test
    fun parses19GearsAndKeepsMetadata() {
        // 1. 19 档（含 1080P/720P 与最高档 3840）→ 档位元数据完整保留，bps 归一为 kbps
        val gears = (0 until 19).map { i ->
            val h = if (i == 0) 3840 else if (i < 4) 1920 else 1280 - i * 8
            // 每档使用**各自唯一**的 URL（真实响应即如此；共用 URL 会被 collectRefs 正确去重合并）
            val url = "https://v3-dy.example/aweme/v1/play/?video_id=v0200fg&gear=$i&sig=s$i"
            gearJson("normal_${h}_$i", 1080, h, if (i == 1) 616_000.0 else 400_000.0 + i * 1_000, listOf(url))
        }
        val capture = DouyinPcDetailMapper.parseCompact(payload(gears = gears, play = addrJson(1080, 1920, listOf(u1080))))
        assertNotNull("ok=true 时必须解析成功", capture)
        assertEquals(19, capture!!.gears.size)
        assertEquals(1, capture.detailHits)

        val candidates = DouyinPcDetailMapper.toCandidates(capture)
        assertTrue("候选数 > 0", candidates.isNotEmpty())
        assertEquals("最高档 height", 3840, candidates.mapNotNull { it.height }.maxOrNull())
        assertTrue("必须保留 1920（1080P 竖屏）档", candidates.any { it.height == 1920 })
        // bps → kbps 归一（616000 bps → 616 kbps；与移动链路同一实现）
        assertTrue(candidates.any { (it.bitrate ?: 0.0) > 600 && (it.bitrate ?: 0.0) < 620 })
        assertTrue("非 playwm 候选必须被标记为可信", candidates.any { !it.watermarkHint })
    }

    @Test
    fun emptyBitRateArrayFallsBackToPlayAddr() {
        // 2. bit_rate 空数组 → 仅 play_addr 单档（height 取自 play_addr），不崩、不丢
        val capture = DouyinPcDetailMapper.parseCompact(
            payload(gears = emptyList(), play = addrJson(1080, 1920, listOf(u1080))),
        )
        assertNotNull(capture)
        assertTrue(capture!!.gears.isEmpty())
        val candidates = DouyinPcDetailMapper.toCandidates(capture)
        assertEquals(1, candidates.size)
        assertEquals(1920, candidates[0].height)
        assertEquals(u1080, candidates[0].url)
    }

    @Test
    fun nonArrayBitRateIsIgnoredGracefully() {
        // 3. bit_rate 非数组（对象形态）→ 视为无档位；仍可从 play_addr 产出候选，绝不抛异常
        val json = JSONObject()
            .put("ok", true).put("reason", "").put("title", "t").put("cover", "")
            .put("videoId", "v0200fg").put("durationMs", 1000)
            .put("captureCount", 10).put("detailReq", 1)
            .put("gears", JSONArray())
            .put("bit_rate", JSONObject().put("1080p", JSONObject()))
            .put("play", addrJson(1080, 1920, listOf(u1080)))
            .toString()
        val capture = DouyinPcDetailMapper.parseCompact(json)
        assertNotNull(capture)
        assertTrue(capture!!.gears.isEmpty())
        assertEquals(1, DouyinPcDetailMapper.toCandidates(capture).size)
    }

    @Test
    fun duplicateUrlMergesGearMetadata() {
        // 4. 同一 URL 同时出现在 bit_rate[i].play_addr 与 play_addr → 只保留一条，且档位元数据不丢
        val capture = DouyinPcDetailMapper.parseCompact(
            payload(gears = listOf(gearJson("normal_1080_0", 1080, 1920, 500_000.0, listOf(u1080))), play = addrJson(1080, 1920, listOf(u1080))),
        )!!
        val candidates = DouyinPcDetailMapper.toCandidates(capture)
        assertEquals(1, candidates.size)
        assertEquals(1920, candidates[0].height)
        assertTrue("合并后仍标记为 bit_rate 来源", candidates[0].fromBitRate)
        assertEquals("normal_1080_0", candidates[0].gear)
    }

    @Test
    fun qualityMatchesGrouperLabel() {
        // 5. quality 一致性：height 已知 → "${height}P" 且与 UI 分组 label 完全相同
        assertEquals("1920P", DouyinPcDetailMapper.qualityOf(1920, u720))
        assertEquals("3840P", DouyinPcDetailMapper.qualityOf(3840, u720))
        // height 未知 → 保持旧 URL 推断兜底
        assertEquals("720P", DouyinPcDetailMapper.qualityOf(null, u720))
        assertEquals("1080P", DouyinPcDetailMapper.qualityOf(null, u1080))
        assertNull(DouyinPcDetailMapper.qualityOf(null, "https://x.example/v.mp4"))

        val groups = FormatDisplayGrouper.group(
            listOf(
                StreamInfo(
                    formatId = "webview-0",
                    url = u1080,
                    quality = DouyinPcDetailMapper.qualityOf(1920, u1080),
                    ext = "mp4",
                    mimeType = "video/mp4",
                    height = 1920,
                ),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("1920P", groups[0].label)
        assertEquals(DouyinPcDetailMapper.qualityOf(1920, u1080), groups[0].label)
    }

    @Test
    fun urlsAreNeverRewrittenAndPlaywmStaysConservative() {
        // 6. 禁止 URL 改写 / playwm 替换：原样保留；playwm 只标保守语义
        val capture = DouyinPcDetailMapper.parseCompact(
            payload(gears = listOf(gearJson("normal_720_0", 1280, 720, 300_000.0, listOf(uWm))), play = null),
        )!!
        val candidates = DouyinPcDetailMapper.toCandidates(capture)
        assertEquals(1, candidates.size)
        assertEquals("URL 必须逐字保留", uWm, candidates[0].url)
        assertTrue("playwm 必须带保守水印语义", candidates[0].watermarkHint)
        assertFalse("playwm 不得被计为可信候选", !candidates[0].watermarkHint)
    }

    @Test
    fun okFalseYieldsNull() {
        // 7. ok=false（未捕获/无 video/解析错误）→ null（调用方据此回退旧链路）
        val json = JSONObject().put("ok", false).put("reason", "no_capture").put("gears", JSONArray()).toString()
        assertNull(DouyinPcDetailMapper.parseCompact(json))
        assertNull(DouyinPcDetailMapper.parseCompact(null))
        assertNull(DouyinPcDetailMapper.parseCompact("not json"))
    }
}
