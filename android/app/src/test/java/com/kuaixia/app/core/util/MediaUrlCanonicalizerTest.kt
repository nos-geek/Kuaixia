package com.kuaixia.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaUrlCanonicalizerTest {

    @Test
    fun dropTrackingParams() {
        assertEquals(
            "https://www.douyin.com/video/123?x=1",
            MediaUrlCanonicalizer.canonical("https://www.douyin.com/video/123?region=CN&mid=9&x=1"),
        )
    }

    @Test
    fun dropAllTrackingLeavesPlain() {
        assertEquals(
            "https://v.douyin.com/OGSxgtxIc6U",
            MediaUrlCanonicalizer.canonical(
                "https://v.douyin.com/OGSxgtxIc6U/?u_code=1&did=2&iid=3&previous_page=app_code_link",
            ),
        )
    }

    @Test
    fun sameWorkDifferentParamOrderEqual() {
        val a = MediaUrlCanonicalizer.canonical("https://www.douyin.com/video/999?b=2&a=1&region=CN")
        val b = MediaUrlCanonicalizer.canonical("https://www.douyin.com/video/999?a=1&b=2&region=HK")
        assertEquals(a, b)
    }

    @Test
    fun fragmentDropped() {
        assertEquals(
            "https://www.douyin.com/video/777",
            MediaUrlCanonicalizer.canonical("https://www.douyin.com/video/777#comment"),
        )
    }

    @Test
    fun meaningfulParamsKept() {
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2",
            MediaUrlCanonicalizer.canonical("https://www.bilibili.com/video/BV1xx411c7mD?p=2"),
        )
    }

    @Test
    fun nonHttpUntouched() {
        val raw = "intent://video/1?region=CN"
        assertEquals(raw, MediaUrlCanonicalizer.canonical(raw))
    }

    @Test
    fun differentWorkDifferentCanonical() {
        assertNotEquals(
            MediaUrlCanonicalizer.canonical("https://v.douyin.com/AAA/"),
            MediaUrlCanonicalizer.canonical("https://v.douyin.com/BBB/"),
        )
    }

    @Test
    fun blankSafe() {
        assertEquals("", MediaUrlCanonicalizer.canonical(""))
    }
}
