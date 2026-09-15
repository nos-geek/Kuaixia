package com.kuaixia.app.data.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlidesInfoParserTest {

    private val OBJ1 = "o0z6zVRQA97fAAAAAAAAAAAAAAAA"
    private val OBJ2 = "okNEkaDeADkrBBBBBBBBBBBBBBBB"

    private fun sampleJson(): String {
        val wm1 = "https://p96-sign.douyinpic.com/tos-cn-i-0813c001/$OBJ1~tplv-dy-water-v2:1440:1920.webp?x=1"
        val nw1 = "https://p5-sign.douyinpic.com/tos-cn-i-0813c001/$OBJ1~tplv-dy-aweme-images:q75.webp?x=2"
        val wm2 = "https://p9-sign.douyinpic.com/tos-cn-i-0813c001/$OBJ2~tplv-dy-water-v2:1440:1920.jpeg?y=1"
        val nw2 = "https://p11-sign.douyinpic.com/tos-cn-i-0813c001/$OBJ2~tplv-dy-aweme-images:q75.jpeg?y=2"
        return """
            {"aweme_details":[{"images":[
                {"uri":"tos-cn-i-0813c001/$OBJ1","url_list":["$wm1","$nw1"],"width":1440,"height":1920},
                {"uri":"tos-cn-i-0813c001/$OBJ2","url_list":["$wm2","$nw2"],"width":1080,"height":1440}
            ]}]}
        """.trimIndent()
    }

    @Test
    fun parsesImagesFromAwemeDetails() {
        val imgs = SlidesInfoParser.parse(sampleJson())
        assertEquals(2, imgs.size)
        assertEquals(0, imgs[0].seq)
        assertEquals(1, imgs[1].seq)
        assertEquals(2, imgs[0].urls.size)
        assertTrue(imgs[0].urls.any { it.contains("aweme-images") })
        assertTrue(imgs[0].urls.any { it.contains("water-v2") })
        // objectKey 从 url_list 首条提取，去 ~tplv 段 → 与捕获候选 extractObjKey 同源
        assertEquals(OBJ1, imgs[0].objKey)
        assertEquals(OBJ2, imgs[1].objKey)
    }

    @Test
    fun emptyOnMissingStructure() {
        assertTrue(SlidesInfoParser.parse("{}").isEmpty())
        assertTrue(SlidesInfoParser.parse("""{"aweme_details":[]}""").isEmpty())
        assertTrue(SlidesInfoParser.parse("""{"aweme_details":[{}]}""").isEmpty())
        assertTrue(SlidesInfoParser.parse("""{"aweme_details":[{"images":[]}]}""").isEmpty())
        assertTrue(SlidesInfoParser.parse("not json").isEmpty())
        assertTrue(SlidesInfoParser.parse("").isEmpty())
    }

    @Test
    fun skipsImageWithEmptyUrlList() {
        val json = """
            {"aweme_details":[{"images":[
                {"uri":"tos/u1","url_list":["https://cdn/x~tplv-dy-aweme-images:q75.webp"]},
                {"uri":"tos/u2","url_list":[]}
            ]}]}
        """.trimIndent()
        val imgs = SlidesInfoParser.parse(json)
        assertEquals(1, imgs.size)
        assertEquals(0, imgs[0].seq)
    }

    @Test
    fun dedupsUrlList() {
        val u = "https://cdn/x~tplv-dy-aweme-images:q75.webp"
        val json = """{"aweme_details":[{"images":[{"uri":"tos/u1","url_list":["$u","$u"]}]}]}"""
        val imgs = SlidesInfoParser.parse(json)
        assertEquals(1, imgs[0].urls.size)
    }
}
