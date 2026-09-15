package com.kuaixia.app.ui.home

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.R
import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.LogSanitizer
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.core.model.UiText
import com.kuaixia.app.core.util.ClipboardAutoDecider
import com.kuaixia.app.core.util.ClipboardIdentityBook
import com.kuaixia.app.core.util.ClipboardUrlExtractor
import com.kuaixia.app.core.util.MediaIdentity
import com.kuaixia.app.core.util.MediaUrlCanonicalizer
import com.kuaixia.app.data.download.DownloadRepository
import com.kuaixia.app.data.format.FormatDisplayGrouper
import com.kuaixia.app.data.model.StreamInfo
import com.kuaixia.app.data.model.VideoInfo
import com.kuaixia.app.data.parser.ParserManager
import com.kuaixia.app.data.parser.ParserSessionGuard
import com.kuaixia.app.data.web.DouyinWebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 抖音 COOKIE_REQUIRED 提示（Phase 6）。hasSession=是否已有（可能过期）会话。 */
data class DouyinPrompt(val hasSession: Boolean)

/** 自动剪贴板检查触发源（唯一入口 checkClipboardAndAutoParse）。 */
enum class ClipboardSource {
    /** 首次进入（VM 内保证只执行一次）。 */
    INITIAL,

    /** Home 每次回到前台（含冷启动首个 RESUMED）。 */
    RESUME,

    /** App 在前台时系统剪贴板变化通知。 */
    CLIPBOARD_CHANGED,
}

data class HomeUiState(
    val url: String = "",
    val isLoading: Boolean = false,
    val result: VideoInfo? = null,
    val sourceLabel: String? = null,
    val errorMessage: UiText? = null,
    /** 检测到剪贴板链接（待用户确认）——非空时首页显示提示条。 */
    val clipboardUrl: String? = null,
    val clipboardPlatformLabel: String? = null,
    /** 抖音需要 Cookie（登录引导）。非空时首页显示「打开抖音登录/重新登录」。 */
    val douyinPrompt: DouyinPrompt? = null,
    /** 自动解析剪贴板的显式状态反馈（null=无；见 autoParseNoteError 区分成功/失败）。 */
    val autoParseNote: UiText? = null,
    val autoParseNoteError: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val parserManager: ParserManager =
        (application as KuaixiaApp).container.parserManager
    private val downloadRepository: DownloadRepository =
        (application as KuaixiaApp).container.downloadRepository
    private val settingsRepository =
        (application as KuaixiaApp).container.settingsRepository
    private val douyinWebSession: DouyinWebSession =
        (application as KuaixiaApp).container.douyinWebSession

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var parseJob: Job? = null

    /**
     * 解析会话世代守卫（P2-001）。
     *
     * `parse()` 返回后到 `_uiState.update { result = … }` 之间**没有挂起点**，因此
     * 「用户取消」与「解析刚好完成」可能同时发生：cancel() 已把 isLoading=false 写回，
     * 紧接着 onSuccess 又把 result 写回来 → UI 又显示解析结果。
     * 世代守卫让每次解析携带一个 token，结果落地前校验它是否仍是当前会话：
     * 取消 / 新一轮解析后旧 token 一律不接受（见 [parseUrl] 的两处 acceptResult）。
     */
    private val parseSessions = ParserSessionGuard()

    /**
     * 作品身份去重账本（BUG-002）。
     *
     * 比较 key 由「canonical URL 字符串」升级为「作品身份」`douyin:aweme:<awemeId>`：
     * 短链自身不含作品 ID，解析成功后用最终网页 URL 登记别名（见 [registerParsedIdentity]）。
     * 状态持久化在 DataStore（SettingsRepository.updateClipboardIdentity），App 重启后恢复，
     * 因此「重启后同一作品再次进入剪贴板」也能正确判 DUPLICATE。
     */
    private val identityBook = ClipboardIdentityBook()

    /** 自动解析 OFF 时「手动提示条」的内存去重（仅展示提醒；与自动解析完全无关）。 */
    @Volatile
    private var lastPromptedCanonical: String? = null

    /** INITIAL 只执行一次（Settings→Home 返回不再触发 INITIAL）。 */
    private var initialCheckDone = false

    /** RESUME 源检查节流时间戳（见 checkClipboardAndAutoParse）。 */
    private var lastResumeCheckAt = 0L

    init {
        // 抖音登录完成事件 → 用新会话自动重试该 URL 一次（不无限循环）。
        // 注：自动剪贴板设置/去重状态不在这里预热——每次 checkClipboardAndAutoParse 都会
        // 用 clipboardPrefs() 做单次 DataStore 快照（等待就绪），不存在“默认 false 提前决策”。
        viewModelScope.launch {
            douyinWebSession.sessionEstablished.collect { url ->
                AppLogRepository.i(LogTags.PARSER, "抖音会话已建立，自动重试原链接 platform=douyin")
                _uiState.update {
                    it.copy(url = url, douyinPrompt = null, errorMessage = null, result = null)
                }
                parseUrl(url)
            }
        }
    }

    /** 供 Share Intent 预填链接（整段分享文案也可，自动提取 URL）。 */
    fun setInitialUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            val clean = ClipboardUrlExtractor.cleanInput(url)
            _uiState.update { it.copy(url = clean.ifEmpty { url.trim() }) }
        }
    }

    fun onUrlChange(url: String) {
        _uiState.update { it.copy(url = url) }
    }

    /** Home 首帧/首次可用：INITIAL 只执行一次（Settings→Home 返回不重复触发）。 */
    fun onHomeReady() {
        if (initialCheckDone) return
        initialCheckDone = true
        checkClipboardAndAutoParse(ClipboardSource.INITIAL)
    }

    /**
     * 自动剪贴板唯一入口（INITIAL / RESUME / CLIPBOARD_CHANGED）。
     * 顺序：设置快照（等待 DataStore 就绪）→ 读剪贴板 → canonical → 纯决策 → 触发/跳过。
     * 检测 URL 与真正解析在同一个函数内完成，不依赖下一次生命周期事件；
     * 也不存在“设置未就绪 → 默认 false 提前决策”的窗口。
     *
     * RESUME 源按 [RESUME_CHECK_MIN_INTERVAL_MS] 节流：快速连续导航会产生密集 RESUME，
     * 避免重复读剪贴板（ColorOS 每次读剪贴板会弹系统提示、影响窗口焦点）。
     * 真实使用场景（切到别的 App 复制链接后回到首页）间隔远大于该阈值，不受影响。
     */
    fun checkClipboardAndAutoParse(source: ClipboardSource) {
        if (source == ClipboardSource.RESUME) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastResumeCheckAt < RESUME_CHECK_MIN_INTERVAL_MS) return
            lastResumeCheckAt = now
        }
        viewModelScope.launch {
            // DataStore 单次快照：cold-flow first() = 等待真实设置值就绪
            val prefs = settingsRepository.clipboardPrefs()

            val rawUrl = readClipboardText()
            val candidate = rawUrl?.let { ClipboardUrlExtractor.first(it) }
            val url = candidate?.url
            val canonical = url?.let { MediaUrlCanonicalizer.canonical(it) }
            // P1：仅受支持平台（douyin/kuaishou/xhs/bilibili/youtube…）才自动解析；
            // UNKNOWN（如图片 CDN douyinpic.com、普通网页）不自动触发，手动解析不受影响。
            val supported = candidate != null &&
                candidate.platform != ClipboardUrlExtractor.Platform.UNKNOWN

            // BUG-002：用持久化状态恢复身份账本（重启后首次检查生效；
            // 只在内存为空时采纳，避免用未写盘的旧值覆盖进程内新状态）
            identityBook.restore(
                persistedLastIdentity = prefs.lastAutoParsedIdentity,
                persistedAliases = prefs.identityAliases,
            )

            val decision = ClipboardAutoDecider.decide(
                autoEnabled = prefs.autoParse,
                canonicalUrl = identityBook.resolve(candidate?.url ?: ""),
                inFlightCanonical = identityBook.inFlightIdentity,
                lastAutoParsedCanonical = identityBook.lastIdentity,
                supported = supported,
            )
            logClipboard(source, prefs.autoParse, candidate, decision, url)

            when (decision) {
                ClipboardAutoDecider.Decision.UNSUPPORTED -> {
                    // 图片 CDN / unknown：不自动解析（也无需提示条），手动解析仍可用
                }
                ClipboardAutoDecider.Decision.AUTO_DISABLED -> {
                    // 自动解析关闭：只做「读取到链接」的普通提醒，绝不调用 parseUrl(auto=true)
                    if (candidate != null && canonical != lastPromptedCanonical) {
                        lastPromptedCanonical = canonical
                        _uiState.update {
                            it.copy(
                                url = url ?: it.url,
                                clipboardUrl = url,
                                clipboardPlatformLabel = candidate.platform.label,
                                errorMessage = null,
                            )
                        }
                        AppLogRepository.i(
                            LogTags.PARSER,
                            "检测到剪贴板链接 platform=${candidate.platform.label}（未开启自动解析，可手动解析）",
                        )
                    }
                }
                ClipboardAutoDecider.Decision.NO_URL,
                ClipboardAutoDecider.Decision.IN_FLIGHT,
                ClipboardAutoDecider.Decision.DUPLICATE -> { /* 已由 logClipboard 记录 */ }
                ClipboardAutoDecider.Decision.TRIGGER -> {
                    val u = url ?: return@launch
                    // 决策即接受：立刻记录并启动统一解析（本函数内完成，不等下一次生命周期）
                    // BUG-002：占位用「作品身份」（短链暂无身份时退化为 canonical，解析成功后升级）
                    identityBook.markTriggered(u)
                    persistIdentityAsync()
                    _uiState.update {
                        it.copy(
                            url = u,
                            clipboardUrl = null,
                            clipboardPlatformLabel = null,
                            autoParseNote = null,
                        )
                    }
                    autoParseUrl(u, source)
                }
            }
        }
    }

    /**
     * 剪贴板检查日志（BUG-002：输出作品身份而非完整 canonical URL，
     * 避免把 share_sign / activity_info 等分享签名大量写进日志）。
     */
    private fun logClipboard(
        source: ClipboardSource,
        autoEnabled: Boolean,
        candidate: ClipboardUrlExtractor.Candidate?,
        decision: ClipboardAutoDecider.Decision,
        url: String?,
    ) {
        val identity = url?.let { identityBook.resolve(it) }
        val awemeId = MediaIdentity.awemeIdFromIdentity(identity)
        val type = MediaIdentity.identityTypeOf(identity)
        // 身份退化为 URL 形态时（非抖音等），仍走脱敏，避免 query 入日志
        val safeIdentity = if (type == "aweme") identity else LogSanitizer.sanitizeUrl(identity)
        AppLogRepository.i(
            LogTags.PARSER,
            "Clipboard check source=${source.name} autoEnabled=$autoEnabled " +
                "urlDetected=${candidate != null} " +
                "platform=${candidate?.platform?.label ?: "-"} " +
                "identityType=$type awemeId=${awemeId ?: "-"} " +
                "identity=${safeIdentity ?: "-"} decision=$decision",
        )
    }

    /** 作品身份去重状态落盘（DataStore）。 */
    private fun persistIdentityAsync() {
        val last = identityBook.snapshotLastIdentity()
        val aliases = identityBook.snapshotAliases()
        viewModelScope.launch {
            settingsRepository.updateClipboardIdentity(last, aliases)
        }
    }

    /**
     * 解析成功后：用最终网页 URL 提取作品 ID，把本次请求的 URL（可能是短链）
     * 登记为该作品身份的别名 —— 这样同一作品的长链/短链后续都会被判 DUPLICATE。
     */
    private fun registerParsedIdentity(requestedUrl: String, resolvedUrl: String?) {
        if (!identityBook.registerResolved(requestedUrl, resolvedUrl)) return
        val identity = identityBook.lastIdentity
        AppLogRepository.i(
            LogTags.PARSER,
            "Clipboard identity registered awemeId=" +
                "${MediaIdentity.awemeIdFromIdentity(identity) ?: "-"} " +
                "aliases=${identityBook.aliasCount()}",
        )
        persistIdentityAsync()
    }

    /** 手动「粘贴」按钮：从剪贴板读取并填充（不受 lastHandledHash 去重限制）。 */
    fun pasteFromClipboard() {
        val raw = readClipboardText() ?: return
        val candidate = ClipboardUrlExtractor.first(raw)
        val clean = candidate?.url ?: raw.trim()
        if (clean.isNotEmpty()) {
            _uiState.update { it.copy(url = clean) }
        }
        if (candidate != null) {
            AppLogRepository.i(
                LogTags.PARSER,
                "手动粘贴剪贴板链接 platform=${candidate.platform.label}",
            )
        }
    }

    /** 用户点「忽略」：隐藏提示；lastPromptedCanonical 已记录，同一剪贴板不会再次弹出。 */
    fun ignoreClipboard() {
        _uiState.update { it.copy(clipboardUrl = null, clipboardPlatformLabel = null) }
    }

    /** 点「立即解析」：直接走现有解析流程。 */
    fun parseClipboard() {
        val url = _uiState.value.clipboardUrl ?: return
        _uiState.update { it.copy(clipboardUrl = null, clipboardPlatformLabel = null) }
        parseUrl(url)
    }

    private fun readClipboardText(): String? = runCatching {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return null
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())
            ?.toString()
    }.getOrNull()

    fun parse() {
        val raw = _uiState.value.url.trim()
        if (raw.isEmpty()) {
            _uiState.update { it.copy(errorMessage = UiText.Res(R.string.home_error_no_url)) }
            return
        }
        // 允许整段分享文案：解析前统一清洗为 URL
        val resolved = ClipboardUrlExtractor.cleanInput(raw)
        if (resolved.isEmpty()) {
            _uiState.update { it.copy(errorMessage = UiText.Res(R.string.home_error_no_valid_url)) }
            return
        }
        if (resolved != raw) {
            _uiState.update { it.copy(url = resolved) }
        }
        parseUrl(resolved)
    }

    /** 现有解析逻辑主体（剪贴板自动/立即解析与手动解析共用，不建第二套）。auto=true 时附带状态反馈与事件日志。 */
    private fun parseUrl(url: String, auto: Boolean = false) {
        parseJob?.cancel()
        // P2-001：本轮解析的世代号。取消 / 新解析会立即作废它。
        val sessionToken = parseSessions.begin()
        val startedAt = System.currentTimeMillis()
        parseJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    result = null,
                    sourceLabel = null,
                    douyinPrompt = null,
                    autoParseNote = if (auto) it.autoParseNote else null,
                )
            }
            // 冷启动竞态修复：抖音 Cookie 恢复（WebView provider 就绪 + 轮询读取）先完成，
            // 避免剪贴板自动/手动解析在恢复前发起导致 COOKIE_REQUIRED 误判
            douyinWebSession.awaitStartupRestore()
            parserManager.parse(url)
                .onSuccess { outcome ->
                    // P2-001 竞态闸门：本会话在等待期间被取消 / 被新解析抢占 → 结果不得落地。
                    // （parse() 返回后到这里没有挂起点，所以必须在写 uiState 之前显式校验。）
                    if (!parseSessions.acceptResult(sessionToken)) {
                        AppLogRepository.i(
                            LogTags.PARSER,
                            "丢弃过期解析结果（会话已取消/被抢占）mediaType=${outcome.info.mediaType.name}",
                        )
                        return@onSuccess
                    }
                    // Phase 6.5：只记汇总（不打印 URL/Cookie/完整 thumbnail/完整 format JSON）
                    val info = outcome.info
                    val grouped = FormatDisplayGrouper.group(info.streams).size
                    val thumbOk = !info.thumbnail.isNullOrBlank()
                    when (info.mediaType) {
                        com.kuaixia.app.core.model.MediaType.IMAGE,
                        com.kuaixia.app.core.model.MediaType.IMAGE_COLLECTION ->
                            AppLogRepository.i(
                                LogTags.PARSER,
                                (if (info.imageItems.size > 1) "ImageCollection" else "Image") +
                                    " mediaType=${info.mediaType.name} thumbnail=$thumbOk " +
                                    "imageItems=${info.imageItems.size} source=${outcome.source}",
                            )
                        else -> {
                            val previewOk = info.streams.any {
                                !it.isDash && it.url.startsWith("http") ||
                                    it.isDash && it.videoUrl?.startsWith("http") == true
                            }
                            AppLogRepository.i(
                                LogTags.PARSER,
                                "VideoResult mediaType=VIDEO thumbnail=$thumbOk previewUrl=$previewOk " +
                                    "formats=${info.streams.size} formatGroups=$grouped source=${outcome.source}",
                            )
                        }
                    }
                    _uiState.update {
                        it.copy(isLoading = false, result = outcome.info, sourceLabel = outcome.source)
                    }
                          if (auto) {
                              val elapsed = System.currentTimeMillis() - startedAt
                              AppLogRepository.i(
                                  LogTags.PARSER,
                                  "Clipboard auto-parse finished success=true source=${outcome.source} " +
                                      "elapsedMs=$elapsed mediaType=${outcome.info.mediaType.name}",
                              )
                              // BUG-002：解析成功 → 用最终网页 URL 登记作品身份（短链别名）
                              registerParsedIdentity(url, outcome.info.webpageUrl)
                              _uiState.update {
                                  it.copy(
                                      autoParseNote = UiText.Res(R.string.home_auto_parse_finished),
                                      autoParseNoteError = false,
                                  )
                              }
                              identityBook.markFinished()
                          }
                }
                .onFailure { e ->
                    // P2-001 竞态闸门：取消后不得把失败态写回（否则会覆盖用户已经恢复的 idle 状态）。
                    if (!parseSessions.acceptResult(sessionToken)) {
                        AppLogRepository.i(LogTags.PARSER, "丢弃过期解析失败态（会话已取消/被抢占）")
                        return@onFailure
                    }
                    val ae = e as? AppException
                    val rawMsg = ae?.message.orEmpty()
                    if (ae?.code == ErrorCode.COOKIE_REQUIRED &&
                        ClipboardUrlExtractor.platformOf(url) == ClipboardUrlExtractor.Platform.DOUYIN
                    ) {
                        // 抖音需要新鲜 Cookie：刷新会话状态，展示登录引导（不再显示笼统错误）
                        val hasSession = withContext(Dispatchers.IO) {
                            douyinWebSession.refreshAndCount() > 0
                        }
                        AppLogRepository.i(
                            LogTags.PARSER,
                            "douyin COOKIE_REQUIRED 会话已建立=$hasSession",
                        )
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = null,
                                douyinPrompt = DouyinPrompt(hasSession = hasSession),
                            )
                        }
                        if (auto) {
                            AppLogRepository.w(
                                LogTags.PARSER,
                                "Clipboard auto-parse failed code=${ae.code} elapsedMs=${System.currentTimeMillis() - startedAt}",
                            )
                            _uiState.update {
                                it.copy(
                                    autoParseNote = if (hasSession) {
                                        UiText.Res(R.string.home_auto_parse_failed_session_invalid)
                                    } else {
                                        UiText.Res(R.string.home_auto_parse_failed_cookie)
                                    },
                                      autoParseNoteError = true,
                                  )
                              }
                              identityBook.markFinished()
                          }
                      } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = if (rawMsg.isEmpty()) {
                                    UiText.Res(R.string.home_error_parse_failed)
                                } else {
                                    UiText.Dynamic(rawMsg)
                                },
                            )
                        }
                        if (auto) {
                            AppLogRepository.w(
                                LogTags.PARSER,
                                "Clipboard auto-parse failed code=${ae?.code ?: "?"} " +
                                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
                            )
                              _uiState.update {
                                  it.copy(
                                      autoParseNote = UiText.Res(R.string.home_auto_parse_failed, listOf(rawMsg)),
                                      autoParseNoteError = true,
                                  )
                              }
                              identityBook.markFinished()
                          }
                    }
                }
        }
    }

    /** 自动解析入口（仅由 checkClipboardAndAutoParse TRIGGER 调用）。 */
    private fun autoParseUrl(url: String, source: ClipboardSource) {
        val platform = ClipboardUrlExtractor.platformOf(url).label
        AppLogRepository.i(
            LogTags.PARSER,
            "Clipboard AUTO_PARSE_STARTED source=${source.name} " +
                "url=${LogSanitizer.sanitizeUrl(url)} platform=$platform",
        )
        _uiState.update {
            it.copy(autoParseNote = UiText.Res(R.string.home_auto_parse_started), autoParseNoteError = false)
        }
        parseUrl(url, auto = true)
    }

    /** 首页「打开抖音登录 / 重新登录」：由 UI 层触发导航（携带当前 URL）。 */
    fun currentUrl(): String = _uiState.value.url

    /** 忽略抖音登录引导（下次解析前不打扰）。 */
    fun dismissDouyinPrompt() {
        _uiState.update { it.copy(douyinPrompt = null) }
    }

    /**
     * 取消当前解析任务。
     *
     * P2-001：取消必须让**当前解析会话立即失效**，而不只是取消协程 ——
     * 顺序上先作废世代（使在途 session 的结果/回调全部失效），再 cancel Job
     * （触发 WebViewParser 的 CancellationException 分支 → finally → 同步 teardown）。
     */
    fun cancel() {
          parseSessions.invalidate()
          parseJob?.cancel()
          parseJob = null
          identityBook.resetInFlight()
          _uiState.update { it.copy(isLoading = false) }
    }

    /** 下载选中的流：由解析结果 + 选中流创建下载任务并入队。 */
    fun downloadSelected(stream: StreamInfo) {
        val video = _uiState.value.result
        if (video == null) {
            AppLogRepository.w(LogTags.DOWNLOAD, "点击下载但无解析结果，忽略")
            return
        }
        AppLogRepository.i(
            LogTags.DOWNLOAD,
            "用户点击下载 quality=${stream.quality ?: stream.formatId} " +
                "dash=${stream.isDash} ext=${stream.ext}",
        )
        runCatching {
            downloadRepository.enqueue(downloadRepository.createTask(video, stream))
        }.onFailure { e ->
            // 创建任务阶段异常也必须被捕获，绝不让 App 崩溃
            AppLogRepository.e(LogTags.DOWNLOAD, "创建下载任务失败", e)
            _uiState.update {
                it.copy(errorMessage = UiText.Res(R.string.home_error_create_task, listOf(e.message ?: "")))
            }
        }
    }

    /** 下载图集/单图里的第 index 张图片（0 起）。 */
    fun downloadImageAt(video: VideoInfo, index: Int) {
        val total = video.imageItems.size
        if (total == 0 || index !in 0 until total) return
        runCatching {
            val task = downloadRepository.createImageTask(video, video.imageItems[index], index + 1, total)
            downloadRepository.enqueue(task)
        }.onFailure { e ->
            AppLogRepository.e(LogTags.DOWNLOAD, "创建图片下载任务失败 index=$index", e)
            _uiState.update {
                it.copy(errorMessage = UiText.Res(R.string.home_error_create_image_task, listOf(e.message ?: "")))
            }
        }
    }

    /** 下载全部图片（图集）。 */
    fun downloadImages(video: VideoInfo) {
        if (video.imageItems.isEmpty()) return
        runCatching { downloadRepository.enqueueImages(video, video.imageItems) }
            .onFailure { e ->
                AppLogRepository.e(LogTags.DOWNLOAD, "批量创建图片下载任务失败", e)
                _uiState.update {
                    it.copy(errorMessage = UiText.Res(R.string.home_error_create_image_task, listOf(e.message ?: "")))
                }
            }
    }

    /** 下载选中的若干张图片（indices 0 起，按传入顺序命名保持原图序）。 */
    fun downloadImagesSelected(video: VideoInfo, indices: List<Int>) {
        if (video.imageItems.isEmpty() || indices.isEmpty()) return
        val sorted = indices.distinct().filter { it in video.imageItems.indices }.sorted()
        if (sorted.isEmpty()) return
        runCatching {
            val total = video.imageItems.size
            sorted.forEach { idx ->
                val task = downloadRepository.createImageTask(video, video.imageItems[idx], idx + 1, total)
                downloadRepository.enqueue(task)
            }
        }.onFailure { e ->
            AppLogRepository.e(LogTags.DOWNLOAD, "创建选中图片下载任务失败", e)
            _uiState.update {
                it.copy(errorMessage = UiText.Res(R.string.home_error_create_image_task, listOf(e.message ?: "")))
            }
        }
    }

    override fun onCleared() {
        // P2-001：VM 销毁也作废会话，避免在途解析把结果写进已废弃的 uiState。
        parseSessions.invalidate()
        parseJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** RESUME 源剪贴板检查最小间隔（毫秒）：吸收连续导航产生的密集 RESUME。 */
        const val RESUME_CHECK_MIN_INTERVAL_MS = 1500L
    }
}
