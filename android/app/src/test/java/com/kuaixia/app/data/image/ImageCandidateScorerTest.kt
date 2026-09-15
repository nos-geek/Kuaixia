package com.kuaixia.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCandidateScorerTest {

    private fun c(
        url: String,
        w: Int? = null,
        h: Int? = null,
        wm: Boolean? = null,
        source: String = "NETWORK",
    ) = ImageCandidate(url = url, width = w, height = h, watermarked = wm, source = source)

    @Test
    fun prefersNoWatermarkHigherResolution() {
        val best = ImageCandidateScorer.pickBest(
            listOf(
                c("u1", 480, 832, true),       // 带水印
                c("u2", 1080, 1920, false),    // 无水印
                c("u3", 2160, 2880, false),    // 无水印更高
            ),
        )
        assertEquals("u3", best?.url)
    }

    @Test
    fun noWatermarkLowResBeatsWatermarkHighResByRule() {
        // 规则：水印档优先于分辨率（无水印低清 > 带水印高清），因为无水印资源不保证可在本页复现更高版本
        val best = ImageCandidateScorer.pickBest(
            listOf(
                c("water-hd", 2160, 2880, true),
                c("plain-sd", 480, 832, false),
            ),
        )
        assertEquals("plain-sd", best?.url)
    }

    @Test
    fun bothNoWatermarkPrefersHigherResolution() {
        val best = ImageCandidateScorer.pickBest(
            listOf(
                c("a", 480, 832, false),
                c("b", 1080, 1920, false),
            ),
        )
        assertEquals("b", best?.url)
    }

    @Test
    fun bothUnknownPrefersHigherResolution() {
        val best = ImageCandidateScorer.pickBest(
            listOf(
                c("a", 480, 832, null),
                c("b", 1080, 1920, null),
            ),
        )
        assertEquals("b", best?.url)
    }

    @Test
    fun waterV2CandidateNotChosenWhenUnwatermarkedExists() {
        val best = ImageCandidateScorer.pickBest(
            listOf(
                c("https://cdn/~tplv-dy-water-v2:1080:0.jpeg", 1080, 1440, true),
                c("https://cdn/~tplv-dy-resize-walign-adapt-aq.jpeg", 480, 832, null),
            ),
        )
        // 未知(null) 档高于带水印(true)：即使带水印分辨率更高也不选
        assertEquals("https://cdn/~tplv-dy-resize-walign-adapt-aq.jpeg", best?.url)
    }

    @Test
    fun differentImagesNeverMergedByScorer() {
        // 归组由调用方按“页面 index/对象关系”完成；scorer 只负责组内排序，单候选原样返回
        val best = ImageCandidateScorer.pickBest(listOf(c("img1", 480, 832, false)))
        assertEquals("img1", best?.url)
        assertTrue(ImageCandidateScorer.pickBest(emptyList()) == null)
    }

    @Test
    fun watermarkHintRecognizesWaterV2Only() {
        assertEquals(true, ImageUrlHints.watermarkHint("https://p.douyinpic.com/x~tplv-dy-water-v2:0:0.jpeg"))
        assertEquals(true, ImageUrlHints.watermarkHint("https://p.douyinpic.com/x?wm=water-v2"))
        // 没有证据时不得标 false
        assertNull(ImageUrlHints.watermarkHint("https://p.douyinpic.com/x~tplv-dy-resize-walign-adapt-aq.jpeg"))
        assertNull(ImageUrlHints.watermarkHint("https://p.douyinpic.com/plain.jpeg"))
        assertNull(ImageUrlHints.watermarkHint(null))
    }

    @Test
    fun sizeHintParsesUrlTokensAndColonPattern() {
        val (w1, h1) = ImageUrlHints.sizeFromUrl("https://cdn/x~tplv-zz:w_480:h_832.jpeg")
        assertEquals(480, w1)
        assertEquals(832, h1)
        val (w2, h2) = ImageUrlHints.sizeFromUrl("https://cdn/x~tplv-zz:1080:1920.jpeg")
        assertEquals(1080, w2)
        assertEquals(1920, h2)
        val (w3, h3) = ImageUrlHints.sizeFromUrl("https://cdn/x.jpeg")
        assertNull(w3)
        assertNull(h3)
    }

    @Test
    fun awemeImagesRecognizedAsConfirmedNonWatermark() {
        // 已真机标定（像素级 A/B）的无水印档 → false；water-v2 仍 true
        assertEquals(false, ImageUrlHints.watermarkHint("https://p5-sign.douyinpic.com/tos/x~tplv-dy-aweme-images:q75.webp"))
        assertEquals(false, ImageUrlHints.watermarkHint("https://p5-sign.douyinpic.com/tos/x~tplv-dy-aweme-images:q75.jpeg"))
        assertEquals(true, ImageUrlHints.watermarkHint("https://p5-sign.douyinpic.com/tos/x~tplv-dy-water-v2:1440:1920.webp"))
        // 其他非 water 模板仍 unknown（不猜无水印）
        assertNull(ImageUrlHints.watermarkHint("https://p5-sign.douyinpic.com/tos/x~tplv-dy-resize-walign-adapt-aq.jpeg"))
    }

    @Test
    fun awemeWebpPreferredOverAwemeJpeg() {
        val best = ImageCandidateScorer.pickBest(
            listOf(
                c("https://cdn/x~tplv-dy-aweme-images:q75.jpeg", 1440, 1920, false),
                c("https://cdn/x~tplv-dy-aweme-images:q75.webp", 1440, 1920, false),
            ),
        )
        assertEquals("https://cdn/x~tplv-dy-aweme-images:q75.webp", best?.url)
    }
}
