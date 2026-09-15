package com.kuaixia.app.data.parser

import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.core.log.ParseSummary
import com.kuaixia.app.core.model.ParseMode
import com.kuaixia.app.core.util.ClipboardUrlExtractor
import com.kuaixia.app.core.util.MediaUrlCanonicalizer
import com.kuaixia.app.core.util.ServerUrl
import com.kuaixia.app.data.SettingsRepository
import com.kuaixia.app.data.format.FormatDisplayGrouper
import com.kuaixia.app.data.image.P96RefreshPolicy
import com.kuaixia.app.data.model.VideoInfo
import com.kuaixia.app.data.web.DouyinWebSession
import kotlinx.coroutines.flow.first

/**
 * 解析编排器：按平台与 [ParseMode] 决定解析顺序。
 *
 * 抖音（专项 A：WebView-first）：
 * ```
 * Douyin URL
 *   → 平台识别（v.douyin.com / *.douyin.com / note/ 均命中）
 *   → WebView 页面级资源解析（真实页面环境）
 *   → 失败 → yt-dlp fallback（沿用 Netscape Cookie Jar `--cookies`，绝不复用 header Cookie）
 *   → 仍失败 → 服务器仅当「已配置」才最后尝试
 *   → 无服务器/全部失败 → 返回真实失败原因（不弹「请先添加解析服务器」）
 * ```
 * 其余平台保持原行为不变（本地 yt-dlp → 已配置服务器兜底）。
 *
 * 设计约定：
 * - 不自动无限切换服务器；服务器失败即如实返回。
 * - 自动剪贴板解析与手动解析共享本编排（无第二套解析器）。
 */
class ParserManager(
    private val localParser: VideoParser,
    private val serverParser: VideoParser,
    private val settings: SettingsRepository,
    private val douyinSession: DouyinWebSession? = null,
    /** douyin 页面级解析（惰性：仅 douyin WebView-first 需要时创建）。 */
    private val webParserProvider: () -> VideoParser? = { null },
) {

    /** 解析结果 + 结果来源（用于 UI 展示「网页解析/本地解析」或服务器名称）。 */
    data class Outcome(val info: VideoInfo, val source: String)

    suspend fun parse(url: String): Result<Outcome> {
        if (!ServerUrl.isHttpUrl(url)) {
            return Result.failure(AppException(ErrorCode.INVALID_URL, "链接无效，仅支持 http/https"))
        }
        val mode = settings.parseMode.first()
        val isDouyin = ClipboardUrlExtractor.platformOf(url) == ClipboardUrlExtractor.Platform.DOUYIN
        val started = System.currentTimeMillis()
        // 缓存键：解析模式 + canonical URL（丢弃追踪参数/fragment；同一作品不同分享参数可复用）
        val canonical = MediaUrlCanonicalizer.canonical(url)
        val cacheKey = "${mode.name}|$canonical"
        AppLogRepository.i(
            LogTags.PARSER,
            "parse start mode=${mode.name} platform=${if (isDouyin) "douyin" else "other"} url=$url",
        )
        val cached = resultCache.get(cacheKey)
        if (cached is Outcome) {
            AppLogRepository.i(LogTags.PARSER, "parse cache hit mode=${mode.name} url=$url")
            return Result.success(cached)
        }
        val result = when (mode) {
            ParseMode.LOCAL_FIRST -> if (isDouyin) douyinFull(url) else genericLocalFirst(url)
            ParseMode.LOCAL_ONLY -> if (isDouyin) douyinLocalOnly(url) else genericLocalOnly(url)
            ParseMode.SERVER_FIRST -> if (isDouyin) douyinServerFirst(url) else genericServerFirst(url)
            ParseMode.SERVER_ONLY -> genericServerOnly(url)
        }
        // 只缓存成功且「可用」的结果：残缺视频结果（无可下载格式，如 formatGroups=0）不入缓存，
        // 避免坏结果在 TTL 内被反复复用（宁可下次重新解析并可回退 yt-dlp）。失败/登录态失效同样不入。
        if (result.isSuccess) {
            val outcome = result.getOrThrow()
            if (isCacheable(outcome.info)) {
                resultCache.put(cacheKey, outcome)
            } else {
                AppLogRepository.w(
                    LogTags.PARSER,
                    "结果不完整（无可下载视频格式）→ 不入缓存 source=${outcome.source} " +
                        "formats=${outcome.info.streams.size}",
                )
            }
        }
        AppLogRepository.i(
            LogTags.PARSER,
            "parse end mode=${mode.name} elapsed_ms=${System.currentTimeMillis() - started} " +
                "success=${result.isSuccess} source=${result.getOrNull()?.source} " +
                "code=${(result.exceptionOrNull() as? AppException)?.code}",
        )
        // 日志 V2：统一 PARSE SUMMARY（多人测试的核心可比对单元；字段名固定，取不到用 unknown）
        runCatching {
            val info = result.getOrNull()?.info
            val streams = info?.streams.orEmpty()
            val summary = ParseSummary.Fields(
                timeMs = System.currentTimeMillis(),
                platform = if (isDouyin) "douyin" else DownloadSummaryPlatformFallback(url),
                page = ParseSummary.pageOf(canonical),
                url = LogSanitizer.sanitizeUrl(canonical),
                strategy = "${if (isDouyin) "DOUYIN" else "GENERIC"}_${mode.name}",
                success = result.isSuccess,
                mediaType = info?.mediaType?.name ?: "unknown",
                source = result.getOrNull()?.source ?: "none",
                video = streams.count { !it.mimeType.orEmpty().lowercase().startsWith("audio") },
                audio = streams.count { it.mimeType.orEmpty().lowercase().startsWith("audio") },
                image = info?.imageItems?.size ?: 0,
                formats = streams.size,
                formatGroups = FormatDisplayGrouper.group(streams).size,
                fallbackYtdlp = result.getOrNull()?.source == SOURCE_LOCAL,
                elapsedMs = System.currentTimeMillis() - started,
                error = result.exceptionOrNull()?.let { (it as? AppException)?.message ?: it.message },
            )
            AppLogRepository.i(LogTags.PARSER, ParseSummary.render(summary))
        }
        return result
    }

    /** 非 douyin 时的平台名（与 DOWNLOAD SUMMARY 同一推断，供 SUMMARY 使用）。 */
    private fun DownloadSummaryPlatformFallback(url: String): String =
        com.kuaixia.app.core.log.DownloadSummary.platformOf(url)

    // ==================== 抖音：WebView-first ====================

    /** 抖音完整链路（LOCAL_FIRST）：WebView → yt-dlp → （已配置）服务器。 */
    private suspend fun douyinFull(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=DOUYIN_WEBVIEW_FIRST")
        val base = douyinChain(url, allowServer = true)
        if (base.isSuccess) return base
        val err = base.exceptionOrNull()
        return Result.failure(finalLocalMessage(err, isDouyin = true))
    }

    /** 抖音仅本地（LOCAL_ONLY）：WebView → yt-dlp，不碰服务器。 */
    private suspend fun douyinLocalOnly(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=DOUYIN_WEBVIEW_FIRST（LOCAL_ONLY）")
        val base = douyinChain(url, allowServer = false)
        if (base.isSuccess) return base
        return Result.failure(finalLocalMessage(base.exceptionOrNull(), isDouyin = true))
    }

    /** 抖音服务器优先（SERVER_FIRST）：先服务器，失败后再 WebView → yt-dlp。 */
    private suspend fun douyinServerFirst(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=SERVER_FIRST(DOUYIN) -> ServerParser")
        val serverName = serverNameOrNull()
        if (serverName != null) {
            val s = serverParser.parse(url)
            if (s.isSuccess) return Result.success(Outcome(s.getOrThrow(), serverName))
            logFailure("server", s.exceptionOrNull())
        }
        AppLogRepository.i(LogTags.PARSER, "server failed/缺失 → douyin WebView-first")
        val base = douyinChain(url, allowServer = false)
        if (base.isSuccess) return base
        return Result.failure(finalLocalMessage(base.exceptionOrNull(), isDouyin = true))
    }

    /**
     * 抖音本地链：WebView-first → yt-dlp（含 Cookie 刷新重试）→ （allowServer 时已配置服务器兜底）。
     * 服务器 fallback 只在 WebView 与 yt-dlp 都失败且已配置时才进入。
     */
    private suspend fun douyinChain(url: String, allowServer: Boolean): Result<Outcome> {
        var webErr: Throwable? = null
        val webParserLocal = webParserProvider()
        if (webParserLocal != null) {
            AppLogRepository.i(LogTags.PARSER, "Douyin WebView-first parse url=平台已识别")
            val web = webParserLocal.parse(url)
            if (web.isSuccess) {
                val webInfo = web.getOrThrow()
                // P0 回归修复：低可信 WebView 结果（videoCount>0 但 trustedVideoCount==0，
                // 典型场景＝页面唯一候选是 playwm 水印直链）不得直接截断 yt-dlp。
                // 行为：先试 yt-dlp → 成功用 yt-dlp；失败则回退这份 WebView 结果，绝不让
                // 「fallback 失败」把原本可下载的结果变成完全解析失败。
                if (webInfo.lowConfidence) {
                    AppLogRepository.i(
                        LogTags.PARSER,
                        "Douyin WebView low-confidence → yt-dlp first（不再直接截断 yt-dlp）",
                    )
                    val preferred = localAttempts(url)
                    if (preferred.isSuccess) {
                        AppLogRepository.i(LogTags.PARSER, "low-conf WebView → yt-dlp ok source=LOCAL")
                        return Result.success(Outcome(preferred.getOrThrow(), SOURCE_LOCAL))
                    }
                    AppLogRepository.w(
                        LogTags.PARSER,
                        "low-conf WebView → yt-dlp failed，回退原 WebView 结果 " +
                            "err=${preferred.exceptionOrNull()?.message}",
                    )
                    return Result.success(Outcome(webInfo, SOURCE_WEBVIEW))
                }
                AppLogRepository.i(LogTags.PARSER, "Douyin WebView result source=webview")
                // 图文/实况：若图片直链落在 p96 坏 Host，则重新加载同一页面重取 URL，
                // 按 object key 替换（已正常的图片与非图片结果一律不动）。
                // 说明：本条分支只处理 imageItems 非空的图集结果（VIDEO 分支 imageItems 为空），
                // 与上面的 lowConfidence（videoCount>0）分支互斥，不会重复加载页面。
                val refreshedInfo = refreshP96ImagesIfNeeded(url, webInfo, webParserLocal)
                return Result.success(Outcome(refreshedInfo, SOURCE_WEBVIEW))
            }
            webErr = web.exceptionOrNull()
            AppLogRepository.i(
                LogTags.PARSER,
                "Douyin WebView failure code=${(webErr as? AppException)?.code} → yt-dlp fallback",
            )
        }

        val dlp = localAttempts(url)
        if (dlp.isSuccess) {
            AppLogRepository.i(LogTags.PARSER, "yt-dlp fallback ok source=LOCAL")
            return Result.success(Outcome(dlp.getOrThrow(), SOURCE_LOCAL))
        }
        val dlpErr = dlp.exceptionOrNull()

        if (allowServer) {
            val serverName = serverNameOrNull()
            if (serverName != null) {
                AppLogRepository.w(
                    LogTags.PARSER,
                    "WebView + yt-dlp failed → last-resort server. err=${dlpErr?.message}",
                )
                val s = serverParser.parse(url)
                if (s.isSuccess) return Result.success(Outcome(s.getOrThrow(), serverName))
                logFailure("server", s.exceptionOrNull())
                return Result.failure(chooseBestError(webErr, s.exceptionOrNull(), dlpErr))
            }
        }
        return Result.failure(chooseBestError(webErr, dlpErr, null))
    }

    // ==================== 抖音：p96 坏 Host 刷新 ====================

    /**
     * p96 坏 Host 刷新（仅图片集）：**重新加载同一个页面**，把上游新下发的图片 URL 按 object key 替换进来。
     *
     * 依据（真机证据）：图片直链的 Host 由上游每次页面加载动态分配；`p96-sign.douyinpic.com`
     * 在 Chromium 与 OkHttp 两通道均失败，非 p96 均成功；快夏对 URL 零改写，因此只能靠"重新加载"重取。
     *
     * 约束：
     * - 仅在「图片集非空且含 p96」时触发；最多 [P96RefreshPolicy.MAX_ATTEMPTS] 次（首次解析不计）；
     * - 每次刷新都是**一次完整的新 WebView 页面加载**——复用 [VideoParser.parse]，其内部自建并在 finally
     *   销毁 WebView，因此本协程内不留下旧 WebView、也不新建第二个全局 WebView；刷新在**同一协程内顺序执行**，
     *   不会出现"旧 parse Job cancel 新刷新"；
     * - 刷新结果条数与原始不一致（或刷新解析失败）→ 整次作废并记录 `refresh invalid`，保留当前结果；
     * - 只替换 p96 项；非 p96 项一律保留；找不到同 object key 的可用 URL 时保留原项（绝不丢图）；
     * - **绝不拼接/改写 URL 或 Host**。
     */
    private suspend fun refreshP96ImagesIfNeeded(
        url: String,
        webInfo: VideoInfo,
        parser: VideoParser,
    ): VideoInfo {
        val originalImages = webInfo.imageItems
        if (originalImages.isEmpty()) return webInfo
        val originalP96 = P96RefreshPolicy.countP96(originalImages)
        if (originalP96 == 0) return webInfo

        var currentImages = originalImages
        var currentP96 = originalP96
        var attempts = 0
        var replacedAny = false
        while (attempts < P96RefreshPolicy.MAX_ATTEMPTS && currentP96 > 0) {
            val attempt = attempts + 1
            AppLogRepository.i(
                LogTags.PARSER,
                "P96_REFRESH start attempt=$attempt original=$originalP96 total=${originalImages.size}",
            )
            val refreshed = parser.parse(url)
            val refreshedInfo = refreshed.getOrNull()
            if (refreshedInfo == null) {
                AppLogRepository.w(
                    LogTags.PARSER,
                    "P96_REFRESH invalid attempt=$attempt reason=parse-failed " +
                        "err=${refreshed.exceptionOrNull()?.message}",
                )
                attempts++
                continue
            }
            val merged = P96RefreshPolicy.merge(
                current = currentImages,
                refreshed = refreshedInfo.imageItems,
                expectedCount = originalImages.size,
            )
            if (!merged.accepted) {
                AppLogRepository.w(
                    LogTags.PARSER,
                    "P96_REFRESH invalid attempt=$attempt reason=count-mismatch " +
                        "original=${originalImages.size} refreshed=${refreshedInfo.imageItems.size}",
                )
                attempts++
                continue
            }
            for (r in merged.replacements) {
                AppLogRepository.i(
                    LogTags.PARSER,
                    "P96_REFRESH replace object=${r.key} oldHost=${r.oldHost} newHost=${r.newHost}",
                )
            }
            AppLogRepository.i(
                LogTags.PARSER,
                "P96_REFRESH result attempt=$attempt total=${merged.images.size} " +
                    "p96=${merged.finalP96} replaced=${merged.replaced}",
            )
            currentImages = merged.images
            currentP96 = merged.finalP96
            replacedAny = replacedAny || merged.replaced > 0
            attempts++
        }
        AppLogRepository.i(
            LogTags.PARSER,
            "P96_REFRESH final original=$originalP96 attempts=$attempts finalP96=$currentP96 " +
                "total=${currentImages.size}",
        )
        if (!replacedAny) return webInfo
        // 缩略图取首图：若首图被替换，缩略图必须同步，否则预览仍指向旧 p96 URL
        return webInfo.copy(
            imageItems = currentImages,
            thumbnail = currentImages.firstOrNull()?.url ?: webInfo.thumbnail,
        )
    }

    // ==================== 其它平台：保持原行为 ====================

    private suspend fun genericLocalOnly(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=LOCAL_ONLY -> YtDlpParser")
        return localAttempts(url).fold(
            onSuccess = { Result.success(Outcome(it, SOURCE_LOCAL)) },
            onFailure = { e ->
                logFailure("local", e)
                Result.failure(e)
            },
        )
    }

    private suspend fun genericServerOnly(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=SERVER_ONLY -> ServerParser")
        val name = serverNameOrNull() ?: return noServerFailure()
        val result = serverParser.parse(url)
        return result.fold(
            onSuccess = { Result.success(Outcome(it, name)) },
            onFailure = { e ->
                logFailure("server", e)
                Result.failure(e)
            },
        )
    }

    private suspend fun genericLocalFirst(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=LOCAL_FIRST -> YtDlpParser")
        val local = localAttempts(url)
        if (local.isSuccess) {
            return Result.success(Outcome(local.getOrThrow(), SOURCE_LOCAL))
        }
        logFailure("local", local.exceptionOrNull())
        val name = serverNameOrNull()
        if (name != null) {
            AppLogRepository.w(LogTags.PARSER, "local parse failed → server fallback")
            val server = serverParser.parse(url)
            if (server.isSuccess) return Result.success(Outcome(server.getOrThrow(), name))
            logFailure("server", server.exceptionOrNull())
            return Result.failure(server.exceptionOrNull() ?: local.exceptionOrNull() ?: genericFailure())
        }
        return Result.failure(local.exceptionOrNull() ?: genericFailure())
    }

    private suspend fun genericServerFirst(url: String): Result<Outcome> {
        AppLogRepository.i(LogTags.PARSER, "strategy=SERVER_FIRST -> ServerParser")
        val name = serverNameOrNull() ?: return noServerFailure()
        val server = serverParser.parse(url)
        if (server.isSuccess) return Result.success(Outcome(server.getOrThrow(), name))
        logFailure("server", server.exceptionOrNull())
        AppLogRepository.w(LogTags.PARSER, "server parse failed, FALLBACK to local.")
        return genericLocalOnly(url)
    }

    /**
     * 本地解析（yt-dlp）。douyin 首次失败且属 Cookie/登录类 → 刷新 WebView Cookie jar 后重试一次。
     * 命令仍为 `--cookies <jar>`（引擎注入），此处只负责触发「先刷新再重试」。
     */
    private suspend fun localAttempts(url: String): Result<VideoInfo> {
        AppLogRepository.i(LogTags.PARSER, "YtDlp parse attempt=1 source=LOCAL")
        val first = localParser.parse(url)
        if (first.isSuccess) return first

        val isDouyin = ClipboardUrlExtractor.platformOf(url) == ClipboardUrlExtractor.Platform.DOUYIN
        val session = douyinSession
        if (!isDouyin || session == null || !isCookieLike(first.exceptionOrNull())) {
            return first
        }
        AppLogRepository.i(
            LogTags.PARSER,
            "YtDlp failure reason=FRESH_COOKIE Cookie refresh triggered=true",
        )
        session.refreshAndExportJar()
        AppLogRepository.i(LogTags.PARSER, "YtDlp parse attempt=2 source=LOCAL")
        val second = localParser.parse(url)
        if (second.isSuccess) AppLogRepository.i(LogTags.PARSER, "YtDlp retry ok after cookie refresh")
        return second
    }

    /** Cookie/登录上下文类错误（这类错误不走「切服务器」，先本地刷新/兜底）。 */
    private fun isCookieLike(e: Throwable?): Boolean {
        val code = (e as? AppException)?.code
        return code == ErrorCode.COOKIE_REQUIRED || code == ErrorCode.LOGIN_REQUIRED
    }

    private suspend fun serverNameOrNull(): String? =
        settings.serverSettings.first().currentServer()?.name

    /** 缓存准入：视频结果须至少含 1 个可下载格式（与 UI 分组口径一致）；图片类保持原行为。 */
    private fun isCacheable(info: VideoInfo): Boolean =
        info.mediaType != com.kuaixia.app.core.model.MediaType.VIDEO ||
            com.kuaixia.app.data.format.ResultCompleteness.hasDownloadableVideo(info.streams)

    private fun noServerFailure(): Result<Outcome> =
        Result.failure(AppException(ErrorCode.NO_SERVER, "请先在设置中添加解析服务器"))

    private fun genericFailure(): AppException =
        AppException(ErrorCode.UNKNOWN_ERROR, "解析失败，请稍后重试")

    /** 选「最有诊断价值」的错误展示（WebView 无 Cookie 类错误优先于泛化 server 错误）。 */
    private fun chooseBestError(web: Throwable?, dlp: Throwable?, server: Throwable?): Throwable {
        val cookieLike = listOfNotNull(web, dlp, server).firstOrNull { isCookieLike(it) }
        cookieLike?.let { return it }
        return listOfNotNull(dlp, web, server).firstOrNull()
            ?: listOfNotNull(web, dlp, server).firstOrNull() ?: genericFailure()
    }

    /** 抖音本地全部失败后的真实原因（不弹「请添加服务器」）。 */
    private fun finalLocalMessage(e: Throwable?, isDouyin: Boolean): AppException {
        val ae = e as? AppException
        if (isDouyin) {
            when (ae?.code) {
                ErrorCode.COOKIE_REQUIRED, ErrorCode.LOGIN_REQUIRED -> return AppException(
                    ae.code,
                    "抖音需要当前网页会话，请打开内置浏览器登录（如需第三方服务器，可在设置中添加后重试）",
                    ae.detail,
                )
                ErrorCode.MEDIA_NOT_FOUND -> return AppException(
                    ae.code, "该抖音作品暂无法提取（可能已删除或需登录后查看）", ae.detail,
                )
                else -> return AppException(
                    ae?.code ?: ErrorCode.UNKNOWN_ERROR,
                    "抖音网页资源获取失败，请稍后重试",
                    ae?.detail,
                )
            }
        }
        return ae ?: genericFailure()
    }

    private fun logFailure(which: String, e: Throwable?) {
        if (e == null) return
        val ae = e as? AppException
        AppLogRepository.w(LogTags.PARSER, "$which parse failed code=${ae?.code} msg=${e.message}")
        ae?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            AppLogRepository.w(LogTags.PARSER, "$which detail: $detail")
        }
    }

    private companion object {
        const val SOURCE_LOCAL = "本地解析"
        const val SOURCE_WEBVIEW = "网页解析"
    }

    /** 解析成功结果缓存（短 TTL + 小容量；失败/登录态失效不入缓存，不跨过期会话复用）。 */
    private val resultCache = ParseResultCache()
}

/** 线程安全、短 TTL 的成功结果缓存。 */
private class ParseResultCache(
    private val ttlMs: Long = 45_000L,
    private val maxSize: Int = 16,
) {
    private class Entry(val at: Long, val value: Any)
    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun get(key: String): Any? {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.at > ttlMs) it.remove()
        }
        return map[key]?.value
    }

    @Synchronized
    fun put(key: String, value: Any) {
        val now = System.currentTimeMillis()
        map[key] = Entry(now, value)
        if (map.size > maxSize) {
            val it = map.entries.iterator()
            it.next()
            it.remove()
        }
    }
}
