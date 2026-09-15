package com.kuaixia.app.data.format

import com.kuaixia.app.data.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FormatDisplayGrouper 纯逻辑测试（JVM，无 Android 依赖）。
 * 覆盖：同高度去重 / bitrate / fps / codec 同组 / audio-only 排除 / 无高度不丢 / 排序 / 下载映射。
 */
class FormatDisplayGrouperTest {

    private fun stream(
        id: String,
        height: Int?,
        codec: String,
        bitrate: Double? = null,
        fps: Float? = null,
        url: String = "https://cdn.example.com/$id",
        audioOnly: Boolean = false,
    ): StreamInfo {
        val vcodec = if (audioOnly) "none" else codec
        val acodec = if (audioOnly) "aac" else "mp4a"
        return StreamInfo(
            formatId = id,
            url = url,
            quality = height?.let { "${it}P" },
            ext = "mp4",
            height = height,
            fps = fps,
            fileSize = null,
            vcodec = vcodec,
            acodec = acodec,
            hasAudio = true,
            videoUrl = if (audioOnly) null else url,
            bitrate = bitrate,
        )
    }

    @Test
    fun test1_sameHeightDedupToOneGroup() {
        val groups = FormatDisplayGrouper.group(
            listOf(
                stream("a", 1280, "avc1.640028", 3.0, 30f, "u1"),
                stream("b", 1280, "avc1.640028", 3.2, 30f, "u1"), // 同资源同编码同fps → 真正重复
                stream("c", 1280, "hevc", 5.0, 30f, "u2"),
                stream("d", 1024, "avc1.4d401f", 2.5, 30f, "u3"),
            ),
        )
        assertEquals("应只剩 1280P 与 1024P 两个显示项", 2, groups.size)
        assertEquals(listOf("1280P", "1024P"), groups.map { it.label })
    }

    @Test
    fun test2_sameHeightPreferHigherBitrate() {
        val groups = FormatDisplayGrouper.group(
            listOf(
                stream("low", 1280, "avc1.640028", bitrate = 3.0, fps = 30f, url = "u-low"),
                stream("high", 1280, "avc1.640028", bitrate = 5.0, fps = 30f, url = "u-high"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("high", groups[0].representative.formatId)
        assertEquals("u-high", groups[0].representative.url)
    }

    @Test
    fun test3_sameHeightPreferHigherFpsWhenBitrateClose() {
        val groups = FormatDisplayGrouper.group(
            listOf(
                stream("s30", 1280, "hevc", bitrate = 4.0, fps = 30f, url = "u30"),
                stream("s60", 1280, "hevc", bitrate = 4.0, fps = 60f, url = "u60"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals("s60", groups[0].representative.formatId)
    }

    @Test
    fun test4_differentCodecSameHeightSingleGroup() {
        val groups = FormatDisplayGrouper.group(
            listOf(
                stream("h264", 1280, "avc1.640028", 3.0, 30f, "u264"),
                stream("h265", 1280, "hevc", 3.0, 30f, "u265"),
            ),
        )
        assertEquals("不同 codec 同高度仍应是一个显示清晰度", 1, groups.size)
        assertEquals("H.265 编码更现代优先", "h265", groups[0].representative.formatId)
        assertEquals("底层仍保留另一版本", 1, groups[0].alternatives.size)
    }

    @Test
    fun test5_audioOnlyNotInVideoList() {
        val groups = FormatDisplayGrouper.group(
            listOf(
                stream("audio", null, "none", audioOnly = true, url = "u-audio"),
                stream("v720", 720, "avc1.4d401f", 2.0, 30f, "u-v"),
            ),
        )
        assertEquals("audio-only 不能进入视频清晰度列表", 1, groups.size)
        assertEquals("720P", groups[0].label)
    }

    @Test
    fun test6_nullHeightVideoNotDropped() {
        val groups = FormatDisplayGrouper.group(
            listOf(
                stream("unk", null, "avc1.640028", 1.5, 30f, "u-unk"),
            ),
        )
        assertEquals("无高度有效视频不能被丢弃", 1, groups.size)
        assertEquals(FormatDisplayGrouper.OTHER_LABEL, groups[0].label)
        assertNull(groups[0].height)
    }

    @Test
    fun test7_distinctHeightsDescendingOrder() {
        val heights = listOf(720, 1024, 1280, 1440, 2160)
        val groups = FormatDisplayGrouper.group(heights.map { h -> stream("f$h", h, "avc1", 3.0, 30f) })
        assertEquals(5, groups.size)
        assertEquals(listOf(2160, 1440, 1280, 1024, 720), groups.map { it.height })
        assertEquals(listOf("2160P", "1440P", "1280P", "1024P", "720P"), groups.map { it.label })
    }

    @Test
    fun test8_clickMappingBindsRealFormat() {
        val inputs = listOf(
            stream("f-h264-5", 1280, "avc1.640028", 5.0, 30f, "u-h264-5"),
            stream("f-h264-3", 1280, "avc1.640028", 3.0, 30f, "u-h264-3"),
            stream("f-1024", 1024, "avc1.4d401f", 2.0, 30f, "u-1024"),
        )
        val groups = FormatDisplayGrouper.group(inputs)
        val top = groups.first { it.label == "1280P" }
        // 点击 1280P → 直接绑定原始 format（更高码率那条），而不是字符串或重新按高度猜
        assertEquals("f-h264-5", top.representative.formatId)
        assertEquals("u-h264-5", top.representative.videoUrl)
        assertTrue(
            "代表必须是输入中的真实实例",
            inputs.any { it.formatId == top.representative.formatId && it.url == top.representative.url },
        )
        assertEquals("f-1024", groups.first { it.label == "1024P" }.representative.formatId)
    }
}
