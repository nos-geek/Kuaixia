package com.kuaixia.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageJsonMergeTest {

    private val KEY1 = "oMAAyGIIEJgVD3Nef2LQalCDeuXQsBA9GFb7Ag"
    private val KEY2 = "o4AAyEIIEJgVD3Nef2LQajCDfuXQ8BA9GFb7Ag"

    private fun jsonImg(seq: Int, key: String, vararg urls: String) =
        PageJsonMerge.JsonImage(seq, key, urls.toList())

    private val HD1440 = "https://p3-pc-sign.douyinpic.com/obj/$KEY1~tplv-dy-lqen-new:1440:2560:q80.webp?s=1"
    private val HD1440b = "https://p9-pc-sign.douyinpic.com/obj/$KEY1~tplv-dy-lqen-new:1440:2560:q80.jpeg?s=2"
    private val DISP540 = "https://p3-pc-sign.douyinpic.com/obj/$KEY1~tplv-dy-resize-walign-adapt-aq:540:q75.webp?x=1"
    private val DISP480 = "https://p26-sign.douyinpic.com/obj/$KEY1~tplv-dy-shrink:480:853.webp?x=2"
    private val WATER1440 = "https://p3-pc-sign.douyinpic.com/obj/$KEY1~tplv-dy-lqen-new-water:1440:2560:q80.webp?w=1"
    private val KEY2_HD = "https://p3-pc-sign.douyinpic.com/obj/$KEY2~tplv-dy-lqen-new:1440:2560:q80.webp?z=9"

    @Test
    fun jsonHighResCandidateEntersUnifiedChain() {
        val merged = PageJsonMerge.merge(
            listOf(jsonImg(0, KEY1, HD1440)),
            emptyList(),
        )
        assertEquals(1, merged.size)
        assertEquals(HD1440, merged[0].url)
        assertEquals("webp", merged[0].extension)
        assertEquals("image/webp", merged[0].mimeType)
    }

    @Test
    fun sameObjKey4805401440CollapsesToOneImage() {
        val merged = PageJsonMerge.merge(
            listOf(jsonImg(0, KEY1, HD1440)),
            listOf(
                PageJsonMerge.Captured(DISP540, "NETWORK"),
                PageJsonMerge.Captured(DISP480, "DOM_SRC"),
            ),
        )
        // 同一张作品图：PAGE_JSON(1440) + 捕获 540/480 → 仅 1 张
        assertEquals(1, merged.size)
        assertEquals(HD1440, merged[0].url)
    }

    @Test
    fun prefers1440Over540And480() {
        // 即使 540 先进捕获、且同 key，组内选优应选 1440 PAGE_JSON
        val merged = PageJsonMerge.merge(
            listOf(jsonImg(0, KEY1, HD1440, HD1440b)),
            listOf(PageJsonMerge.Captured(DISP540, "CAPTURED")),
        )
        assertEquals(1, merged.size)
        assertTrue(merged[0].url.contains("lqen-new:1440:2560"))
        assertEquals("webp", merged[0].extension)
    }

    @Test
    fun waterUrlNotChosenByDefault() {
        // 防御：即便 json.urls 混入 water 档（download_url_list 不应传入，这里模拟误传），
        // watermarkHint 识别 -water: → 带水印档被压，非水 1440 胜出
        val merged = PageJsonMerge.merge(
            listOf(jsonImg(0, KEY1, WATER1440, HD1440)),
            emptyList(),
        )
        assertEquals(1, merged.size)
        assertEquals(HD1440, merged[0].url)
    }

    @Test
    fun keepsJsonImageOrder() {
        val merged = PageJsonMerge.merge(
            listOf(
                jsonImg(0, KEY1, HD1440),
                jsonImg(1, KEY2, KEY2_HD),
            ),
            emptyList(),
        )
        assertEquals(2, merged.size)
        assertTrue(merged[0].url.contains(KEY1))
        assertTrue(merged[1].url.contains(KEY2))
    }

    @Test
    fun emptyJsonYieldsEmptySoCallerFallsBack() {
        // PAGE_JSON 缺失/为空 → merge 返回空 → 调用方回退 NETWORK/DOM 旧链（解析层保证）
        assertTrue(PageJsonMerge.merge(emptyList(), emptyList()).isEmpty())
        assertTrue(
            PageJsonMerge.merge(
                emptyList(),
                listOf(PageJsonMerge.Captured(DISP540, "CAPTURED")),
            ).isEmpty(),
        )
    }

    @Test
    fun capturedOfDifferentKeyNotMixedIntoJsonGroup() {
        // 不同 obj key 的捕获候选不得混入该组（防串图）
        val other = "https://p3-pc-sign.douyinpic.com/obj/OTHERKEY123~tplv-dy-resize-walign-adapt-aq:540:q75.webp"
        val merged = PageJsonMerge.merge(
            listOf(jsonImg(0, KEY1, HD1440)),
            listOf(PageJsonMerge.Captured(other, "NETWORK")),
        )
        assertEquals(1, merged.size)
        assertEquals(HD1440, merged[0].url)
    }

    @Test
    fun extractObjKeyHandlesTplvAndRawForms() {
        assertEquals(KEY1, PageJsonMerge.extractObjKey(HD1440))
        assertEquals(KEY1, PageJsonMerge.extractObjKey(DISP540))
        assertEquals(KEY1, PageJsonMerge.extractObjKey(DISP480))
        assertEquals(KEY1, PageJsonMerge.extractObjKey(WATER1440))
        // 无 tplv 的原图类路径保留目录+末段（不同 key 体系，不强行与展示 key 匹配）
        val raw = "https://p3-pc-sign.douyinpic.com/obj/tos-cn-i-tsj2vxp0zn/2d8dc1e2bb8a417cab4130df2d10e478"
        assertEquals("tos-cn-i-tsj2vxp0zn/2d8dc1e2bb8a417cab4130df2d10e478", PageJsonMerge.extractObjKey(raw))
        assertNull(PageJsonMerge.extractObjKey(null))
        assertNull(PageJsonMerge.extractObjKey(""))
    }

    @Test
    fun watermarkHintRecognizesDashWaterTemplate() {
        // lqen-new-water 属水印档 → true；同名无水印 lqen-new → unknown（不猜无水印也绝不误判无水印）
        assertEquals(true, ImageUrlHints.watermarkHint(WATER1440))
        assertNull(ImageUrlHints.watermarkHint(HD1440))
        assertEquals(true, ImageUrlHints.watermarkHint("https://x/y~tplv-dy-lqen-new-water:1080:1920.webp"))
    }

    @Test
    fun sizeHintDoesNotMistakeWordTrailingWAsWidthToken() {
        // 回归：`lqen-new:1440:2560` 的尾字母 w 不得被当作 width 标记（否则 height=null → 分辨率归零）
        assertEquals(1440 to 2560, ImageUrlHints.sizeFromUrl(HD1440))
        assertEquals(1440 to 2560, ImageUrlHints.sizeFromUrl(WATER1440))
        assertEquals(480 to 853, ImageUrlHints.sizeFromUrl(DISP480))
    }

    @Test
    fun slidesInfoAwemeImagesBeatsWaterV2InSameGroup() {
        // P0：slidesinfo url_list 同时含 water-v2 与 aweme-images 档 → 选 aweme-images（无水印）
        val aweme = "https://p5-sign.douyinpic.com/obj/$KEY1~tplv-dy-aweme-images:q75.webp?x=1"
        val water = "https://p96-sign.douyinpic.com/obj/$KEY1~tplv-dy-water-v2:1440:1920.webp?x=2"
        val merged = PageJsonMerge.merge(
            listOf(jsonImg(0, KEY1, water, aweme)),
            emptyList(),
        )
        assertEquals(1, merged.size)
        assertEquals(aweme, merged[0].url)
    }
}
