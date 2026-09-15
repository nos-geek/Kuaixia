package com.kuaixia.app.core.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ClipboardUrlExtractor 的 JVM 单元测试（Java 编写，规避 Kotlin 测试类接入差异）。
 * 覆盖需求 §14 六组用例 + 关键边界。
 */
public class ClipboardUrlExtractorTest {

    @Test
    public void test1_youtube_query_preserved() {
        String text = "https://youtube.com/shorts/6gkT9t_Lbu4?si=Wt5KBwU67L-A2ASu";
        assertEquals(listOf(text), ClipboardUrlExtractor.INSTANCE.extract(text));
        assertTrue(ClipboardUrlExtractor.INSTANCE.extract(text).get(0).contains("?si=Wt5KBwU67L-A2ASu"));
    }

    @Test
    public void test2_xiaohongshu_trailing_chinese() {
        String text = "https://xhslink.cn/o/AYA6P8pNvtW 存下这段话，去【小红书】速览笔记~";
        assertEquals(listOf("https://xhslink.cn/o/AYA6P8pNvtW"), ClipboardUrlExtractor.INSTANCE.extract(text));
    }

    @Test
    public void test3_kuaishou_adjacent_cjk_no_space() {
        String text = "https://v.kuaishou.com/JKIcqAkZ 《不同KD爆率》\"三角洲行动 \"我的世界 该作品在快手被播放过916.7万次";
        assertEquals(listOf("https://v.kuaishou.com/JKIcqAkZ"), ClipboardUrlExtractor.INSTANCE.extract(text));
    }

    @Test
    public void test4_douyin_full_share_text() {
        String text = "1.00 复制打开抖音，看看【湛江DW机车（毛毛）的作品】湛江90年代的生活 # 湛江  " +
                "https://v.douyin.com/OGSxgtxIc6U/ dnD:/ 03/27 :9pm r@R.K";
        assertEquals(listOf("https://v.douyin.com/OGSxgtxIc6U/"), ClipboardUrlExtractor.INSTANCE.extract(text));
    }

    @Test
    public void test5_bilibili_bracket_prefix() {
        String text = "【先生，这是现在最值得买的企业级空气盘了！希捷酷狼 Pro 8TB 评测【钱韦德】-哔哩哔哩】 https://b23.tv/9tkJjEJ";
        assertEquals(listOf("https://b23.tv/9tkJjEJ"), ClipboardUrlExtractor.INSTANCE.extract(text));
    }

    @Test
    public void test6_unknown_platform_full_query_kept() {
        String text = "https://example.com/video?id=123&quality=1080";
        assertEquals(
                listOf("https://example.com/video?id=123&quality=1080"),
                ClipboardUrlExtractor.INSTANCE.extract(text));
        assertEquals(
                ClipboardUrlExtractor.Platform.UNKNOWN,
                ClipboardUrlExtractor.INSTANCE.first(text).getPlatform());
    }

    @Test
    public void bare_https_is_not_a_link() {
        assertEquals(Arrays.asList(), ClipboardUrlExtractor.INSTANCE.extract("看看这个 https:// 怎么样"));
        assertEquals(Arrays.asList(), ClipboardUrlExtractor.INSTANCE.extract("https://"));
    }

    @Test
    public void garbage_like_0327_is_not_a_link() {
        assertEquals(Arrays.asList(), ClipboardUrlExtractor.INSTANCE.extract("dnD:/ 03/27 :9pm r@R.K"));
    }

    @Test
    public void leading_chinese_and_wrapped_brackets() {
        String text = "（推荐）https://v.kuaishou.com/JKIcqAkZ）快看";
        assertEquals(listOf("https://v.kuaishou.com/JKIcqAkZ"), ClipboardUrlExtractor.INSTANCE.extract(text));
    }

    @Test
    public void platform_recognition() {
        assertEquals(ClipboardUrlExtractor.Platform.DOUYIN, ClipboardUrlExtractor.INSTANCE.platformOf("https://v.douyin.com/xx"));
        assertEquals(ClipboardUrlExtractor.Platform.KUAISHOU, ClipboardUrlExtractor.INSTANCE.platformOf("https://v.kuaishou.com/xx"));
        assertEquals(ClipboardUrlExtractor.Platform.XIAOHONGSHU, ClipboardUrlExtractor.INSTANCE.platformOf("https://xhslink.cn/o/xx"));
        assertEquals(ClipboardUrlExtractor.Platform.BILIBILI, ClipboardUrlExtractor.INSTANCE.platformOf("https://b23.tv/xx"));
        assertEquals(ClipboardUrlExtractor.Platform.YOUTUBE, ClipboardUrlExtractor.INSTANCE.platformOf("https://youtu.be/abc"));
    }

    @Test
    public void www_fallback_only_when_no_http() {
        assertEquals(listOf("https://www.example.com/video"), ClipboardUrlExtractor.INSTANCE.extract("站点 www.example.com/video 见"));
        List<String> withHttp = ClipboardUrlExtractor.INSTANCE.extract("https://example.com/a www.example.com/b");
        assertEquals(listOf("https://example.com/a"), withHttp);
    }

    @Test
    public void clean_input_whole_share_text() {
        assertEquals(
                "https://v.douyin.com/OGSxgtxIc6U/",
                ClipboardUrlExtractor.INSTANCE.cleanInput("1.00 复制打开抖音… https://v.douyin.com/OGSxgtxIc6U/ dnD:/ 03/27"));
        assertEquals("纯文字", ClipboardUrlExtractor.INSTANCE.cleanInput("  纯文字  "));
    }

    @Test
    public void trailing_fullwidth_wave_not_swallowed() {
        String text = "这是正文 https://xhslink.cn/o/AYA6P8pNvtW～快去看看吧";
        assertEquals(listOf("https://xhslink.cn/o/AYA6P8pNvtW"), ClipboardUrlExtractor.INSTANCE.extract(text));
    }

    private static List<String> listOf(String... items) {
        return Arrays.asList(items);
    }
}
