package com.kuaixia.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileNamingTest {

    @Test
    fun titleUsedWhenUsable() {
        assertEquals("作品标题", MediaFileNaming.baseName("作品标题", "123456789", null))
    }

    @Test
    fun urlLikeTitleFallsBackToId() {
        assertEquals(
            "7681884182683034746",
            MediaFileNaming.baseName("https://www.iesdouyin.com/share/video/7681884182683034746", "7681884182683034746", null),
        )
    }

    @Test
    fun hostLikeIdRejected() {
        // webview 早期用 host 作 id：不应作为文件名
        val name = MediaFileNaming.baseName(null, "www.iesdouyin.com", "https://www.douyin.com/note/7682718861846548601")
        assertEquals("7682718861846548601", name)
    }

    @Test
    fun deriveFromShortLink() {
        assertEquals(
            "OGSxgtxIc6U",
            MediaFileNaming.baseName(null, null, "https://v.douyin.com/OGSxgtxIc6U/"),
        )
    }

    @Test
    fun deriveIgnoresNavSegments() {
        assertEquals(
            "7681884182683034746",
            MediaFileNaming.baseName(null, null, "https://www.iesdouyin.com/share/video/7681884182683034746/"),
        )
    }

    @Test
    fun illegalCharsSanitized() {
        val name = MediaFileNaming.baseName("a/b:c*d", "id", null)
        assertTrue(!name.contains('/') && !name.contains(':') && !name.contains('*'))
    }

    @Test
    fun longTitleCapped() {
        val long = "很长的标题".repeat(30)
        val name = MediaFileNaming.baseName(long, null, null)
        assertTrue(name.length <= 80)
    }

    @Test
    fun nothingUsableFallback() {
        assertEquals("快夏作品", MediaFileNaming.baseName(null, null, null))
    }
}
