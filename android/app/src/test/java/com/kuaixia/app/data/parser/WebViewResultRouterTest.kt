package com.kuaixia.app.data.parser

import com.kuaixia.app.core.model.MediaType
import com.kuaixia.app.data.model.ImageResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结果路由纯逻辑测试（F-1 语义）：
 * - 视频候选存在 → VIDEO（不论页面类型）；
 * - 非 VIDEO 页有合法图片 → IMAGES（单图/图集，imageItems 全量保留）；
 * - VIDEO 页无视频 → 一律 FAIL（页面普通图片不作数）→ 上层 yt-dlp fallback。
 */
class WebViewResultRouterTest {

    private fun image(i: Int) = ImageResource(url = "https://p.douyinpic.com/image/$i.webp", mimeType = "image/webp", extension = "webp")

    private fun images(n: Int): List<ImageResource> = List(n) { image(it) }

    // ---------- route ----------

    @Test
    fun videoNoVideoNoImageFails() {
        assertEquals(WebViewResultRouter.Route.FAIL, WebViewResultRouter.route(true, 0, 0))
    }

    @Test
    fun videoNoVideoMultipleImagesStillFallsBack() {
        // F-1 回归修复（v.douyin.com/QR7GV3E1_d8 → /video/7683099070780776019）：
        // VIDEO 页无视频时，页面上的普通图片（头像/推荐卡/UI）数量不作数——图片收集只能证明
        // 「页面存在图片」，不能证明「图片属于作品」；按数量判图集会把普通视频页误判成
        // IMAGE_COLLECTION 并截断 yt-dlp fallback。VIDEO 页无视频一律 FAIL。
        assertEquals(WebViewResultRouter.Route.FAIL, WebViewResultRouter.route(true, 0, 2))
        assertEquals(WebViewResultRouter.Route.FAIL, WebViewResultRouter.route(true, 0, 4))
        assertEquals(WebViewResultRouter.Route.FAIL, WebViewResultRouter.route(true, 0, 7))
    }

    @Test
    fun videoNoVideoSingleImageKeepsFallback() {
        // 普通视频页只有 1 张封面 → 不误判图集，保持 FAIL → yt-dlp fallback
        assertEquals(WebViewResultRouter.Route.FAIL, WebViewResultRouter.route(true, 0, 1))
    }

    @Test
    fun slidesFourImagesImages() {
        // 非视频页（SLIDES/NOTE/OTHER）
        assertEquals(WebViewResultRouter.Route.IMAGES, WebViewResultRouter.route(false, 0, 4))
    }

    @Test
    fun slidesSingleImageImages() {
        assertEquals(WebViewResultRouter.Route.IMAGES, WebViewResultRouter.route(false, 0, 1))
    }

    @Test
    fun anyVideoCountKeepsVideo() {
        assertEquals(WebViewResultRouter.Route.VIDEO, WebViewResultRouter.route(true, 1, 0))
        assertEquals(WebViewResultRouter.Route.VIDEO, WebViewResultRouter.route(true, 3, 5))
        assertEquals(WebViewResultRouter.Route.VIDEO, WebViewResultRouter.route(false, 1, 2))
    }

    // ---------- buildImageVideoInfo：imageItems 全量保留 ----------

    @Test
    fun buildTwoImagesKeepsAllItems() {
        val imgs = images(2)
        val v = WebViewResultRouter.buildImageVideoInfo("id", "标题", "https://www.douyin.com/note/id", imgs)
        assertEquals(MediaType.IMAGE_COLLECTION, v.mediaType)
        assertEquals(2, v.imageItems.size)
        assertEquals(imgs, v.imageItems) // 原样全量，非仅首图
        assertEquals(imgs.first().url, v.thumbnail)
        assertTrue(v.streams.isEmpty())
    }

    @Test
    fun buildFourImagesKeepsAllItems() {
        val imgs = images(4)
        val v = WebViewResultRouter.buildImageVideoInfo("id", null, "https://www.douyin.com/video/id", imgs)
        assertEquals(MediaType.IMAGE_COLLECTION, v.mediaType)
        assertEquals(4, v.imageItems.size)
        assertEquals(imgs, v.imageItems)
        assertEquals(imgs.first().url, v.thumbnail)
    }

    @Test
    fun buildSingleImageIsImage() {
        val imgs = images(1)
        val v = WebViewResultRouter.buildImageVideoInfo("id", null, "https://www.douyin.com/note/id", imgs)
        assertEquals(MediaType.IMAGE, v.mediaType)
        assertEquals(1, v.imageItems.size)
        assertEquals(imgs.first().url, v.thumbnail)
    }

    @Test
    fun imageMediaTypeBoundary() {
        assertEquals(MediaType.IMAGE, WebViewResultRouter.imageMediaType(1))
        assertEquals(MediaType.IMAGE_COLLECTION, WebViewResultRouter.imageMediaType(2))
        // route() 不会为 0 张产生 IMAGES 分支；此函数本身对 0 取单图（防御性）
        assertEquals(MediaType.IMAGE, WebViewResultRouter.imageMediaType(0))
    }
}
