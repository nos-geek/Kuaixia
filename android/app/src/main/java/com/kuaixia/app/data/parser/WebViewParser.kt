package com.kuaixia.app.data.parser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.data.image.DomImageGroup
import com.kuaixia.app.data.image.ImageUrlHints
import com.kuaixia.app.data.image.PageJsonMerge
import com.kuaixia.app.data.image.SlidesInfoParser
import com.kuaixia.app.data.model.ImageResource
import com.kuaixia.app.data.model.StreamInfo
import com.kuaixia.app.data.model.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView 嗅探解析器（Phase 5 最小技术验证）。
 *
 * ## 能力边界（诚实说明，设计依据真实 API）
 * - [android.webkit.WebViewClient.shouldInterceptRequest] 只能**观察请求**（URL/method/请求头）；
 *   当返回 null 时，实际响应由 Chromium 网络栈直接处理 —— **App 拿不到响应 body，也拿不到
 *   响应 Content-Type / 响应头**。因此媒体识别以「请求 URL + 请求头 Accept + 页面 JS 上报」为主。
 * - 补充手段：注入**本地常量** JS 轮询页面 `<video>/<audio>` 元素与 resource timing，
 *   经 [KuaixiaJsBridge]（addJavascriptInterface）把浏览器侧实际播放的媒体 URL 报回。
 *
 * ## 范围
 * - 只处理快夏自己创建的 WebView；不读其它 App Cookie、不碰系统代理/VPN；
 * - Cookie 来自本 WebView 会话（CookieManager），仅随候选 headers 参与下载，日志不落明文；
 * - 只**探测**并产出候选 [StreamInfo]，不在这里实现任何下载（m3u8/mp4 均交既有下载器）；
 * - 明确识别：mp4/m4v/webm/mov、m3u8、ts/m4s（分段）、audio 扩展名 + Accept video/audio + /videoplayback。
 *
 * 本阶段**不接入 ParserManager 默认流程**，只由「WebView 嗅探测试」调试页调用。
 */
class WebViewParser(private val context: Context) : VideoParser {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 媒体候选（去重 + 稳定排序后的输出）。 */
    data class MediaCandidate(
        val url: String,
        val kind: MediaKind,
        /** 尽力推断的 MIME（来自 Accept/扩展名；真实响应 MIME 拿不到，可能为 null/近似）。 */
        val mimeHint: String?,
        val requestHeaders: Map<String, String>,
        val captureOrder: Int,
        // P8.2：PAGE_JSON video.bit_rate[] 档位元数据（仅页面 JSON 候选携带；网络捕获候选保持 null = P8.1 行为）
        /** gear_name（如 1080p / normal_720_0；仅日志参考，不参与排序）。 */
        val gear: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        /** 码率 kbps（页面 bps 已归一；与 yt-dlp tbr 同量级，仅同清晰度组内择优用）。 */
        val bitrate: Double? = null,
        /** play_addr.data_size（字节）。 */
        val dataSize: Long? = null,
    ) {
        val isVideo: Boolean get() = kind == MediaKind.VIDEO || kind == MediaKind.HLS
        val isAudio: Boolean get() = kind == MediaKind.AUDIO

        fun toStreamInfo(quality: String?, referer: String?): StreamInfo {
            // Cookie 已并入 headers（本会话直链可能需要）。
            // Referer 规则：优先保留原始 requestHeaders 里的真实 Referer（filterKeys 已保留）；
            // 仅在「页面 URL ≠ 资源 URL」时才用页面 URL 兜底（禁止把资源自身 URL 当 Referer，
            // 否则会把媒体请求伪装成自我引用请求，部分源会因此拒绝）。
            val headers = requestHeaders.toMutableMap().apply {
                remove("range"); remove("Range")
                val hasReferer = keys.any { it.equals("referer", true) }
                val usablePage = referer?.takeIf {
                    it != url &&
                        (it.startsWith("http://") || it.startsWith("https://"))
                }
                if (!hasReferer && usablePage != null) {
                    put("Referer", usablePage)
                }
            }
            val proto = if (kind == MediaKind.HLS) "m3u8" else "http"
            // P8.2：档位元数据只取有效正数（JS 端已做 isFinite 校验，此处兜底）
            val realHeight = height?.takeIf { it > 0 }
            val realWidth = width?.takeIf { it > 0 }
            return StreamInfo(
                formatId = "webview-${captureOrder}",
                url = url,
                // P8.2：真实字段优先 —— height 存在时 quality="${height}P"（与 UI 分组 label、
                // 下载任务记录的 quality 三者一致，保证"链接失效重解析"的 pickStream 匹配）；
                // height 缺失时沿用既有 URL 正则兜底（旧逻辑保留，行为与 P8.1 一致）
                quality = realHeight?.let { "${it}P" } ?: quality,
                ext = extFor(kind),
                mimeType = mimeHint,
                resolution = if (realWidth != null && realHeight != null) "${realWidth}×${realHeight}" else null,
                width = realWidth,
                height = realHeight,
                fileSize = dataSize?.takeIf { it > 0 },
                bitrate = bitrate,
                protocol = proto,
                videoHeaders = headers,
                hasAudio = kind == MediaKind.AUDIO || kind == MediaKind.VIDEO || kind == MediaKind.HLS,
            )
        }
    }

    enum class MediaKind(val label: String) {
        VIDEO("video"),
        HLS("m3u8"),
        SEGMENT("segment"),
        AUDIO("audio"),
        UNKNOWN("unknown"),
    }

    /** 页面类型：决定「只捕获到图片」时是否允许作为图片作品（/video/ 页不允许）。 */
    enum class PageKind { VIDEO, SLIDES, NOTE, OTHER }

    private fun pageKind(u: String?): PageKind {
        val path = runCatching { Uri.parse(u.orEmpty()).path?.lowercase(Locale.ROOT) }.getOrNull() ?: ""
        return when {
            path.contains("/video/") || path.endsWith("/video") -> PageKind.VIDEO
            path.contains("/slides/") || path.endsWith("/slides") -> PageKind.SLIDES
            path.contains("/note/") || path.endsWith("/note") -> PageKind.NOTE
            else -> PageKind.OTHER
        }
    }

    // ======================= VideoParser =======================
    /** Extract a readable work id from the page URL (last path segment; prefer digits),
     *  never fall back to the bare host. */
    private fun workIdOf(u: String?): String {
        val path = runCatching { Uri.parse(u.orEmpty()).path }.getOrNull() ?: ""
        val seg = path.split('/').filter { it.isNotBlank() }.lastOrNull()
        val candidate = seg?.substringBefore('.')?.takeIf { it.isNotBlank() }
        return candidate ?: (Uri.parse(u.orEmpty()).host ?: "webview")
    }


    override fun support(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    /** 加载页面并嗅探媒体候选；全程在 Main 线程操作 WebView，返回前销毁。 */
    override suspend fun parse(url: String): Result<VideoInfo> = withContext(Dispatchers.Main) {
        val collector = CandidateCollector()
        val pageState = PageState()
        // P2-001（session 防护）：单次解析的会话对象 —— 取消即失效，页面回调 / JS 桥入口一律先过闸门；
        // 收尾（teardown）通过 session.teardownOnce 保证幂等（cancel / finally / 超时 / 重复调用只执行一次）。
        val session = ParseSession()
        val webView = createWebView(collector, pageState, session)
        val started = System.currentTimeMillis()
        try {
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "WebView 创建并开始加载 parseSeq=${session.generation} url=${LogSanitizer.sanitizeUrl(url)}",
            )
            webView.loadUrl(url)

            // 轮询收尾条件：候选稳定静默 ≥ STABLE_WINDOW_MS；页面完成且无候选也静默满窗口；超时兜底
            while (true) {
                val loadFail = pageState.loadFailed
                if (loadFail != null) {
                    throw AppException(ErrorCode.WEBVIEW_LOAD_FAILED, loadFail)
                }
                val now = System.currentTimeMillis()
                val overall = now - started
                val lastActivity = maxOf(pageState.finishedAt, collector.lastCandidateAt)
                val idle = now - lastActivity
                val anyCandidate = collector.candidates().isNotEmpty() || collector.imageCount() > 0

                if (overall >= TIMEOUT_MS) {
                    if (!anyCandidate) {
                        throw AppException(ErrorCode.WEBVIEW_TIMEOUT, "页面加载超时（30s），未捕获到媒体资源")
                    }
                    AppLogRepository.w(LogTags.WEBVIEW, "收集窗口达上限，按现有候选返回 count=${collector.size()}")
                    break
                }
                // P8 视频快速路径：VIDEO 页 finished 后再等短视频静默窗即收尾，跳过图片候选 4s 稳定等待
                // （图片稳定窗仅图集路径需要；视频流通常随页面加载/自动播放已发起）。仅影响 VIDEO 页。
                if (pageState.finishedAt > 0 &&
                    pageKind(pageState.finalUrl ?: url) == PageKind.VIDEO &&
                    now - pageState.finishedAt >= VIDEO_FINISH_QUIET_MS
                ) {
                    AppLogRepository.i(
                        LogTags.WEBVIEW,
                        "P8 VIDEO 快速路径收尾 finished 后 ${now - pageState.finishedAt}ms count=${collector.size()} " +
                            "video=${collector.candidates().count { it.kind != MediaKind.AUDIO }}",
                    )
                    break
                }
                if (anyCandidate && idle >= STABLE_WINDOW_MS) {
                    AppLogRepository.i(
                        LogTags.WEBVIEW,
                        "候选稳定窗口结束 count=${collector.size()} idleMs=$idle",
                    )
                    break
                }
                // 页面完成且无候选：再等一个静默窗口后判「未发现媒体」
                if (pageState.finishedAt > 0 && !anyCandidate && idle >= STABLE_WINDOW_MS) {
                    break
                }
                delay(POLL_INTERVAL_MS)
            }

            // ---------- 结果构建（销毁 WebView 前完成，结果对象独立于页面） ----------
            // P8.1：videoPageHint 提前计算（供 PAGE_JSON images 段与 video 候选段共用；IMAGE 路径不受影响）
            val videoPageHint = pageKind(pageState.finalUrl ?: url) == PageKind.VIDEO
            val capturedCandidates = collector.finalizeCandidates()
            val capturedImages = collector.imageResources()

            // PAGE_JSON：页面运行时 JSON（_ROUTER_DATA → videoInfoRes.item_list[].images[].url_list）
            // 提供同一作品图片的高清候选（如 lqen-new:1440:2560）。存在时按 obj key 吸收同图
            // NETWORK/DOM 捕获候选，组内选优后每张作品图输出 1 个 URL；不存在/失败 → 完全旧链。
            // P8：VIDEO 页使用独立轮询参数（更快收尾）；IMAGE/其他页保持原参数（不动）。
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "P8 PAGE_JSON start page=${if (videoPageHint) "VIDEO" else "IMAGE/其他"} " +
                    "wait=${if (videoPageHint) VIDEO_PAGE_JSON_TOTAL_WAIT_MS else PAGE_JSON_TOTAL_WAIT_MS}ms",
            )
            val jsonImages = if (videoPageHint) {
                readPageJsonImages(webView, VIDEO_PAGE_JSON_POLL_INTERVAL_MS, VIDEO_PAGE_JSON_TOTAL_WAIT_MS)
            } else {
                readPageJsonImages(webView)
            }
            // P0 业务修复：/share/slides/ 页没有 _ROUTER_DATA → PAGE_JSON 恒空 → 改走 slidesinfo 接口
            // （真实证据：slidesinfo 的 aweme_details[0].images[].url_list 含无水印 aweme-images 档）。
            val slidesPageHint = pageKind(pageState.finalUrl ?: url) == PageKind.SLIDES
            val effectiveJsonImages: List<PageJsonMerge.JsonImage>? = when {
                !jsonImages.isNullOrEmpty() -> jsonImages
                slidesPageHint -> readSlidesInfoImages(pageState)
                else -> null
            }
            val images: List<ImageResource> = if (!effectiveJsonImages.isNullOrEmpty()) {
                val merged = PageJsonMerge.merge(
                    effectiveJsonImages,
                    capturedImages.map { PageJsonMerge.Captured(it.url, "CAPTURED") },
                )
                AppLogRepository.i(
                    LogTags.WEBVIEW,
                    "PAGE_JSON images count=${effectiveJsonImages.size} merged=${merged.size}",
                )
                effectiveJsonImages.forEach { j ->
                    val firstUrl = j.urls.firstOrNull().orEmpty()
                    val (w, h) = ImageUrlHints.sizeFromUrl(firstUrl)
                    AppLogRepository.d(
                        LogTags.WEBVIEW,
                        "PAGE_JSON image index=${j.seq} source=PAGE_JSON obj=${j.objKey?.take(40) ?: "?"} " +
                            "tpl=${templateToken(firstUrl)} dims=${w ?: "?"}x${h ?: "?"} urls=${j.urls.size}",
                    )
                }
                merged.forEachIndexed { i, r ->
                    val (w, h) = ImageUrlHints.sizeFromUrl(r.url)
                    AppLogRepository.d(
                        LogTags.WEBVIEW,
                        "PAGE_JSON selected index=$i source=PAGE_JSON tpl=${templateToken(r.url)} dims=${w ?: "?"}x${h ?: "?"}",
                    )
                }
                if (merged.isNotEmpty()) merged else {
                    AppLogRepository.w(LogTags.WEBVIEW, "PAGE_JSON 提取为空，回退 NETWORK/DOM 链")
                    capturedImages
                }
            } else {
                if (effectiveJsonImages == null) {
                    AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON skipped（无数据/超时/异常）→ 使用 NETWORK/DOM")
                } else {
                    AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON skipped（无图片字段）→ 使用 NETWORK/DOM")
                }
                capturedImages
            }
            val hasImages = images.isNotEmpty()

            // ===== P8.1：VIDEO 页 PAGE_JSON 视频候选接入（videoInfoRes.item_list[].video 定向提取）=====
            // 仅 VIDEO 页执行；页面真实提供 play_addr/download_addr.url_list(http) 时前置为 VIDEO 候选。
            // playwm 等 URL 仅作候选（下载=带水印资源，日志保守标注），绝不标注无水印；
            // 无候选 → candidates 保持纯捕获 → route 照常 FAIL → yt-dlp fallback。IMAGE 路径完全不受影响。
            val videoJsonCandidates = if (videoPageHint) {
                readVideoJsonCandidates(webView, capturedCandidates.map { it.url }.toSet())
            } else {
                emptyList()
            }
            val candidates: List<MediaCandidate> = if (videoJsonCandidates.isEmpty()) {
                capturedCandidates
            } else {
                val extra = videoJsonCandidates.map { vc ->
                    MediaCandidate(
                        url = vc.url,
                        kind = MediaKind.VIDEO,
                        // WebView/页面直链拿不到响应 MIME；与 classify() 对无扩展 VIDEO 的默认（mimeByExt）一致，
                        // 不新造字段体系。URL 如 aweme/v1/playwm/ 无 .mp4 后缀，靠 kind+ext(mp4) 走既有下载链。
                        mimeHint = "video/mp4",
                        requestHeaders = emptyMap(),
                        captureOrder = captureOrderCounter.incrementAndGet(),
                        // P8.2：bit_rate[] 档位元数据（缺失时全为 null → 与 P8.1 行为一致）
                        gear = vc.gear,
                        width = vc.width,
                        height = vc.height,
                        bitrate = vc.bitrate,
                        dataSize = vc.dataSize,
                    )
                }
                // PAGE_JSON 直链优先（默认下载首选）；已捕获同 URL 不重复
                extra + capturedCandidates.filter { c -> videoJsonCandidates.none { it.url == c.url } }
            }
            val videoCount = candidates.count { it.kind != MediaKind.AUDIO }
            val audioCount = candidates.count { it.kind == MediaKind.AUDIO }
            val hasVideo = videoCount > 0
            // P0 回归修复：区分「存在视频候选」与「存在足够可信的视频候选」。
            // trusted = NETWORK/HLS 捕获到的视频候选数 + PAGE_JSON 候选中 watermarkHint == false 的数量
            // （watermarkHint 来自 VideoJsonCandidate，复用既有判定，不新增第二套 playwm 判断）。
            // **只用于编排决策**（低可信时不直接截断 yt-dlp，失败仍回退本结果）；
            // 不删除任何候选、不改候选顺序、不参与质量排序/UI。
            val capturedTrustedVideoCount = capturedCandidates.count { it.kind != MediaKind.AUDIO }
            val jsonTrustedVideoCount = videoJsonCandidates.count { !it.watermarkHint }
            val trustedVideoCount = capturedTrustedVideoCount + jsonTrustedVideoCount
            val lowConfidenceVideo = videoCount > 0 && trustedVideoCount == 0
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "P8.1 VIDEO 页 PAGE_JSON video 候选=${videoJsonCandidates.size} " +
                    "合并后 videoCandidates=$videoCount（json 前置 + 捕获去重）",
            )
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "VIDEO 候选可信度 trustedVideo=$trustedVideoCount" +
                    "（捕获=$capturedTrustedVideoCount + 页面非水印=$jsonTrustedVideoCount）" +
                    " lowConfidence=$lowConfidenceVideo",
            )

            val finalUrl = pageState.finalUrl ?: url
            // P1: 标题缺失时绝不回退完整 HTTPS URL；用短作品 ID（避免超长 URL 成为 UI/下载标题）
            val title = pageState.title?.takeIf { it.isNotBlank() }
                ?: "抖音作品_${workIdOf(finalUrl)}"
            val cookie = mainCookie(finalUrl)
            // 页面类型：明确 /video/ 页即使只捕获图片也绝不能当图片作品成功
            val primaryKind = pageKind(finalUrl).let { if (it == PageKind.OTHER) pageKind(url) else it }
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "嗅探 videoCandidates=$videoCount audioCandidates=$audioCount " +
                    "imageCandidates=${images.size} mediaCandidates=${images.size + candidates.size} " +
                    "page=${primaryKind.name} url=${LogSanitizer.sanitizeUrl(finalUrl)}",
            )
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "Douyin WebView imageStats ${collector.imageFilterSummary()}",
            )

            val isVideoPage = primaryKind == PageKind.VIDEO
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "P8 parse 收尾 elapsed=${System.currentTimeMillis() - started}ms page=${primaryKind.name} " +
                    "video=$videoCount audio=$audioCount images=${images.size}",
            )
            when (WebViewResultRouter.route(isVideoPage, videoCount, images.size)) {
                WebViewResultRouter.Route.VIDEO -> {
                    // 真实视频流 → 原有 VideoInfo/FormatGroup 链路（图片不混入下载）
                    val streams = candidates.map { c ->
                        c.toStreamInfo(qualityFromUrl(c.url), finalUrl).let { si ->
                            if (cookie.isNotBlank()) {
                                si.copy(videoHeaders = si.videoHeaders + ("Cookie" to cookie))
                            } else si
                        }
                    }
                    AppLogRepository.i(
                        LogTags.WEBVIEW,
                        "Douyin WebView mediaType=VIDEO videoCandidates=$videoCount " +
                            "imageCandidates=${images.size} trustedVideo=$trustedVideoCount " +
                            "lowConfidence=$lowConfidenceVideo",
                    )
                    Result.success(
                        VideoInfo(
                            id = workIdOf(finalUrl),
                            title = title,
                            // 封面 = 本次真实捕获的第一张作品图（仅 UI 展示；与下载资源彻底分离，不入 imageItems）
                            thumbnail = images.firstOrNull()?.url,
                            webpageUrl = finalUrl,
                            platform = "webview",
                            streams = streams,
                            mediaType = com.kuaixia.app.core.model.MediaType.VIDEO,
                            imageItems = emptyList(),
                            // P0：有候选但无足够可信候选（如唯一候选为 playwm）→ 交编排层先试 yt-dlp
                            lowConfidence = lowConfidenceVideo,
                        ),
                    )
                }
                WebViewResultRouter.Route.IMAGES -> {
                    // 图片作品：仅非 VIDEO 页（NOTE/SLIDES/未知）捕获到合法图片时进入；
                    // VIDEO 页不再凭图片数量判定图集（F-1：页面杂图可能进候选，防误判，见 route() 文档），
                    // 无视频的 VIDEO 页走 FAIL → yt-dlp fallback。
                    val count = images.size
                    val built = WebViewResultRouter.buildImageVideoInfo(
                        id = workIdOf(finalUrl),
                        title = title,
                        webpageUrl = finalUrl,
                        images = images,
                    )
                    AppLogRepository.i(
                        LogTags.WEBVIEW,
                        "Douyin WebView mediaType=${built.mediaType.name} page=${primaryKind.name} " +
                            "imageCandidates=$count（无视频，图片集）",
                    )
                    Result.success(built)
                }
                WebViewResultRouter.Route.FAIL -> {
                    // 无有效媒体：视频页单图（封面）不算图片作品 → 抛错走 yt-dlp fallback；其余无媒体同
                    // P8 取证：VIDEO 页未捕获到视频时，探测页面 JSON 是否存在 video 字段（如 video.play_addr
                    // .url_list）——仅日志取证（字段结构未真机标定前不接入候选，防伪造）；随后照常 FAIL→yt-dlp。
                    if (isVideoPage) {
                        runCatching { videoJsonProbe(webView) }
                    }
                    val msg = when {
                        isVideoPage && images.size == 1 ->
                            "视频页未捕获到真实视频资源（仅封面图），将尝试其它解析方式"
                        audioCount > 0 ->
                            "页面仅捕获到音频资源，缺少可下载的视频/图片，暂不能判定为视频作品"
                        else ->
                            "页面加载完成但未发现可下载的视频/图片资源（可能需登录或点击播放）"
                    }
                    AppLogRepository.i(LogTags.WEBVIEW, "Douyin WebView success=false reason=NO_VIDEO")
                    throw AppException(ErrorCode.MEDIA_NOT_FOUND, msg)
                }
            }
        } catch (e: CancellationException) {
            // P2-001：取消即作废会话 —— 之后任何页面回调 / JS 桥写入一律被丢弃（allowCallback=false）。
            session.invalidate()
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "嗅探取消 parseSeq=${session.generation} url=${LogSanitizer.sanitizeUrl(url)} " +
                    "droppedCallbacks=${session.droppedCallbacks}",
            )
            throw e
        } catch (e: Throwable) {
            val ae = e as? AppException
            AppLogRepository.w(
                LogTags.WEBVIEW,
                "嗅探失败 code=${ae?.code ?: "?"} msg=${e.message}",
            )
            Result.failure(
                ae ?: AppException(ErrorCode.WEBVIEW_LOAD_FAILED, "嗅探异常：${e.message ?: e::class.java.simpleName}"),
            )
        } finally {
            // P2-003：无论正常结束 / 失败 / 取消，都在这里真正结束 WebView 生命周期（幂等）。
            destroy(webView, session)
            AppLogRepository.i(LogTags.WEBVIEW, "WebView 已销毁")
        }
    }

    // ======================= WebView 构建 =======================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        collector: CandidateCollector,
        pageState: PageState,
        session: ParseSession,
    ): WebView {
        // P2-003：用 NonTouchableWebView —— 整屏透明覆盖层不再吞掉宿主 Compose UI 的触摸。
        val webView = NonTouchableWebView(context)
        webView.setBackgroundColor(0x00000000)

        // 解析用离屏 WebView：不挂 UI，但布局视口必须是设备真实尺寸。
        // 1×1 视口会让 lazy-loading / IntersectionObserver / srcset 候选 / 媒体查询全部失效
        // （浏览器按 1×1 视口选择资源、且视口外懒加载图永不触发），是上一轮多图拿不全的根因之一。
        val dm = context.resources.displayMetrics
        val vw = dm.widthPixels
        val vh = dm.heightPixels
        webView.layoutParams = android.view.ViewGroup.LayoutParams(vw, vh)
        runCatching {
            val ws = android.view.View.MeasureSpec.makeMeasureSpec(vw, android.view.View.MeasureSpec.EXACTLY)
            val hs = android.view.View.MeasureSpec.makeMeasureSpec(vh, android.view.View.MeasureSpec.EXACTLY)
            webView.measure(ws, hs)
            webView.layout(0, 0, vw, vh)
        }.onFailure { AppLogger.w("WebView 视口布局失败 err=${it.message}", LogTags.WEBVIEW) }
        AppLogRepository.i(LogTags.WEBVIEW, "WebView 离屏视口 ${vw}x${vh}（不挂 UI，仅布局）")

        val s = webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // P2-002：**必须要求用户手势才允许播放**。
            // 旧值 false 的注释写着「允许自动播放以便触发媒体请求（测试页需要）」—— 那是调试期开关，
            // 被 P7/P8 提升为正式解析路径后原样保留，直接后果是解析 WebView 会把页面音频外放到扬声器。
            // 改为 true（即系统默认）后页面无法自动播放；脚本侧另有「静音所有 video/audio」作为第二层保险。
            // 真机证据（审计）：VIDEO 页 40 次采样 `捕获=0`，即放开自动播放也从未真正捕获到媒体请求，
            // 故本改动不会损失任何"实际发生过"的媒体捕获（详见 implementation report 的回归验证项）。
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // WebView 私有数据目录（快夏自己的 Cookie 域），不访问其它应用
        CookieManager.getInstance().acceptCookie()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // P2-001（session 防护）：会话失效（已取消）后不再收集任何候选 —— 旧 session 的网络回调一律丢弃。
                // 仍返回 null（不接管请求），不影响 WebView 自身行为。
                // 闸门约定：本回调按「每个子资源」高频触发，故用不计数的 isValid；
                // droppedCallbacks 只统计内容回调（JS 桥 4 个入口 + onPageStarted/onPageFinished）。
                if (!session.isValid) return null
                // P2 diagnostic only：禁用收集逻辑以隔离 evaluateJavascript callback 行为。
                // 实验模式不 collect、不读 request headers；页面加载/生命周期照常。
                // 恢复正式行为：A_B_DISABLE_INTERCEPT 置 false。
                if (A_B_DISABLE_INTERCEPT) {
                    return super.shouldInterceptRequest(view, request)
                }
                // 仅观察 GET 请求；返回 null 表示不接管，响应由 WebView 正常处理。
                if (request.method.equals("GET", ignoreCase = true)) {
                    collector.offer(request)
                    // P0 业务修复：只读镜像 slidesinfo 接口请求（URL + 页面自身请求头），
                    // 供结果构建阶段复用页面请求头重放该接口（拿真实响应体 → 无水印 url_list）。
                    val reqUrl = request.url?.toString()
                    if (reqUrl != null && reqUrl.contains(SLIDESINFO_PATH)) {
                        pageState.onSlidesInfoRequest(
                            reqUrl,
                            runCatching { request.requestHeaders }.getOrDefault(emptyMap()),
                        )
                    }
                }
                return null
            }

            /**
             * 顶层导航 scheme 白名单：只允许 http/https 交还 WebView 继续加载；
             * 其余（snssdk1128 等 app scheme）一律拦截（返回 true），
             * 避免抖音 H5 的 app 唤起导航把当前 HTTPS 作品页替换成「网页无法打开」错误页。
             * 通用规则，不写死任何平台协议。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest,
            ): Boolean {
                val u = request.url?.toString() ?: return false
                return !isHttpLike(u)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val u = url ?: return false
                return !isHttpLike(u)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!session.allowCallback()) return
                AppLogRepository.i(LogTags.WEBVIEW, "页面加载开始 url=${LogSanitizer.sanitizeUrl(url)}")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // P2-001（session 防护）：会话失效后不再推进 pageState（否则晚到的 onPageFinished 会刷新
                // finishedAt/finalUrl，让收尾轮询误以为页面刚有新活动）。
                if (!session.allowCallback()) {
                    AppLogRepository.d(
                        LogTags.WEBVIEW,
                        "onPageFinished 丢弃（会话已失效） url=${LogSanitizer.sanitizeUrl(url)}",
                    )
                    return
                }
                pageState.onFinished(url)
                AppLogRepository.i(
                    LogTags.WEBVIEW,
                    "页面加载完成 title=${view?.title?.take(40)} url=${LogSanitizer.sanitizeUrl(url)}",
                )
                // A/B 实验（B 组）：完全不注入 PROBE_JS（无 scan/title interval），验证 evaluateJavascript
                // callback 是否因此恢复。仅实验版；恢复正式行为 = A_B_DISABLE_PROBE 置 false。
                if (A_B_DISABLE_PROBE) {
                    AppLogRepository.i(LogTags.WEBVIEW, "PAGE_JSON probe disabled for A/B")
                } else {
                    injectProbeJs(view)
                }
            }

            /**
             * 诊断专用（本轮只增证据，不改任何请求行为）：记录 WebView 自身对 douyinpic.com
             * 子资源的 HTTP 错误状态。`shouldInterceptRequest` 仍返回 null，响应由 Chromium 处理，
             * 本回调只是旁观，不会接管/重发/改写任何请求。
             *
             * 记录：statusCode / reasonPhrase / host / url（脱敏）；响应头**只记 key**，绝不打值。
             * TAG 用 HTTP 而非 WEBVIEW：HTTP 不属于 LogPolicy 的「详细类 tag」，
             * 即使误用「标准日志」也能保住这条关键证据。
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                try {
                    val req = request ?: return
                    if (req.isForMainFrame) return
                    val u = req.url ?: return
                    val host = u.host?.lowercase(Locale.ROOT).orEmpty()
                    if (!host.contains("douyinpic.com")) return
                    val headerKeys = errorResponse?.responseHeaders?.keys?.joinToString(",").orEmpty()
                    AppLogRepository.i(
                        LogTags.HTTP,
                        "WEBVIEW_HTTP_ERROR status=${errorResponse?.statusCode ?: -1} " +
                            "host=$host reason=${errorResponse?.reasonPhrase.orEmpty()} " +
                            "url=${LogSanitizer.sanitizeUrl(u.toString())} " +
                            "respHeaderKeys=[$headerKeys]",
                    )
                } catch (t: Throwable) {
                    // 诊断日志绝不影响业务
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?,
            ) {
                AppLogRepository.w(
                    LogTags.WEBVIEW,
                    "页面加载错误 code=$errorCode desc=${description?.take(120)} " +
                        "url=${LogSanitizer.sanitizeUrl(failingUrl)}",
                )
                if (failingUrl == view?.url || errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT ||
                    errorCode == ERROR_TIMEOUT || errorCode == ERROR_FAILED_SSL_HANDSHAKE
                ) {
                    // P2-001（session 防护）：会话失效后不再写 pageState（旧会话的 loadFailed 无意义且可能干扰收尾判断）。
                    if (session.isValid) {
                        pageState.loadFailed = "页面加载失败（${errorCode}）${description?.take(80) ?: ""}"
                    }
                }
            }
        }

        val bridge = KuaixiaJsBridge(collector, pageState, session)
        // P3 diagnostic only：条件化 addJavascriptInterface（隔离其对 evaluateJavascript callback 的影响）
        if (!A_B_DISABLE_JS_INTERFACE) {
            webView.addJavascriptInterface(bridge, JS_BRIDGE_NAME)
        }
        // P2 diagnostic only：配置完成时一次性输出（不随每个网络请求重复打印）
        if (A_B_DISABLE_INTERCEPT) {
            AppLogRepository.i(LogTags.WEBVIEW, "PAGE_JSON A/B intercept disabled")
        }
        // P3 diagnostic only：配置完成时一次性输出（证明本 WebView 未注入 JS interface）
        if (A_B_DISABLE_JS_INTERFACE) {
            AppLogRepository.i(LogTags.WEBVIEW, "PAGE_JSON A/B JavascriptInterface disabled")
        }
        // P4 diagnostic：记录 WebView 基础状态（保留历史日志能力；host attach 结果见下方 HiddenWebViewHost 行）
        val visName = when (webView.visibility) {
            android.view.View.VISIBLE -> "VISIBLE"
            android.view.View.INVISIBLE -> "INVISIBLE"
            else -> "GONE"
        }
        AppLogRepository.i(
            LogTags.WEBVIEW,
            "PAGE_JSON P4 webview state attached=${webView.isAttachedToWindow} visible=$visName size=${vw}x${vh}",
        )
        // P7 正式宿主：P5/P6 实验结论——WebView 必须 attach 到真实 Activity 窗口，renderer 生命周期才完整
        // （ApplicationContext 无 Window token → evaluateJavascript callback 永不返回；WindowManager 方案废弃）。
        // WebViewParser 不再直接处理 Activity/addContentView/WindowManager，统一交 HiddenWebViewHost。
        val hostResult = HiddenWebViewHost.attach(webView, vw, vh)
        AppLogRepository.i(LogTags.WEBVIEW, "PAGE_JSON HiddenWebViewHost ${hostResult.logLine()}")
        return webView
    }

    /** 注入本地受信探针 JS：轮询 video/audio 元素与 resource timing 上报媒体 URL 与标题。 */
    private fun injectProbeJs(view: WebView?) {
        if (view == null) return
        runCatching {
            view.post {
                view.evaluateJavascript(WebViewProbeScript.PROBE_JS, null)
            }
        }.onFailure { AppLogger.w("注入探针 JS 失败 err=${it.message}", LogTags.WEBVIEW) }
    }

    /**
     * 真正结束 WebView 生命周期（P2-001 / P2-002 / P2-003 的共同收尾点）。
     *
     * 相比旧实现的三处修复：
     * 1. **幂等**：经 [ParseSession.teardownOnce] 保证 cancel / finally / 超时 / Activity destroy
     *    多条路径重复调用只真正执行一次（旧实现无此保证）。
     * 2. **同步执行**：已在主线程时直接同步收尾，不再经 `mainHandler.post` 延迟 —— 旧实现
     *    `cancel()` 返回时 WebView 其实还活着（这正是"取消后仍在播/仍挡触摸"的直接原因）。
     * 3. **逐步独立 runCatching**：旧实现把 6 个调用塞进**一个** `runCatching`，任何一步抛异常
     *    都会让后面的 `detach` / `destroy()` 全部跳过（WebView 就永久留在窗口上）。
     *    现在每一步互不影响，`detach` 与 `destroy()` 一定被执行到。
     *
     * 顺序与理由：
     * ```
     * stopLoading()                    // 停掉在途请求
     * evaluateJavascript(STOP_JS)      // 清掉探针的 scan/title interval（P2-001 timer 清理）
     * loadUrl("about:blank")           // 卸载文档 → 页面 <video>/<audio> 元素随之销毁，媒体停止
     * onPause()                        // 辅助：暂停渲染/定时（不作为音频停止的唯一手段）
     * removeJavascriptInterface()      // 摘掉桥，之后页面再调也进不来
     * HiddenWebViewHost.detach()       // 从 Activity 窗口摘除（透明覆盖层消失 → 触摸立刻恢复）
     * removeAllViews() / destroy()     // 释放 renderer
     * ```
     * 注：不使用 `pauseTimers()` —— 它是**进程级**开关，会连带影响登录 WebView 等其它 WebView，
     * 与"最小副作用"冲突。
     */
    private fun destroy(webView: WebView, session: ParseSession) {
        val first = session.teardownOnce {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                teardown(webView)
            } else {
                // 不在主线程（理论上不会走到；parse() 全程在 Dispatchers.Main）→ 投递到主线程执行。
                // 注：post 在消息队列已退出时会返回 false，此时本次收尾被丢弃；这是"进程/宿主即将销毁"
                // 的极端场景，与旧行为一致，属可接受降级（正常路径走上面的同步分支）。
                runCatching { mainHandler.post { teardown(webView) } }
            }
        }
        if (!first) {
            AppLogRepository.d(LogTags.WEBVIEW, "teardown 已执行过，跳过（幂等）")
        }
    }

    /** 实际的收尾动作序列：每一步独立捕获异常，保证后续步骤不被跳过。 */
    private fun teardown(webView: WebView) {
        // 1) 清探针 interval（必须在 destroy 之前；destroy 后 renderer 已消失，JS 不再执行）
        runCatching { webView.evaluateJavascript(WebViewProbeScript.PROBE_STOP_JS, null) }
            .onFailure { AppLogger.w("停止探针失败 err=${it.message}", LogTags.WEBVIEW) }
        // 2) 停加载 + 卸载文档（页面的 video/audio 元素随之销毁 → 媒体停止）
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        // 3) 暂停渲染/页面定时
        runCatching { webView.onPause() }
        // 4) 摘桥
        runCatching { webView.removeJavascriptInterface(JS_BRIDGE_NAME) }
        // 5) 从宿主窗口摘除（透明覆盖层消失 → 宿主 UI 触摸立即恢复）
        runCatching { HiddenWebViewHost.detach(webView) }
            .onFailure { AppLogger.w("detach 失败 err=${it.message}", LogTags.WEBVIEW) }
        // 6) 释放
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
            .onFailure { AppLogger.w("destroy 失败 err=${it.message}", LogTags.WEBVIEW) }
    }

    // ======================= 收集器 =======================

    private inner class CandidateCollector {
        private val seen = ConcurrentHashMap.newKeySet<String>()
        private val list = java.util.Collections.synchronizedList(mutableListOf<MediaCandidate>())
        private val seenImages = ConcurrentHashMap.newKeySet<String>()
        private val images = java.util.Collections.synchronizedList(mutableListOf<ImageResource>())

        @Volatile
        var lastCandidateAt: Long = System.currentTimeMillis()
            private set

        /** 疑似图片类请求数（单 URL 一次；含被过滤项，用于诊断）。 */
        @Volatile
        var imageAttempts: Int = 0
            private set

        /** 过滤原因计数：NAVIGATION / FAVICON / AVATAR / ICON / LOGO / NOISE / UNKNOWN。 */
        private val imageFilterReasons = ConcurrentHashMap<String, Int>()

        fun offer(request: WebResourceRequest) {
            offerUrl(request.url?.toString() ?: return, request.requestHeaders)
        }

        /** JS bridge / 无请求头场景的 URL 上报。 */
        fun offerUrl(url: String, requestHeaders: Map<String, String> = emptyMap()) {
            val candidate = classify(url, requestHeaders)
            if (candidate == null) {
                // 图片类：保存真实请求（URL 必须来自实际捕获，绝不按 ID 拼接）
                if (isImageLikeAttempt(url, requestHeaders)) captureImage(url, requestHeaders)
                return
            }
            val key = url.substringBefore('#')
            if (seen.add(key)) {
                list += candidate
                lastCandidateAt = System.currentTimeMillis()
                AppLogRepository.d(
                    LogTags.WEBVIEW,
                    "捕获候选 ${candidate.kind.label} url=${LogSanitizer.sanitizeUrl(url)} " +
                        "headers=[${LogSanitizer.headerKeys(candidate.requestHeaders)}]",
                )
            }
        }

        /** 真实图片入库：门控过滤（导航/噪音/非媒体主机）后保存，保持捕获顺序。 */
        private fun captureImage(url: String, requestHeaders: Map<String, String>) {
            val cleanUrl = url.substringBefore('#')
            if (!seenImages.add(cleanUrl)) return
            imageAttempts++
            val reason = imageGateReason(cleanUrl, requestHeaders)
            if (reason != null) {
                imageFilterReasons.merge(reason, 1) { a, b -> a + b }
                return
            }
            val headers = requestHeaders.filterKeys { k ->
                !k.equals("range", true) && !k.equals("cookie", true)
            }
            images += buildImageResource(cleanUrl, headers)
            lastCandidateAt = System.currentTimeMillis()
            val mime = buildImageResource(cleanUrl, headers).mimeType ?: "?"
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "捕获图片 url=${LogSanitizer.sanitizeUrl(cleanUrl)} source=NETWORK mimeHint=$mime " +
                    "headers=[${LogSanitizer.headerKeys(headers)}]",
            )
        }

        /** JS/DOM 只读扫描提供的页面图片：与网络捕获统一去重/统一过门控；
         *  扩展名缺失时只要命中媒体主机且无噪音即接受（页面真实提供的 URL，不做任何改写）。
         *  @param sourceLabel 来源标签（DOM_SRC/DOM_SRCSET/DOM_DATA；null=旧 DOM 单 URL 通道），仅用于日志。 */
        fun offerPageImage(url: String, sourceLabel: String? = null) {
            val clean = url.substringBefore('#').trim()
            if (clean.isEmpty()) return
            if (!seenImages.add(clean)) return
            imageAttempts++
            val reason = imageGateReason(clean, emptyMap(), pageProvided = true)
            if (reason != null) {
                imageFilterReasons.merge(reason, 1) { a, b -> a + b }
                if (sourceLabel != null) {
                    AppLogRepository.d(
                        LogTags.WEBVIEW,
                        "DOM 图片被过滤 source=${sourceLabel} reason=$reason tpl=${templateToken(clean)}",
                    )
                }
                return
            }
            images += buildImageResource(clean, emptyMap())
            lastCandidateAt = System.currentTimeMillis()
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "捕获页面图片 url=${LogSanitizer.sanitizeUrl(clean)} source=${sourceLabel ?: "DOM"} " +
                    "mimeHint=${images.lastOrNull()?.mimeType ?: "?"}",
            )
        }

        /** DOM 元素级候选组（一图多档）：组内选优后统一走 [offerPageImage]（同一 gate/去重/入库）。 */
        fun offerDomImageGroup(input: DomImageGroup.Input) {
            val entries = DomImageGroup.collect(input)
            if (entries.isEmpty()) return
            val best = DomImageGroup.pickBest(entries) ?: return
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "DOM 图片组候选=${entries.size} 明细=[" + entries.joinToString("; ") { domCandidateSummary(it) } + "]",
            )
            offerPageImage(best.url, sourceLabel = best.source)
            AppLogRepository.d(LogTags.WEBVIEW, "DOM 图片组 selected ${domCandidateSummary(best)}")
        }

        /** JS 探针每帧统计（不含 URL）。 */
        fun logDomProbe(imgCount: Int, candidateCount: Int, reported: Int) {
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "DOM probe imgCount=$imgCount candidateCount=$candidateCount reported=$reported",
            )
        }

        /** DOM 候选摘要（不打印 URL/签名，只出模板/尺寸/来源/水印提示）。 */
        private fun domCandidateSummary(e: DomImageGroup.Entry): String {
            val wm = when (ImageUrlHints.watermarkHint(e.url)) {
                true -> "true"
                false -> "false"
                null -> "unknown"
            }
            val (w, h) = ImageUrlHints.sizeFromUrl(e.url)
            return "src=${e.source} wm=$wm size=${w ?: "?"}x${h ?: "?"} tpl=${templateToken(e.url)}"
        }

        fun candidates(): List<MediaCandidate> = synchronized(list) { list.toList() }

        fun imageResources(): List<ImageResource> = synchronized(images) { images.toList() }

        fun imageCount(): Int = synchronized(images) { images.size }

        fun size(): Int = synchronized(list) { list.size }

        /** 过滤统计摘要（只记数量，无 URL/Cookie）。 */
        fun imageFilterSummary(): String {
            val reasons = imageFilterReasons.entries.sortedByDescending { it.value }
                .joinToString(",") { "${it.key}:${it.value}" }
            val accepted = images.size
            val filtered = (imageAttempts - accepted).coerceAtLeast(0)
            return "acceptedImages=$accepted filteredImages=$filtered" +
                (if (reasons.isNotEmpty()) " reasons=[$reasons]" else "")
        }

        /** 排序/精简：m3u8 存在则丢弃 ts/m4s 分段候选（交由 M3U8Downloader 全量拉取）。 */
        fun finalizeCandidates(): List<MediaCandidate> {
            val all = candidates()
            val hasHls = all.any { it.kind == MediaKind.HLS }
            return all
                .filter { !(hasHls && it.kind == MediaKind.SEGMENT) }
                .filter { it.kind != MediaKind.UNKNOWN }
                .sortedWith(
                    compareBy<MediaCandidate> {
                        // 直接可下（mp4/webm）> HLS > 分段 > 音频
                        when (it.kind) {
                            MediaKind.VIDEO -> 0
                            MediaKind.HLS -> 1
                            MediaKind.SEGMENT -> 2
                            MediaKind.AUDIO -> 3
                            MediaKind.UNKNOWN -> 4
                        }
                    }.thenBy { it.captureOrder },
                )
                .distinctBy { it.url.substringBefore('#') }
        }
    }

    private val IMAGE_EXTENSIONS =
        listOf("jpg", "jpeg", "png", "webp", "avif", "gif", "apng", "heic", "heif")

    private fun acceptHeader(headers: Map<String, String>): String =
        headers.entries
            .firstOrNull { it.key.equals("accept", true) }?.value?.lowercase(Locale.ROOT).orEmpty()

    /** Guess mime/extension hints from url/accept (final mime decided by HTTP response). */
    private fun buildImageResource(url: String, headers: Map<String, String>): ImageResource {
        val path = runCatching { Uri.parse(url).path?.lowercase(Locale.ROOT) }.getOrNull() ?: ""
        val accept = acceptHeader(headers)
        val extByPath = IMAGE_EXTENSIONS.firstOrNull { path.endsWith(".$it") }
        if (extByPath != null) {
            return ImageResource(url, "image/$extByPath", extByPath, headers)
        }
        val (hintExt, hintMime) = when {
            accept.contains("image/avif") -> "avif" to "image/avif"
            accept.contains("image/webp") -> "webp" to "image/webp"
            accept.contains("image/png") -> "png" to "image/png"
            accept.contains("image/apng") -> "apng" to "image/apng"
            accept.contains("image/gif") -> "gif" to "image/gif"
            else -> "jpg" to "image/jpeg"
        }
        return ImageResource(url, hintMime, hintExt, headers)
    }

    /** 泛化「疑似图片请求」探测（Accept 仅作尝试信号；入库仍需 gate）。 */
    private fun isImageLikeAttempt(url: String, requestHeaders: Map<String, String>): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val path = uri.path?.lowercase(Locale.ROOT) ?: ""
        if (IMAGE_EXTENSIONS.any { path.endsWith(".$it") }) return true
        val accept = acceptHeader(requestHeaders)
        return accept.contains("image/") || isLikelyMediaHost(uri.host)
    }

    /** Douyin page hosts must never enter image candidates. */
    private fun isPageHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == "douyin.com" || h == "iesdouyin.com" ||
            h.endsWith(".douyin.com") || h.endsWith(".iesdouyin.com")
    }

    /** Image CDN hosts (auxiliary signal only). */
    private fun isLikelyMediaHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h.endsWith("douyinpic.com") || h.endsWith("snssdk.com") ||
            h.endsWith("byteimg.com") || h.endsWith("bytecdn.cn") ||
            h.endsWith("ibytedtos.com") || h.endsWith("bytecdntp.com")
    }

    /** 图片入库门控：null=放行；否则为过滤分类。绝不只凭 Accept。 */
    private fun imageGateReason(
        url: String,
        headers: Map<String, String>,
        pageProvided: Boolean = false,
    ): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return "UNKNOWN"
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return "UNKNOWN"
        if (isPageHost(uri.host)) return "NAVIGATION"
        val path = uri.path?.lowercase(Locale.ROOT) ?: ""
        val noise = when {
            path.contains("favicon") || path.contains(".ico") -> "FAVICON"
            path.contains("aweme-avatar") || path.contains("avatar") -> "AVATAR"
            path.contains("webapp") || path.contains("sprite") || path.contains("badge") ||
                path.contains("emoji") || path.contains("icon") || path.contains("d_icon") -> "ICON"
            path.contains("logo") -> "LOGO"
            path.contains("100x100") || path.contains("/misc/") || path.contains("/music") ||
                path.contains("/svg") -> "NOISE"
            else -> null
        }
        if (noise != null) return noise
        if (IMAGE_EXTENSIONS.any { path.endsWith(".$it") }) return null
        // DOM/页面数据源：无请求头可依，页面真实 URL + 媒体主机即为有效证据（仍排除页面/噪音主机）
        if (pageProvided) {
            return if (isLikelyMediaHost(uri.host)) null else "UNKNOWN"
        }
        val accept = acceptHeader(headers)
        return if (accept.contains("image/") && isLikelyMediaHost(uri.host)) null else "UNKNOWN"
    }

    /** 分类：URL 扩展名 + Accept + 特例（/videoplayback）。拿不到响应 MIME，按请求侧信息推断。 */
    private fun classify(url: String, requestHeaders: Map<String, String>): MediaCandidate? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val path = uri.path?.lowercase(Locale.ROOT) ?: ""
        val accept = requestHeaders.entries
            .firstOrNull { it.key.equals("accept", true) }?.value?.lowercase(Locale.ROOT) ?: ""

        val kind = when {
            path.endsWith(".m3u8") -> MediaKind.HLS
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") ||
                path.endsWith(".webm") -> MediaKind.VIDEO
            path.endsWith(".ts") -> MediaKind.SEGMENT
            path.endsWith(".m4s") -> MediaKind.SEGMENT
            path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
                path.endsWith(".ogg") || path.endsWith(".opus") || path.endsWith(".wav") ||
                path.endsWith(".flac") -> MediaKind.AUDIO
            // 无扩展名 CDN 视频（如 /videoplayback、/media/video/xxx）
            path.contains("videoplayback") || path.contains("/play/") || path.contains("media/") ->
                if (accept.contains("video/") || accept.contains("audio/")) {
                    if (accept.contains("audio/")) MediaKind.AUDIO else MediaKind.VIDEO
                } else MediaKind.UNKNOWN
            accept.contains("video/mp4") || accept.contains("video/webm") -> MediaKind.VIDEO
            accept.contains("audio/mp4") || accept.contains("audio/mpeg") || accept.contains("audio/webm") ->
                MediaKind.AUDIO
            accept.contains("application/vnd.apple.mpegurl") || accept.contains("application/x-mpegurl") ->
                MediaKind.HLS
            else -> null
        } ?: return null

        val headers = requestHeaders.filterKeys { k ->
            !k.equals("range", true) && !k.equals("cookie", true) // Cookie 由 CookieManager 管理，另行附加
        }
        val mime = when (kind) {
            MediaKind.HLS -> "application/vnd.apple.mpegurl"
            MediaKind.SEGMENT -> if (path.endsWith(".m4s")) "video/iso.segment" else "video/mp2t"
            MediaKind.VIDEO -> mimeByExt(path)
            MediaKind.AUDIO -> audioMimeByExt(path)
            MediaKind.UNKNOWN -> null
        }
        return MediaCandidate(
            url = url,
            kind = kind,
            mimeHint = mime,
            requestHeaders = headers,
            captureOrder = captureOrderCounter.incrementAndGet(),
        )
    }

    private val captureOrderCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 页面 <video>/<audio> 与 resource timing 的 JS 上报入口。
     *
     * P2-001（session 防护）：本桥**不走协程**，因此 `cont.isActive` 那套取消防护对 `addJavascriptInterface`
     * 回调无效 —— 只能靠 [session] 闸门。会话一旦失效（用户取消），这里所有上报一律丢弃，
     * 保证「旧 session callback 晚到」不会写入 collector / pageState。
     */
    private inner class KuaixiaJsBridge(
        private val collector: CandidateCollector,
        private val pageState: PageState,
        private val session: ParseSession,
    ) {
        @android.webkit.JavascriptInterface
        fun onMedia(url: String) {
            if (!session.allowCallback()) return
            if (url.isBlank()) return
            collector.offerUrl(url)
        }

        @android.webkit.JavascriptInterface
        fun onTitle(title: String) {
            if (!session.allowCallback()) return
            pageState.title = title.take(120)
        }

        @android.webkit.JavascriptInterface
        fun onImage(url: String) {
            if (!session.allowCallback()) return
            if (url.isBlank()) return
            collector.offerPageImage(url)
        }

        /** DOM `<img>` 元素整组候选（多档一次上报，原生侧组内选优）。空串=无。 */
        @android.webkit.JavascriptInterface
        fun onImageGroup(
            src: String?,
            currentSrc: String?,
            srcset: String?,
            dataRaw: String?,
            baseHref: String?,
        ) {
            if (!session.allowCallback()) return
            collector.offerDomImageGroup(
                DomImageGroup.Input(
                    src = src?.takeIf { it.isNotEmpty() },
                    currentSrc = currentSrc?.takeIf { it.isNotEmpty() },
                    srcset = srcset?.takeIf { it.isNotEmpty() },
                    dataRaw = dataRaw?.takeIf { it.isNotEmpty() },
                    baseHref = baseHref?.takeIf { it.isNotEmpty() },
                ),
            )
        }

        /** JS 探针扫描统计（img 总数 / 有候选 img 数 / 本帧新增组数）。 */
        @android.webkit.JavascriptInterface
        fun onDomProbe(imgCount: Int, candidateCount: Int, reported: Int) {
            if (!session.isValid) return
            collector.logDomProbe(imgCount, candidateCount, reported)
        }

        /**
         * 诊断（只读，不参与业务）：DOM `<img>` 元素的**加载状态**上报。
         *
         * 回答的问题：Chromium 自己有没有成功加载/解码某张图（`complete=true` 且 `naturalWidth>0`）。
         * JS 侧 payload 为多行、以记录分隔符拼接，每行字段以单元分隔符拼接，字段依次为
         * via / index / url / complete(1|0) / naturalWidth / naturalHeight。
         *
         * 只读取 `img.complete` / `img.naturalWidth` / `img.naturalHeight`，不读像素、不改 DOM、
         * 不替换 src、不触发重新加载。整段包 try-catch：诊断日志绝不影响业务。
         */
        @android.webkit.JavascriptInterface
        fun onImgStates(payload: String?) {
            // P2-001（session 防护）：会话失效后不再产生任何上报（含诊断日志），避免取消后日志被旧页面持续刷屏。
            if (!session.isValid) return
            if (payload.isNullOrEmpty()) return
            try {
                var logged = 0
                for (line in payload.split('\u001E')) {
                    if (logged >= IMG_STATE_MAX_PER_REPORT) break
                    val f = line.split('\u001F')
                    if (f.size < 6) continue
                    val raw = f[2]
                    if (raw.isEmpty()) continue
                    val host = runCatching { java.net.URI(raw).host }.getOrNull().orEmpty()
                    AppLogRepository.i(
                        LogTags.HTTP,
                        "WEBVIEW_IMG_STATE index=${f[1]} via=${f[0]} host=$host " +
                            "complete=${f[3]} naturalWidth=${f[4]} naturalHeight=${f[5]} " +
                            "url=${LogSanitizer.sanitizeUrl(raw)}",
                    )
                    logged++
                }
            } catch (t: Throwable) {
                // 诊断日志绝不影响业务
            }
        }
    }

    private inner class PageState {
        @Volatile
        var title: String? = null

        @Volatile
        var finalUrl: String? = null

        @Volatile
        var finishedAt: Long = 0L

        @Volatile
        var loadFailed: String? = null

        // P0 业务修复：slidesinfo 接口请求的只读镜像（URL + 页面自身请求头）
        @Volatile
        var slidesInfoUrl: String? = null
            private set

        @Volatile
        var slidesInfoHeaders: Map<String, String> = emptyMap()
            private set

        fun onSlidesInfoRequest(url: String, headers: Map<String, String>) {
            if (slidesInfoUrl == null) {
                slidesInfoUrl = url
                slidesInfoHeaders = headers
            }
        }

        fun onFinished(url: String?) {
            finalUrl = url
            finishedAt = System.currentTimeMillis()
        }
    }

    // ======================= 工具 =======================

    /** 从 URL 推断清晰度标签（`height` 未知时的旧兜底逻辑）；与 PC 链路共用同一实现，避免两套漂移。 */
    private fun qualityFromUrl(url: String): String? =
        com.kuaixia.app.data.parser.douyin.qualityHintFromUrl(url)

    /**
     * 读取页面运行时 JSON 的作品图片高清候选（PAGE_JSON 数据源）。
     *
     * 采用【短周期轮询 + 总硬超时】：PAGE_JSON 数据（_ROUTER_DATA → loaderData →
     * page.videoInfoRes.item_list[].images[].url_list）可能在 onPageFinished 后延迟数百毫秒
     * 才就绪（外部下载工具亦需等待），故每 [pollIntervalMs] 轻量重读一次
     * （每次 picker 为定向浅扫、毫秒级），命中即返回；总等待不超过 [totalWaitMs]。
     *
     * P8：IMAGE 页沿用 [PAGE_JSON_POLL_INTERVAL_MS]/[PAGE_JSON_TOTAL_WAIT_MS]；
     * VIDEO 页用独立 [VIDEO_PAGE_JSON_POLL_INTERVAL_MS]/[VIDEO_PAGE_JSON_TOTAL_WAIT_MS]（更快收尾）。
     * 任何失败/超时/异常一律返回 null（调用方回退 NETWORK/DOM），**绝不永久等待**。
     */
    private suspend fun readPageJsonImages(
        view: WebView?,
        pollIntervalMs: Long = PAGE_JSON_POLL_INTERVAL_MS,
        totalWaitMs: Long = PAGE_JSON_TOTAL_WAIT_MS,
    ): List<PageJsonMerge.JsonImage>? {
        if (view == null) return null
        val js = runCatching { context.assets.open("page_json_picker.js").bufferedReader().use { it.readText() } }
            .getOrNull()
        if (js == null) {
            AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON skipped（picker 资源缺失）→ 使用 NETWORK/DOM")
            return null
        }
        AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON poll start")
        val started = System.currentTimeMillis()
        // Debug 诊断：一次性 routerProbe，证明当前 document 是否存在 _ROUTER_DATA（不影响业务结果）
        runCatching {
            probeRouterData(view)
        }
        // P4 diagnostic only：独立 "1+1" evaluate 通道自检（不进解析流程，仅验证 renderer 能否回调）
        if (A_B_EVAL_SANITY_TEST) {
            runCatching {
                evalSanity1Plus1(view)
            }
        }
        // A/B 实验（单次长等待组）：只发起一次 evaluate，等 callback ≤ SINGLE_EVALUATE_WAIT_MS，
        // 不 150ms 重发；callback 回来即结束。false = 旧 5s 轮询模型（保留可恢复）。
        if (A_B_SINGLE_EVALUATE) {
            return singleEvaluatePageJson(view, js, started)
        }
        var attempt = 0
        while (true) {
            val remain = totalWaitMs - (System.currentTimeMillis() - started)
            if (remain <= 0) break
            attempt++
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "PAGE_JSON poll attempt=$attempt remaining=${remain}ms",
            )
            val attemptStart = System.currentTimeMillis()
            AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON evaluate start attempt=$attempt")
            val raw = readEvaluate(view, js, minOf(EVALUATE_TIMEOUT_MS, remain), attempt)
            val attemptElapsed = System.currentTimeMillis() - attemptStart
            if (raw != null) {
                val hit = parsePageJsonPayload(raw)
                logPickerMeta(raw)
                val hasImages = raw.contains("\"images\"")
                AppLogRepository.d(
                    LogTags.WEBVIEW,
                    "PAGE_JSON callback attempt=$attempt rawLen=${raw.length} elapsed=${attemptElapsed}ms " +
                        "hasImages=$hasImages parsed=${hit?.size ?: 0}",
                )
                if (!hit.isNullOrEmpty()) {
                    AppLogRepository.i(
                        LogTags.WEBVIEW,
                        "PAGE_JSON poll hit elapsed=${System.currentTimeMillis() - started}ms images=${hit.size}",
                    )
                    return hit
                }
            } else {
                AppLogRepository.d(
                    LogTags.WEBVIEW,
                    "PAGE_JSON callback attempt=$attempt NO_CALLBACK elapsed=${attemptElapsed}ms",
                )
            }
            val remain2 = totalWaitMs - (System.currentTimeMillis() - started)
            if (remain2 <= 0) break
            delay(minOf(pollIntervalMs, remain2))
        }
        AppLogRepository.w(
            LogTags.WEBVIEW,
            "PAGE_JSON timeout elapsed=${System.currentTimeMillis() - started}ms → fallback NETWORK/DOM",
        )
        return null
    }

    /**
     * P0 业务修复：读取 `/share/slides/` 页的 slidesinfo 图片候选。
     *
     * 前提（真机证据）：slides 页没有 `_ROUTER_DATA`，PAGE_JSON 恒空；真正的图片数据在
     * `/web/api/v2/aweme/slidesinfo/`，且必须复用页面自身请求头才能拿到真实响应体。
     * 本方法复用 [PageState] 里只读镜像到的 slidesinfo 请求 URL + 页面请求头做一次重放，
     * 解析出作品图片列表（同 [PageJsonMerge.JsonImage] 结构），交上游走 [PageJsonMerge.merge] 归并。
     *
     * 失败语义：未捕获请求 / 请求失败 / 空体 / 无 images → 一律返回 null，上游回退 NETWORK/DOM，
     * **绝不因 slidesinfo 失败而中断整体解析**。
     */
    private suspend fun readSlidesInfoImages(pageState: PageState): List<PageJsonMerge.JsonImage>? {
        val apiUrl = pageState.slidesInfoUrl
        if (apiUrl == null) {
            AppLogRepository.d(LogTags.WEBVIEW, "SLIDESINFO skipped（页面未发起 slidesinfo 请求）→ 使用 NETWORK/DOM")
            return null
        }
        AppLogRepository.i(LogTags.WEBVIEW, "SLIDESINFO fetch start")
        val body = SlidesInfoFetcher.fetch(apiUrl, pageState.slidesInfoHeaders)
        if (body == null) {
            AppLogRepository.w(LogTags.WEBVIEW, "SLIDESINFO fetch 失败/空 → fallback NETWORK/DOM")
            return null
        }
        val images = SlidesInfoParser.parse(body)
        if (images.isEmpty()) {
            AppLogRepository.w(LogTags.WEBVIEW, "SLIDESINFO 无 aweme_details[0].images → fallback NETWORK/DOM")
            return null
        }
        // 日志只出模板/水印/候选统计，不打完整 URL（LogSanitizer 仍会兜底脱敏签名 query）
        val tpl = HashMap<String, Int>()
        var nonWater = 0
        var water = 0
        images.forEach { j ->
            j.urls.forEach { u ->
                when (ImageUrlHints.watermarkHint(u)) {
                    true -> water++
                    false -> nonWater++
                    else -> Unit
                }
                tpl.merge(templateToken(u), 1) { a, b -> a + b }
            }
        }
        AppLogRepository.i(
            LogTags.WEBVIEW,
            "SLIDESINFO ok images=${images.size} urls=${images.sumOf { it.urls.size }} " +
                "nonWater=$nonWater water=$water " +
                "tpl=[" + tpl.entries.joinToString(" ") { "${it.key}=${it.value}" } + "]",
        )
        return images
    }

    /**
     * P4 diagnostic only：最小 evaluateJavascript 通道自检——对当前 WebView 执行 "1+1"，
     * 独立 5s 超时（不复用 PAGE_JSON 超时/回调路径），只验证 renderer 能否执行 JS 并回调。
     * 结果仅日志（start / callback value / timeout），不进解析流程。
     */
    private suspend fun evalSanity1Plus1(view: WebView) {
        AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON P5 sanity start")
        AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON P4 eval sanity start")
        val t0 = System.currentTimeMillis()
        val raw = readEvaluate(view, "1+1", 5_000L, attempt = 0)
        val elapsed = System.currentTimeMillis() - t0
        if (raw != null) {
            val value = if (raw.startsWith("\"")) {
                runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrNull() ?: raw
            } else raw
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "PAGE_JSON P4 eval sanity callback value=${value.take(60)} elapsed=${elapsed}ms",
            )
        } else {
            AppLogRepository.w(
                LogTags.WEBVIEW,
                "PAGE_JSON P4 eval sanity timeout elapsed=${elapsed}ms",
            )
        }
    }

    /**
     * A/B 实验（单次长等待组）：对齐 Debug Collector 核心行为——只 evaluateJavascript 一次，
     * 持续等 callback ≤ [SINGLE_EVALUATE_WAIT_MS]（25s 实验窗口），不 150ms 重发、无短超时弃等。
     * 命中即返回；callback 未回/返回空 → null（fallback NETWORK/DOM）。不做任何其它行为改动。
     */
    private suspend fun singleEvaluatePageJson(
        view: WebView,
        js: String,
        started: Long,
    ): List<PageJsonMerge.JsonImage>? {
        AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON single-evaluate mode（等 ≤${SINGLE_EVALUATE_WAIT_MS}ms，仅发一次，不重发）")
        // P4 diagnostic only：实际 evaluate 前额外等待，验证「onPageFinished 后立即 evaluate 是否过早」
        if (P4_DELAY_BEFORE_EVALUATE_MS > 0) {
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "PAGE_JSON P4 delayed evaluate wait=$P4_DELAY_BEFORE_EVALUATE_MS",
            )
            delay(P4_DELAY_BEFORE_EVALUATE_MS)
        }
        val attemptStart = System.currentTimeMillis()
        AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON evaluate start attempt=1")
        val raw = readEvaluate(view, js, SINGLE_EVALUATE_WAIT_MS, attempt = 1)
        val attemptElapsed = System.currentTimeMillis() - attemptStart
        if (raw != null) {
            val hit = parsePageJsonPayload(raw)
            logPickerMeta(raw)
            val hasImages = raw.contains("\"images\"")
            AppLogRepository.d(
                LogTags.WEBVIEW,
                "PAGE_JSON callback attempt=1 rawLen=${raw.length} elapsed=${attemptElapsed}ms " +
                    "hasImages=$hasImages parsed=${hit?.size ?: 0}",
            )
            if (!hit.isNullOrEmpty()) {
                AppLogRepository.i(
                    LogTags.WEBVIEW,
                    "PAGE_JSON single-evaluate hit elapsed=${System.currentTimeMillis() - started}ms images=${hit.size}",
                )
                return hit
            }
            AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON single-evaluate 无图片字段 → fallback NETWORK/DOM")
            return null
        }
        AppLogRepository.d(
            LogTags.WEBVIEW,
            "PAGE_JSON callback attempt=1 NO_CALLBACK elapsed=${attemptElapsed}ms",
        )
        AppLogRepository.w(
            LogTags.WEBVIEW,
            "PAGE_JSON single-evaluate timeout elapsed=${System.currentTimeMillis() - started}ms → fallback NETWORK/DOM",
        )
        return null
    }

    /** Debug 诊断：轻量探针，输出 typeof _ROUTER_DATA + loaderData 顶层 keys（只读，不影响业务）。 */
    private suspend fun probeRouterData(view: WebView) {
        val js = "(function(){var R=window._ROUTER_DATA||window.__ROUTER_DATA__||window.ROUTER_DATA;" +
            "var ld=R&&R.loaderData;var rk=[],lk=[];var c=0;try{for(var k in R){if(c++<20)rk.push(k);else break;}}catch(e){}" +
            "c=0;try{for(var k2 in ld){if(c++<20)lk.push(k2);else break;}}catch(e){}" +
            "return JSON.stringify({t:typeof R,rk:rk,lk:lk});})();"
        val raw = readEvaluate(view, js, minOf(EVALUATE_TIMEOUT_MS, 800L))
        val probe = raw?.let {
            if (it.startsWith("\"")) {
                runCatching { org.json.JSONTokener(it).nextValue().toString() }.getOrNull()
            } else it
        } ?: "NO_PROBE"
        AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON routerProbe $probe")
    }

    /**
     * P8.1 正式接入：读取 VIDEO 页 PAGE_JSON video 字段候选（assets/video_picker.js 定向提取
     * video.play_addr.url_list / download_addr.url_list，仅 http/https）。
     * 保守纪律：URL 一律来自页面真实数据、不改写；playwm 等只由 [VideoJsonCandidate.watermarkHint]
     * 标注（绝不宣称无水印）；无候选返回空 → 调用方保持捕获候选 → route FAIL → yt-dlp fallback。
     */
    private suspend fun readVideoJsonCandidates(
        view: WebView,
        capturedUrls: Set<String>,
    ): List<VideoJsonCandidate> {
        val js = runCatching { context.assets.open("video_picker.js").bufferedReader().use { it.readText() } }
            .getOrNull()
        if (js == null) {
            AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON VIDEO skipped（video_picker 资源缺失）")
            return emptyList()
        }
        val raw = readEvaluate(view, js, minOf(EVALUATE_TIMEOUT_MS, 1_000L))
        val text = raw?.let {
            if (it.startsWith("\"")) {
                runCatching { org.json.JSONTokener(it).nextValue().toString() }.getOrNull()
            } else it
        } ?: run {
            AppLogRepository.w(LogTags.WEBVIEW, "PAGE_JSON VIDEO read timeout/error → 无候选")
            return emptyList()
        }
        val refs = runCatching {
            val o = JSONObject(text)
            val items = o.optInt("items", 0)
            val play = o.optInt("playUrls", 0)
            val dl = o.optInt("downloadUrls", 0)
            val keys = o.optJSONArray("videoKeys")?.let { a ->
                (0 until a.length()).joinToString(",") { a.optString(it) }
            } ?: ""
            // P8.2：bit_rate[] 审计（字段存在性/命名确认用；不参与任何判定）
            val brCount = o.optInt("bitRateCount", 0)
            val brKeys = o.optJSONArray("bitRateKeys")?.let { a ->
                (0 until a.length()).joinToString(",") { a.optString(it) }
            } ?: ""
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "PAGE_JSON VIDEO 字段审计 items=$items playUrls=$play downloadUrls=$dl " +
                    "bitRateCount=$brCount videoKeys=[$keys] bitRateKeys=[$brKeys]",
            )
            val arr = o.optJSONArray("refs") ?: return@runCatching emptyList()
            buildList {
                for (j in 0 until arr.length()) {
                    val e = arr.optJSONObject(j) ?: continue
                    val field = e.optString("field")
                    val url = e.optString("url")
                    if (url.isNotBlank()) {
                        add(
                            VideoJsonRef(
                                field = field,
                                url = url,
                                // org.json 的 optString 对 JSON null 返回 "null" 字面量 → 统一视为缺失
                                source = e.optString("source").takeIf { it.isNotBlank() && it != "null" },
                                gear = e.optString("gear").takeIf { it.isNotBlank() && it != "null" },
                                // 数值字段：缺失/非正一律 null（与 JS isFinite 校验一致，不猜测）
                                width = e.optInt("width", 0).takeIf { it > 0 },
                                height = e.optInt("height", 0).takeIf { it > 0 },
                                bitrate = e.optDouble("bitrate", 0.0).takeIf { it > 0 && it.isFinite() },
                                dataSize = e.optLong("dataSize", 0L).takeIf { it > 0 },
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        val sel = VideoJsonMedia.collectRefs(refs, capturedUrls)
        // P8.2 汇总（解析诊断）：bit_rate 档位候选数 / 最高 height / 最高码率 / 候选总数
        val brCandidates = sel.count { it.fromBitRate }
        val maxHeight = sel.mapNotNull { it.height }.maxOrNull() ?: 0
        val maxBitrate = sel.mapNotNull { it.bitrate }.maxOrNull() ?: 0.0
        AppLogRepository.i(
            LogTags.WEBVIEW,
            "Douyin bit_rate candidates=$brCandidates maxHeight=$maxHeight " +
                "maxBitrate=${maxBitrate.toLong()} totalCandidates=${sel.size}",
        )
        sel.forEach { vc ->
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "PAGE_JSON VIDEO 候选 field=${vc.field} source=${vc.source ?: "-"} gear=${vc.gear ?: "-"} " +
                    "height=${vc.height ?: 0} bitrate=${vc.bitrate?.toLong() ?: 0} " +
                    "wm=${if (vc.watermarkHint) "playwm(带水印语义，未宣称无水印)" else "plain(未证实无水印)"} " +
                    "url=${LogSanitizer.sanitizeUrl(vc.url)}",
            )
        }
        if (sel.isEmpty()) {
            AppLogRepository.d(LogTags.WEBVIEW, "PAGE_JSON VIDEO 无可用新候选 → 保持捕获/yt-dlp 链")
        }
        return sel
    }

    /**
     * P8 取证（Debug，仅日志）：VIDEO 页 WebView 未捕获到视频时，泛化探测页面 JSON 是否存在视频字段
     * （loaderData → page.videoInfoRes → item_list[].video / play_addr[.url_list]，不猜字段名、泛化遍历）。
     * 输出 PAGE_JSON VIDEO found / videoField / videoKeys / playUrls / uris 摘要。
     * 字段结构未真机标定前**不接入视频候选**（防伪造）；探测失败静默，随后照常 FAIL → yt-dlp。
     */
    private suspend fun videoJsonProbe(view: WebView) {
        val js = "(function(){try{var R=window._ROUTER_DATA||window.__ROUTER_DATA__||window.ROUTER_DATA;" +
            "if(!R||!R.loaderData)return JSON.stringify({found:false,reason:'no_router'});" +
            "var out={found:false,item:0,videoField:false,videoKeys:[],playUrls:0,uris:[]};" +
            "var c=0;for(var k in R.loaderData){if(c++>80)break;var o=R.loaderData[k];if(!o)continue;" +
            "var vf=(o.page&&o.page.videoInfoRes)||o.videoInfoRes;if(!vf)continue;" +
            "var il=vf.item_list;if(!il)continue;" +
            "for(var i=0;i<il.length&&i<8;i++){var it=il[i];if(!it)continue;out.item++;" +
            "var v=it.video||null;if(!v&&it.play_addr)v={play_addr:it.play_addr};" +
            "if(v){out.videoField=true;var pk=0;for(var vk in v){if(out.videoKeys.length<14&&out.videoKeys.indexOf(vk)<0)out.videoKeys.push(vk);pk++;if(pk>40)break;}" +
            "var ul=v.url_list||(v.play_addr&&v.play_addr.url_list)||null;" +
            "if(ul){out.playUrls+=ul.length;" +
            "for(var u2=0;u2<ul.length&&out.uris.length<4;u2++){var s=String(ul[u2]||'');var q=s.indexOf('?');var base=q>=0?s.slice(0,q):s;out.uris.push(base.replace(/^https?:\\/\\//,'').slice(0,90));}}}}}" +
            "out.found=out.playUrls>0||out.videoField;return JSON.stringify(out);" +
            "}catch(e){return JSON.stringify({found:false,reason:'err:'+e.message});}})();"
        val raw = readEvaluate(view, js, minOf(EVALUATE_TIMEOUT_MS, 600L))
        val text = raw?.let {
            if (it.startsWith("\"")) {
                runCatching { org.json.JSONTokener(it).nextValue().toString() }.getOrNull()
            } else it
        } ?: return
        runCatching {
            val o = JSONObject(text)
            val keys = o.optJSONArray("videoKeys")?.let { a ->
                (0 until a.length()).joinToString(",") { a.optString(it) }
            } ?: ""
            val uris = o.optJSONArray("uris")?.let { a ->
                (0 until a.length()).joinToString(",") { a.optString(it) }
            } ?: ""
            AppLogRepository.i(
                LogTags.WEBVIEW,
                "PAGE_JSON VIDEO found=${o.optBoolean("found", false)} reason=${o.optString("reason", "-")} " +
                    "item=${o.optInt("item", 0)} videoField=${o.optBoolean("videoField", false)} " +
                    "videoKeys=[$keys] playUrls=${o.optInt("playUrls", 0)} uris=[$uris]",
            )
        }
    }

    /** picker 返回 payload → 作品图片列表（无图/解析失败 → null）。 */
    private fun parsePageJsonPayload(raw: String): List<PageJsonMerge.JsonImage>? {
        val text = if (raw.startsWith("\"")) {
            runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrNull() ?: return null
        } else raw
        return runCatching {
            val arr = runCatching { JSONObject(text).optJSONArray("images") }.getOrNull() ?: return null
            val out = ArrayList<PageJsonMerge.JsonImage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val urls = o.optJSONArray("urls")?.let { a ->
                    buildList {
                        for (j in 0 until a.length()) a.optString(j).takeIf { it.startsWith("http") }?.let { add(it) }
                    }
                }.orEmpty()
                if (urls.isEmpty()) continue
                out.add(
                    PageJsonMerge.JsonImage(
                        seq = o.optInt("seq", out.size),
                        objKey = o.optString("obj").takeIf { it.isNotBlank() },
                        urls = urls,
                    ),
                )
            }
            out
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** picker payload 内的 meta（诊断：router/loader/vfAny/vfHit/item/img/urls/found）。 */
    private fun logPickerMeta(raw: String) {
        val text = if (raw.startsWith("\"")) {
            runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrNull() ?: return
        } else raw
        val m = runCatching { JSONObject(text).optJSONObject("meta") }.getOrNull() ?: return
        val loaderKeys = m.optJSONArray("loaderKeys")?.let { arr ->
            (0 until arr.length()).joinToString(",") { arr.optString(it) }
        } ?: ""
        AppLogRepository.d(
            LogTags.WEBVIEW,
            "PAGE_JSON picker meta router=${m.optBoolean("router", false)} " +
                "loader=${m.optBoolean("loader", false)} loaderKeys=[$loaderKeys] " +
                "vfAny=${m.optBoolean("vfAny", false)} vfHit=${m.optInt("vfHit", 0)} " +
                "item=${m.optInt("item", 0)} img=${m.optInt("img", 0)} " +
                "urls=${m.optInt("urls", 0)} found=${m.optInt("found", 0)}",
        )
    }

    /**
     * evaluateJavascript 可靠等待：硬超时内 callback 未返回 → null；
     * 成功/异常/超时/取消/晚到 callback 五态均安全（晚到 callback 因 isActive=false 被忽略，绝不二次 resume）。
     *
     * Debug 诊断（LATE_CALLBACK）：callback 无论 cont.isActive 都留痕——超时/取消后的晚到回调
     * 打 `PAGE_JSON LATE_CALLBACK attempt=… rawLen=… elapsed=…ms thread=…`（elapsed 自 evaluateJavascript
     * 真正发起时刻起算）。只观察，不改任何超时/轮询/业务行为。
     */
    private suspend fun readEvaluate(view: WebView, js: String, timeoutMs: Long, attempt: Int = 0): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { /* 取消/超时即放弃等待，resume 交由 withTimeoutOrNull 完成 */ }
                runCatching {
                    view.post {
                        runCatching {
                            val evalStart = System.currentTimeMillis()
                            view.evaluateJavascript(js) { r ->
                                val elapsed = System.currentTimeMillis() - evalStart
                                if (cont.isActive) {
                                    cont.resume(r)
                                } else {
                                    // 晚到 callback（超时/取消后）：绝不 resume，仅诊断留痕
                                    AppLogRepository.d(
                                        LogTags.WEBVIEW,
                                        "PAGE_JSON LATE_CALLBACK attempt=$attempt rawLen=${r?.length ?: 0} " +
                                            "elapsed=${elapsed}ms thread=${Thread.currentThread().name}",
                                    )
                                }
                            }
                        }.onFailure {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    /** URL 摘要：提取 CDN 模板 token（~tplv-… 段）或末段短 key，用于日志诊断（不打印完整 URL/签名）。 */
    private fun templateToken(url: String): String {
        val path = runCatching { Uri.parse(url).path }.getOrNull() ?: return ""
        val i = path.indexOf("~tplv")
        if (i >= 0) {
            var seg = path.substring(i + 1)
            val cut = seg.indexOf('/')
            if (cut >= 0) seg = seg.substring(0, cut)
            return seg.take(48)
        }
        val last = path.substringAfterLast('/').take(24)
        return last.ifEmpty { path.take(24) }
    }

    private fun mainCookie(url: String): String =
        runCatching { CookieManager.getInstance().getCookie(url) ?: "" }.getOrDefault("")

    private fun mimeByExt(path: String): String = when {
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".mov") -> "video/quicktime"
        path.endsWith(".m4v") -> "video/x-m4v"
        else -> "video/mp4"
    }

    private fun audioMimeByExt(path: String): String = when {
        path.endsWith(".mp3") -> "audio/mpeg"
        path.endsWith(".m4a") -> "audio/mp4"
        path.endsWith(".aac") -> "audio/aac"
        path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
        path.endsWith(".wav") -> "audio/wav"
        path.endsWith(".flac") -> "audio/flac"
        else -> "audio/*"
    }

    private companion object {
        val JS_BRIDGE_NAME = WebViewProbeScript.BRIDGE_NAME
        const val TIMEOUT_MS = 30_000L
        const val STABLE_WINDOW_MS = 4_000L
        const val POLL_INTERVAL_MS = 500L

        /** PAGE_JSON evaluateJavascript 单次硬超时：单次 callback 超时即放弃本轮（防解析卡死）。 */
        const val EVALUATE_TIMEOUT_MS = 1_500L

        /** PAGE_JSON 短周期轮询间隔（每次 picker 为定向浅扫，毫秒级）。 */
        const val PAGE_JSON_POLL_INTERVAL_MS = 150L

        /** PAGE_JSON 总等待上限：命中即提前返回，最迟 5s 内必然结束（超时 → NETWORK/DOM fallback）。 */
        const val PAGE_JSON_TOTAL_WAIT_MS = 5_000L

        /** P0 业务修复：slidesinfo 接口请求 URL 的路径特征（只读镜像该请求，供复用请求头重放）。 */
        const val SLIDESINFO_PATH = "slidesinfo"

        // ===================== P8 视频快速路径（仅 VIDEO 页；IMAGE 参数保持不动） =====================
        /** VIDEO 页 onPageFinished 后的短视频静默窗：到点即收尾，不再等待图片候选 4s 稳定（跳过 IMAGE 等待）。 */
        const val VIDEO_FINISH_QUIET_MS = 1_500L

        /** VIDEO 页 PAGE_JSON 读取的独立轮询间隔（IMAGE 用 [PAGE_JSON_POLL_INTERVAL_MS]，不改）。 */
        const val VIDEO_PAGE_JSON_POLL_INTERVAL_MS = 100L

        /** VIDEO 页 PAGE_JSON 读取的独立总等待上限（IMAGE 用 [PAGE_JSON_TOTAL_WAIT_MS]，不改）。 */
        const val VIDEO_PAGE_JSON_TOTAL_WAIT_MS = 2_500L

        // ===================== 诊断（只读，不参与业务）：DOM 图片加载状态 =====================
        /**
         * 单次 [KuaixiaJsBridge.onImgStates] 最多打印多少条 `WEBVIEW_IMG_STATE`（防日志爆炸）。
         * 与 PROBE_JS 内同样的条数上限保持一致（JS 侧先截断，这里是二次兜底）。
         */
        const val IMG_STATE_MAX_PER_REPORT = 40

        // ============================================================
        // P1~P6 单变量实验（evaluateJavascript callback 永不返回排查）已收敛：
        // 根因 = ApplicationContext 离屏 WebView 无 Window 宿主 → renderer 生命周期异常 →
        // evaluate callback 永不返回。正式修复 = HiddenWebViewHost（真实 Activity 透明宿主，P6 实测恢复）。
        // 以下开关全部保持 false = 正式路径；代码保留供诊断复用，禁止置 true 上生产。
        // ============================================================
        const val A_B_DISABLE_PROBE = false
        const val A_B_SINGLE_EVALUATE = false
        /** 单次 evaluate 最长等待（A/B 诊断窗口残留；singleEvaluatePageJson 仅在开关 true 时执行）。 */
        const val SINGLE_EVALUATE_WAIT_MS = 25_000L

        // P2 实验结论：shouldInterceptRequest 与 evaluate 通道无关（单变量排除）→ 正式始终收集。
        const val A_B_DISABLE_INTERCEPT = false
        // P3 实验结论：addJavascriptInterface 与 evaluate 通道无关（单变量排除）→ 正式始终注入 bridge。
        const val A_B_DISABLE_JS_INTERFACE = false
        // P4 诊断代码（sanity/LATE_CALLBACK 日志）保留；正式默认不执行 sanity 自检（dex 字符串仍在）。
        const val A_B_EVAL_SANITY_TEST = false
        // P4 诊断延迟（「onPageFinished 后立即 evaluate 过早」假设）：实验证明与问题无关 → 正式 0。
        const val P4_DELAY_BEFORE_EVALUATE_MS = 0L
        // P5 方案废弃：ApplicationContext 无 Window token，WindowManager addView 抛 BadToken，
        // 无法作为后台 WebView 宿主。实验代码保留，勿再启用。
        const val A_B_ATTACH_TEST = false
        // P6 实验已收敛为正式架构 HiddenWebViewHost（真实 Activity 透明宿主）→ 此开关不再使用。
        const val A_B_ACTIVITY_HOST_TEST = false

    }
}

/** URL 是否为可交还 WebView 的 http/https 导航。 */
private fun isHttpLike(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** 候选类型 → 最终文件扩展名（供候选 StreamInfo 使用；HLS 固定 m3u8）。 */
private fun extFor(kind: WebViewParser.MediaKind): String? = when (kind) {
    WebViewParser.MediaKind.HLS -> "m3u8"
    WebViewParser.MediaKind.VIDEO -> "mp4"
    WebViewParser.MediaKind.SEGMENT -> "ts"
    WebViewParser.MediaKind.AUDIO -> "mp4"
    WebViewParser.MediaKind.UNKNOWN -> null
}


/** 结果分流纯逻辑（F-1：VIDEO 页无视频一律 FAIL→yt-dlp，图集判定仅限非 VIDEO 页；可 JVM 单测）。 */
internal object WebViewResultRouter {

    enum class Route { VIDEO, IMAGES, FAIL }

    /**
     * 路由规则（F-1 修复，回归案例 v.douyin.com/QR7GV3E1_d8 → /video/ 7683099070780776019）：
     * 1. 捕获到视频候选 → VIDEO（与页面类型、图片数量无关）；
     * 2. 非 VIDEO 页（NOTE/SLIDES/未知）捕获到 >=1 张合法图片 → IMAGES（单图或图集）；
     * 3. 其余 → FAIL（尤其：VIDEO 页无视频时，页面上有多少普通图片都不作数——图片收集
     *    只能证明「页面存在图片」，不能证明「图片属于当前作品」；头像/推荐卡/UI 图同样
     *    可能进入候选，按数量判图集会把普通视频页误判成 IMAGE_COLLECTION 并截断 yt-dlp fallback）。
     *
     * @param isVideoPage  URL path 判定为 VIDEO 页
     * @param videoCount   捕获的视频类候选数（含 HLS；不含音频）
     * @param imageCount   通过门控过滤后的合法图片数
     */
    fun route(isVideoPage: Boolean, videoCount: Int, imageCount: Int): Route = when {
        videoCount > 0 -> Route.VIDEO
        // 图集/单图判定仅对非 VIDEO 页开放（VIDEO 页无视频 → FAIL → 上层 yt-dlp fallback）
        !isVideoPage && imageCount >= 1 -> Route.IMAGES
        else -> Route.FAIL
    }

    /** 图片数 → 媒体类型：1=IMAGE，>1=IMAGE_COLLECTION。 */
    fun imageMediaType(count: Int): com.kuaixia.app.core.model.MediaType =
        if (count <= 1) com.kuaixia.app.core.model.MediaType.IMAGE
        else com.kuaixia.app.core.model.MediaType.IMAGE_COLLECTION

    /**
     * 图片作品结果构建：imageItems = 传入全部合法图片（原样保留，绝不只留首图），
     * thumbnail = 第一张（仅 UI 展示）；streams 恒为空。
     */
    fun buildImageVideoInfo(
        id: String,
        title: String?,
        webpageUrl: String,
        images: List<ImageResource>,
    ): VideoInfo = VideoInfo(
        id = id,
        title = title,
        thumbnail = images.firstOrNull()?.url,
        webpageUrl = webpageUrl,
        platform = "webview",
        streams = emptyList(),
        mediaType = imageMediaType(images.size),
        imageItems = images,
    )
}
