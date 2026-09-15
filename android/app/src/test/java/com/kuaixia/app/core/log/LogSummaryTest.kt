package com.kuaixia.app.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志 V2：PARSE / DOWNLOAD / DEVICE SUMMARY 生成测试（字段名固定、缺失用 unknown）。 */
class LogSummaryTest {

    @Test
    fun parseSummaryContainsStableFields() {
        val text = ParseSummary.render(
            ParseSummary.Fields(
                timeMs = 1_700_000_000_000L,
                platform = "douyin",
                page = "VIDEO",
                url = "https://v.douyin.com/xxxx/",
                strategy = "DOUYIN_LOCAL_FIRST",
                success = true,
                mediaType = "VIDEO",
                source = "网页解析",
                video = 1,
                audio = 0,
                image = 0,
                formats = 1,
                formatGroups = 1,
                fallbackYtdlp = false,
                elapsedMs = 4387,
            ),
        )
        listOf(
            "========== PARSE SUMMARY ==========",
            "platform=douyin",
            "page=VIDEO",
            "strategy=DOUYIN_LOCAL_FIRST",
            "video=1",
            "audio=0",
            "image=0",
            "success=true",
            "mediaType=VIDEO",
            "formats=1",
            "formatGroups=1",
            "ytdlp=false",
            "elapsedMs=4387",
        ).forEach { assertTrue("缺少字段: $it", text.contains(it)) }
    }

    @Test
    fun parseSummaryFailureCarriesError() {
        val text = ParseSummary.render(
            ParseSummary.Fields(
                timeMs = 0L, platform = "douyin", page = "unknown", url = "https://x/",
                strategy = "DOUYIN_LOCAL_FIRST", success = false, mediaType = "unknown",
                source = "none", video = 0, audio = 0, image = 0, formats = 0, formatGroups = 0,
                fallbackYtdlp = false, elapsedMs = 9_000, error = "该抖音作品暂无法提取",
            ),
        )
        assertTrue(text.contains("success=false"))
        assertTrue(text.contains("error=该抖音作品暂无法提取"))
    }

    @Test
    fun parseSummaryPageInference() {
        assertEquals("VIDEO", ParseSummary.pageOf("https://www.iesdouyin.com/share/video/123/"))
        assertEquals("NOTE", ParseSummary.pageOf("https://www.iesdouyin.com/share/note/123/"))
        assertEquals("SLIDES", ParseSummary.pageOf("https://www.iesdouyin.com/share/slides/123/"))
        assertEquals("unknown", ParseSummary.pageOf("https://v.douyin.com/abc/"))
    }

    @Test
    fun downloadSummaryContainsStableFields() {
        val text = DownloadSummary.render(
            DownloadSummary.Fields(
                timeMs = 0L, platform = "douyin", type = "DIRECT", quality = "720P",
                http = 403, elapsedMs = 1234, error = "HTTP 403 Forbidden",
            ),
        )
        listOf(
            "========== DOWNLOAD SUMMARY ==========", "platform=douyin", "type=DIRECT",
            "quality=720P", "http=403", "elapsedMs=1234", "error=HTTP 403 Forbidden",
        ).forEach { assertTrue("缺少字段: $it", text.contains(it)) }
    }

    @Test
    fun downloadSummaryUnknownWhenMissing() {
        val text = DownloadSummary.render(
            DownloadSummary.Fields(
                timeMs = 0L, platform = "unknown", type = "DIRECT", quality = null,
                http = null, elapsedMs = 0, error = "未知错误",
            ),
        )
        assertTrue(text.contains("quality=unknown"))
        assertTrue(text.contains("http=unknown"))
    }

    @Test
    fun downloadPlatformInference() {
        assertEquals("douyin", DownloadSummary.platformOf("https://v.douyin.com/x/"))
        assertEquals("bilibili", DownloadSummary.platformOf("https://www.bilibili.com/video/BV1"))
        assertEquals("unknown", DownloadSummary.platformOf(null))
    }

    @Test
    fun deviceSummaryRendersUnknownForMissing() {
        val text = DeviceSummary.render(
            listOf(
                DeviceSummary.Field("appVersion", "v1.2 (3)"),
                DeviceSummary.Field("webViewVersion", null),
            ),
        )
        assertTrue(text.contains("========== DEVICE SUMMARY =========="))
        assertTrue(text.contains("appVersion=v1.2 (3)"))
        assertTrue(text.contains("webViewVersion=unknown"))
    }
}
