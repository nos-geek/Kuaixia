package com.kuaixia.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomImageGroupTest {

    private fun input(
        src: String? = null,
        currentSrc: String? = null,
        srcset: String? = null,
        data: String? = null,
        base: String = "https://www.iesdouyin.com/share/note/123456/",
    ) = DomImageGroup.Input(src, currentSrc, srcset, data, base)

    @Test
    fun collectsAllSourcesWithLabels() {
        val out = DomImageGroup.collect(
            input(
                src = "/img/cover.webp",
                currentSrc = "https://p3.douyinpic.com/img/cover~tplv-dy-shrink:480:832.webp",
                srcset = "/img/cover~tplv-dy-resize:720.webp 720w, /img/cover~tplv-dy-resize:1080.webp 1080w",
                data = "https://p9.douyinpic.com/img/cover-orig.webp",
            ),
        )
        // currentSrc、src(相对→绝对)、srcset×2、data×1 —— src 与 currentSrc 绝对化后不同 URL，共 5 条
        assertEquals(5, out.size)
        val labels = out.map { it.source }.toSet()
        assertTrue(DomImageGroup.SRC_DOM in labels)
        assertTrue(DomImageGroup.SRC_SRCSET in labels)
        assertTrue(DomImageGroup.SRC_DATA in labels)
        // srcset 宽度提示保留
        val srcset720 = out.first { it.widthPx == 720 }
        assertEquals(DomImageGroup.SRC_SRCSET, srcset720.source)
        // 相对 src 已绝对化
        assertTrue(out.any { it.url == "https://www.iesdouyin.com/img/cover.webp" })
    }

    @Test
    fun duplicateUrlsDeduplicatedKeepingFirstLabel() {
        val out = DomImageGroup.collect(
            input(
                src = "https://cdn/x.webp",
                currentSrc = "https://cdn/x.webp", // 与 src 相同 → 去重，保留 DOM_SRC
                srcset = "https://cdn/x.webp 480w, https://cdn/y.webp 720w",
            ),
        )
        assertEquals(2, out.size)
        assertEquals(DomImageGroup.SRC_DOM, out[0].source) // 首次来源保留
        assertNull(out[0].widthPx)
        assertEquals(DomImageGroup.SRC_SRCSET, out[1].source)
    }

    @Test
    fun pictureSourceSrcsetFlowsInAsSrcsetCandidates() {
        // JS 采集端把 <picture><source srcset> 并入 srcset 字段；这里验证其字符串可被正确拆分归组
        val out = DomImageGroup.collect(
            input(
                src = "/pic/a.webp",
                srcset = "/pic/a-480.webp 480w, /pic/a-1080.webp 1080w",
            ),
        )
        assertEquals(3, out.size) // src + 2 档
        assertTrue(out.any { it.url.endsWith("/pic/a-1080.webp") && it.source == DomImageGroup.SRC_SRCSET })
    }

    @Test
    fun dataAndBlobSkipped() {
        val out = DomImageGroup.collect(
            input(
                src = "data:image/gif;base64,R0lGOD",
                srcset = "blob:https://www.iesdouyin.com/uuid 1x",
                data = "javascript:void(0)",
                base = "https://www.iesdouyin.com/",
            ),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun blankOrBrokenBaseYieldsEmpty() {
        assertTrue(DomImageGroup.collect(input(base = "")).isEmpty())
        assertTrue(DomImageGroup.collect(input(base = "not a uri")).isEmpty())
    }

    @Test
    fun lazyDataUrlsAllCollected() {
        val out = DomImageGroup.collect(
            input(
                src = "/cover.webp",
                data = "/img/2.webp\u001F/img/3.webp\u001F/img/4.webp", // JS join('\u001F')
            ),
        )
        assertEquals(4, out.size)
        assertEquals(listOf(DomImageGroup.SRC_DOM, DomImageGroup.SRC_DATA, DomImageGroup.SRC_DATA, DomImageGroup.SRC_DATA), out.map { it.source })
    }

    @Test
    fun buildCandidatesAnnotatesWatermarkAndSize() {
        val entries = DomImageGroup.collect(
            input(
                src = "https://cdn/x~tplv-dy-water-v2:1080:1920.webp",
                srcset = "https://cdn/x~tplv-dy-shrink:480:832.webp 480w",
            ),
        )
        val cands = DomImageGroup.buildCandidates(entries)
        val water = cands.first { it.source == DomImageGroup.SRC_DOM }
        assertEquals(true, water.watermarked)          // water-v2 确认带水印
        assertEquals(1080, water.width)
        assertEquals(1920, water.height)
        val plain = cands.first { it.source == DomImageGroup.SRC_SRCSET }
        assertNull(plain.watermarked)                  // shrink 档未知，不猜无水印
        assertEquals(480, plain.width)
    }

    @Test
    fun pickBestChoosesNoWatermarkHighResWithinGroup() {
        val entries = DomImageGroup.collect(
            input(
                currentSrc = "https://cdn/x~tplv-dy-water-v2:1080:1920.webp",
                srcset = "https://cdn/x~tplv-dy-resize:480:832.webp 480w, https://cdn/x~tplv-dy-resize:2160:2880.webp 2160w",
            ),
        )
        val best = DomImageGroup.pickBest(entries)
        // water-v2=true 被压到带水印档最后；未知(null) 档内 2160 最高 → 选中未知高画质
        assertEquals("https://cdn/x~tplv-dy-resize:2160:2880.webp", best?.url)
    }

    @Test
    fun pickBestEmptyGroupIsNull() {
        assertNull(DomImageGroup.pickBest(emptyList()))
    }
}
