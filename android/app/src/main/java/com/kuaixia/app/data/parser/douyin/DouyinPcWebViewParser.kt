package com.kuaixia.app.data.parser.douyin

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.core.model.MediaType
import com.kuaixia.app.data.model.VideoInfo
import com.kuaixia.app.data.parser.HiddenWebViewHost
import com.kuaixia.app.data.parser.NonTouchableWebView
import com.kuaixia.app.data.parser.ParseSession
import com.kuaixia.app.data.parser.VideoParser
import com.kuaixia.app.data.parser.WebViewParser
import com.kuaixia.app.ui.douyin.DESKTOP_USER_AGENT
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 抖音 **PC 形态高清**解析器（第一优先级；失败即返回失败，由 [com.kuaixia.app.data.parser.ParserManager]
 * 继续既有「移动 WebView → yt-dlp → server」链路）。
 *
 * ## 为什么需要它（真机取证结论）
 * - 移动 UA：短链 `v.douyin.com` → `m.douyin.com/share/video`（或 `www.iesdouyin.com/share/video`）
 *   → 只有 1 条 `playwm` + `ratio=720p`，`bit_rate` 非数组 → 只能拿 720P 水印档；
 * - 桌面 UA：`www.douyin.com/video/{id}` **不跳转**，页面**自身**请求 `aweme/detail`，
 *   响应中 `bit_rate[]` 为数组（实测 19 档、最高 3840、60/60 非 playwm）。
 *
 * ## 取数方式（硬性纪律）
 * 只**观察页面自身已发生的网络响应**（`DouyinPcProbeScript` 注入只读钩子）：
 * **不实现签名、不调用私有接口、不自建请求、不重放、不改写 URL**；`playwm` 仍按保守语义标注。
 *
 * ## 复用与生命周期
 * 复用 [NonTouchableWebView]（不吞触摸）、[HiddenWebViewHost]（**必须 attach 到真实 Activity 窗口**，
 * 否则 `evaluateJavascript` 回调永不返回）、[ParseSession]（会话闸门 + `teardownOnce` 幂等收尾）。
 * 收尾顺序与生产解析器一致（stopLoading → STOP_JS → about:blank → onPause → detach → destroy），
 * 保证任何路径（成功/失败/取消/超时）都不把覆盖层留在窗口上。
 *
 * ## 日志（只允许这些字段，禁止 URL/Cookie/Token 明文）
 * `Douyin PC ok|fail candidate=N maxHeight=H maxBitrate=B elapsedMs=T reason=...`
 */
class DouyinPcWebViewParser(private val context: Context) : VideoParser {

    override fun support(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun parse(url: String): Result<VideoInfo> = withContext(Dispatchers.Main) {
        val target = pcTargetUrl(url)
            ?: return@withContext Result.failure(
                AppException(ErrorCode.MEDIA_NOT_FOUND, "PC 模式不适用于该链接（图集/实况/幻灯片走移动链路）"),
            )
        val session = ParseSession()
        val started = System.currentTimeMillis()
        var webView: WebView? = null
        // 仅用于诊断（计数/布尔，不含 URL/Cookie）
        var pageFinished = false
        try {
            val dm = context.resources.displayMetrics
            val vw = dm.widthPixels
            val vh = dm.heightPixels
            val wv = NonTouchableWebView(context)
            webView = wv
            wv.setBackgroundColor(0x00000000)
            wv.layoutParams = ViewGroup.LayoutParams(vw, vh)
            runCatching {
                val ws = View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY)
                val hs = View.MeasureSpec.makeMeasureSpec(vh, View.MeasureSpec.EXACTLY)
                wv.measure(ws, hs)
                wv.layout(0, 0, vw, vh)
            }
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                loadWithOverviewMode = true
                useWideViewPort = true
                // 桌面 UA（复用登录会话同款常量）：抖音据此把页面留在 PC 形态；
                // 移动 UA 会被重定向到分享页（只有 720p 水印档）。
                userAgentString = DESKTOP_USER_AGENT
            }
            // 应用私有 CookieManager（与 DouyinWebSession / 移动解析同源）
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                /** 顶层导航只放行 http(s)；`snssdk1128://` 等 app scheme 一律拦截（避免被替换成错误页）。 */
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    return !(u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true))
                }

                /** 尽早注入只读钩子（幂等）：捕获页面**自身**发起的 aweme/detail 响应。 */
                override fun onPageStarted(view: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                    if (!session.isValid) return
                    // 与生产一致：经 post 投递注入（直接同步 evaluate 在文档创建期可能被丢弃）
                    runCatching {
                        view?.post {
                            runCatching { view.evaluateJavascript(DouyinPcProbeScript.HOOK_JS, null) }
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, u: String?) {
                    pageFinished = true
                }
            }

            // 生产要求：WebView 必须 attach 到真实 Activity 窗口，evaluateJavascript 回调才会返回
            HiddenWebViewHost.attach(wv, vw, vh)
            wv.loadUrl(target)
            AppLogRepository.i(
                LogTags.DOUYIN,
                "Douyin PC load ua=desktop url=${LogSanitizer.sanitizeUrl(target)}",
            )

            // 轮询等待页面自身完成 detail 请求（命中即退出；总预算内不命中则判定未命中）
            var capture: DouyinPcDetailMapper.Capture? = null
            var lastRaw: String? = null
            var polls = 0
            while (System.currentTimeMillis() - started < PC_TOTAL_WAIT_MS) {
                if (!session.isValid) break
                polls++
                val raw = evaluate(wv, DouyinPcProbeScript.EXTRACT_JS, PC_EVAL_TIMEOUT_MS)
                // evaluateJavascript 回传的是「JSON 编码后的字符串」（形如 "\"{...}\""）→ 需先解包，
                // 与移动链路 readVideoJsonCandidates 的处理方式完全一致。
                val text = raw?.let {
                    if (it.startsWith("\"")) {
                        runCatching { org.json.JSONTokener(it).nextValue().toString() }.getOrNull()
                    } else {
                        it
                    }
                }
                if (text != null) lastRaw = text
                val c = DouyinPcDetailMapper.parseCompact(text)
                if (c != null) {
                    capture = c
                    break
                }
                delay(PC_POLL_INTERVAL_MS)
            }

            val candidates = capture?.let { DouyinPcDetailMapper.toCandidates(it) }.orEmpty()
            val trusted = candidates.count { !it.watermarkHint }
            val maxHeight = candidates.mapNotNull { it.height }.maxOrNull() ?: 0
            val maxBitrate = candidates.mapNotNull { it.bitrate }.maxOrNull() ?: 0.0
            val elapsed = System.currentTimeMillis() - started

            // 成功条件（全部满足才算命中；否则返回失败 → 上层回退旧链路）：
            // candidate 数量 > 0；可信（非 playwm）候选 > 0；存在真实 height（档位可用于分组）
            if (candidates.isEmpty() || trusted == 0 || maxHeight <= 0) {
                // 诊断字段只有计数/布尔（无 URL、无 Cookie）：用于区分"页面未请求"与"响应太晚"
                val diag = runCatching { org.json.JSONObject(lastRaw.orEmpty()) }.getOrNull()
                AppLogRepository.i(
                    LogTags.DOUYIN,
                    "Douyin PC fail reason=${capture?.reason?.takeIf { it.isNotBlank() } ?: "no_capture"} " +
                        "candidate=${candidates.size} maxHeight=$maxHeight maxBitrate=${maxBitrate.toLong()} " +
                        "elapsedMs=$elapsed detailReq=${diag?.optInt("detailReq", -1) ?: -1} " +
                        "captureCount=${diag?.optInt("captureCount", -1) ?: -1} " +
                        "pageFinished=$pageFinished polls=$polls",
                )
                return@withContext Result.failure(
                    AppException(ErrorCode.MEDIA_NOT_FOUND, "PC 形态未取到可用高清档位"),
                )
            }

            val cookie = runCatching { CookieManager.getInstance().getCookie(target) ?: "" }.getOrDefault("")
            val streams = candidates.mapIndexed { index, c ->
                WebViewParser.MediaCandidate(
                    url = c.url,
                    kind = WebViewParser.MediaKind.VIDEO,
                    // PC 直链类型未知（无响应体可读）；与移动链路对无扩展 VIDEO 的默认一致
                    mimeHint = "video/mp4",
                    requestHeaders = emptyMap(),
                    captureOrder = index,
                    gear = c.gear,
                    width = c.width,
                    height = c.height,
                    bitrate = c.bitrate,
                    dataSize = c.dataSize,
                )
                    .toStreamInfo(DouyinPcDetailMapper.qualityOf(c.height, c.url), target)
                    .let { si ->
                        if (cookie.isNotBlank()) {
                            si.copy(videoHeaders = si.videoHeaders + ("Cookie" to cookie))
                        } else {
                            si
                        }
                    }
            }
            AppLogRepository.i(
                LogTags.DOUYIN,
                "Douyin PC ok candidate=${candidates.size} maxHeight=$maxHeight " +
                    "maxBitrate=${maxBitrate.toLong()} elapsedMs=$elapsed",
            )
            Result.success(
                VideoInfo(
                    id = awemeIdOf(target) ?: workIdOf(target),
                    title = capture?.title?.takeIf { it.isNotBlank() } ?: (TITLE_PREFIX + workIdOf(target)),
                    thumbnail = capture?.cover,
                    webpageUrl = target,
                    // 复用既有 platform 取值，避免任何 UI/文案改动
                    platform = "webview",
                    streams = streams,
                    mediaType = MediaType.VIDEO,
                    imageItems = emptyList(),
                    // PC 直链非 playwm 且带真实 height → 非低可信（不再绕 yt-dlp）
                    lowConfidence = false,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppLogRepository.w(LogTags.DOUYIN, "Douyin PC exception → 回退旧链路 err=${t.message}")
            Result.failure(t)
        } finally {
            val wv = webView
            if (wv != null) {
                // 幂等收尾（任何路径都执行；顺序与生产解析器一致，避免覆盖层残留）
                session.teardownOnce {
                    session.invalidate()
                    runCatching { wv.stopLoading() }
                    runCatching { wv.evaluateJavascript(DouyinPcProbeScript.STOP_JS, null) }
                    runCatching { wv.loadUrl("about:blank") }
                    runCatching { wv.onPause() }
                    runCatching { HiddenWebViewHost.detach(wv) }
                    runCatching { wv.removeAllViews() }
                    runCatching { wv.destroy() }
                }
            }
        }
    }

    // ======================= 内部 =======================

    /** 单次 evaluate（带硬超时；callback 与超时竞态由 `cont.isActive` 兜底）。 */
    private suspend fun evaluate(view: WebView, js: String, timeoutMs: Long): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    view.post {
                        runCatching {
                            view.evaluateJavascript(js) { r ->
                                if (cont.isActive) cont.resume(r)
                            }
                        }
                    }
                }
            }
        }

    /**
     * PC 目标 URL：
     * - `/note/`、`/slides/` → 返回 null（图集/实况/幻灯片走既有移动链路）；
     * - 能解析出 aweme id → 规范化为 `https://www.douyin.com/video/{id}`；
     * - 短链等解析不出 id → 原样返回（桌面 UA 下由平台自身 301 到 PC 视频页，已真机验证）。
     */
    private fun pcTargetUrl(url: String): String? {
        val path = runCatching { Uri.parse(url).path?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        if (path.contains("/note/") || path.contains("/slides/")) return null
        val id = awemeIdOf(url) ?: return url
        return "https://www.douyin.com/video/$id"
    }

    private fun awemeIdOf(url: String): String? {
        val patterns = listOf(
            Regex("/video/(\\d{6,})"),
            Regex("/share/video/(\\d{6,})"),
            Regex("[?&]modal_id=(\\d{6,})"),
            Regex("/note/(\\d{6,})"),
        )
        patterns.forEach { p ->
            val m = p.find(url) ?: return@forEach
            val v = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (v != null) return v
        }
        return null
    }

    /** 作品 ID（回退用）：URL 末段；绝不回退成裸主机名。 */
    private fun workIdOf(url: String): String {
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        val seg = path.split('/').filter { it.isNotBlank() }.lastOrNull()
        val candidate = seg?.substringBefore('.')?.takeIf { it.isNotBlank() }
        return candidate ?: (Uri.parse(url).host ?: "douyin")
    }

    private companion object {
        /** 等待页面自身 detail 请求的总预算（真机：PC 页加载+自身 XHR 需数秒；超时即回退旧链路）。 */
        const val PC_TOTAL_WAIT_MS = 25_000L

        /** 轮询间隔（命中即退出）。 */
        const val PC_POLL_INTERVAL_MS = 500L

        /** 单次 evaluate 硬超时（防 callback 不返回导致卡死）。 */
        const val PC_EVAL_TIMEOUT_MS = 1_500L

        const val TITLE_PREFIX = "抖音作品_"
    }
}
