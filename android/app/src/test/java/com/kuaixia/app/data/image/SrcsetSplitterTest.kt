package com.kuaixia.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrcsetSplitterTest {

    @Test
    fun splitsSimpleMultiCandidate() {
        val out = SrcsetSplitter.split("a-480.webp 480w, b-720.webp 720w, c-1080.webp 1080w")
        assertEquals(3, out.size)
        assertEquals("a-480.webp", out[0].url)
        assertEquals(480, out[0].widthPx)
        assertEquals("b-720.webp", out[1].url)
        assertEquals(720, out[1].widthPx)
        assertEquals("c-1080.webp", out[2].url)
        assertEquals(1080, out[2].widthPx)
    }

    @Test
    fun splitsPictureSourceSrcsetSameGrammar() {
        // <picture><source srcset> 与 <img srcset> 语法一致，同一拆分器覆盖
        val out = SrcsetSplitter.split("x1.webp 1x, x2.webp 2x, x3.webp 3x")
        assertEquals(3, out.size)
        // 密度描述符不产出宽度
        assertEquals(null, out[0].widthPx)
        assertEquals(null, out[1].widthPx)
        assertEquals("x2.webp", out[1].url)
    }

    @Test
    fun emptyAndBlankReturnEmpty() {
        assertTrue(SrcsetSplitter.split(null).isEmpty())
        assertTrue(SrcsetSplitter.split("").isEmpty())
        assertTrue(SrcsetSplitter.split("   ").isEmpty())
    }

    @Test
    fun malformedSegmentsAreSkipped() {
        // 空段、纯描述符、碎片 → 全部跳过，不抛异常
        val out = SrcsetSplitter.split(" , 480w, ,, ,broken , https://c.example/i.webp ,  ,")
        assertEquals(1, out.size)
        assertEquals("https://c.example/i.webp", out[0].url)
    }

    @Test
    fun singleUrlWithoutDescriptor() {
        val out = SrcsetSplitter.split("https://c.example/img.webp")
        assertEquals(1, out.size)
        assertEquals("https://c.example/img.webp", out[0].url)
        assertEquals(null, out[0].widthPx)
    }

    @Test
    fun dataAndBlobUrlsFiltered() {
        val out = SrcsetSplitter.split(
            "data:image/png;base64,AAAA 1x, https://c.example/a.webp 480w, blob:https://x/y 1x",
        )
        assertEquals(1, out.size)
        assertEquals("https://c.example/a.webp", out[0].url)
    }

    @Test
    fun trailingAndLeadingWhitespaceTolerated() {
        val out = SrcsetSplitter.split("  https://c.example/a.webp 480w   ")
        assertEquals(1, out.size)
        assertEquals("https://c.example/a.webp", out[0].url)
        assertEquals(480, out[0].widthPx)
    }
}
