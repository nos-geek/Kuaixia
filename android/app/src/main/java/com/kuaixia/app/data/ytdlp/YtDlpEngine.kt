package com.kuaixia.app.data.ytdlp

import android.content.Context
import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "YtDlp"

/** yt-dlp 初始化状态。 */
enum class YtDlpInitState { NOT_STARTED, INITIALIZING, READY, FAILED }

/** yt-dlp 运行状态（供 Debug 页面展示）。 */
data class YtDlpStatus(
    val initState: YtDlpInitState = YtDlpInitState.NOT_STARTED,
    val initMessage: String? = null,
    /** yt-dlp 版本号（`--version` 输出），用于诊断页展示。 */
    val version: String? = null,
    val lastCommand: String? = null,
    val lastExitCode: Int? = null,
    val lastStdout: String? = null,
    val lastStderr: String? = null,
    /** 最近一次执行的耗时（毫秒）。 */
    val lastElapsedMs: Long? = null,
)

/** 环境文件检查结果。 */
data class FileStatus(val label: String, val ok: Boolean, val detail: String)

/** 一次执行的原样结果。 */
data class RawResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * yt-dlp 引擎：封装 youtubedl-android 库。
 *
 * 职责边界：
 * - 进程调用、JSON 输出获取、取消/超时、状态与日志；
 * - 不负责把结果转成领域模型（那是 YtDlpMapper 的职责）。
 *
 * 原则：不在主线程运行；取消/超时销毁进程；日志不输出敏感信息。
 * Phase 2.5：把真实解析（extractInfo）的诊断信息（command/exitCode/stdout/stderr/耗时）
 * 也写入 [status]，便于首页解析失败后到诊断页回看；并按 stderr 文本做错误分类。
 */
class YtDlpEngine(
    context: Context,
    private val provider: YtDlpProvider = BundledYtDlpProvider(),
    /** 抖音 WebView 会话 Cookie 文件提供者（Netscape 格式；仅 douyin 解析时用 `--cookies` 注入）。 */
    private val douyinCookieJarProvider: () -> java.io.File? = { null },
) {

    private val appContext = context.applicationContext
    private val initialized = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _status = MutableStateFlow(YtDlpStatus())
    val status: StateFlow<YtDlpStatus> = _status.asStateFlow()

    /** 本地 yt-dlp 是否已初始化成功。 */
    fun isAvailable(): Boolean = initialized.get()

    /** 在 Application 生命周期里后台预热初始化（幂等）。初始化成功后缓存版本号。 */
    suspend fun initialize() {
        if (initialized.get()) return
        if (_status.value.initState == YtDlpInitState.INITIALIZING) return
        withContext(Dispatchers.IO) {
            if (initialized.get()) return@withContext
            _status.value = YtDlpStatus(initState = YtDlpInitState.INITIALIZING)
            try {
                ensureInitBlocking()
                cacheVersion()
            } catch (e: AppException) {
                _status.value = YtDlpStatus(initState = YtDlpInitState.FAILED, initMessage = e.message)
            }
        }
    }

    /** 阻塞初始化，须在 IO 线程调用。失败抛出带中文提示的 [AppException]。 */
    private fun ensureInitBlocking() {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            AppLogger.i("init start", TAG)
            val started = System.currentTimeMillis()
            try {
                YoutubeDL.getInstance().init(appContext)
                initialized.set(true)
                // 库内置 yt-dlp 装好后，用 App assets 携带的版本做本地覆盖升级（离线、幂等）
                applyProviderUpgrade()
                AppLogger.i("init ok elapsed_ms=${System.currentTimeMillis() - started}", TAG)
                _status.value = _status.value.copy(
                    initState = YtDlpInitState.READY,
                    initMessage = null,
                )
            } catch (e: Throwable) {
                val msg = "${e::class.simpleName}: ${e.message}"
                AppLogger.e("init fail $msg", e, TAG)
                _status.value = YtDlpStatus(initState = YtDlpInitState.FAILED, initMessage = msg)
                throw AppException(ErrorCode.YTDLP_NOT_AVAILABLE, "本地解析组件初始化失败（$msg）")
            }
        }
    }

    /** 执行 `--version` 并缓存版本号到 status。 */
    private fun cacheVersion() {
        try {
            val raw = runRaw(YoutubeDLRequest("").apply { addOption("--version") })
            val version = firstLine(raw.stdout) ?: firstLine(raw.stderr)
            if (version != null) {
                _status.value = _status.value.copy(version = version)
                AppLogger.i("yt-dlp version=$version", TAG)
            }
        } catch (e: Exception) {
            AppLogger.w("cache version fail ${e.message}", TAG)
        }
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()

    /** 解析 URL，返回 yt-dlp 原始 JSON 结果（已转换为 [YtDlpResult]）。 */
    suspend fun extractInfo(url: String): Result<YtDlpResult> = withContext(Dispatchers.IO) {
        try {
            ensureInitBlocking()
            val result = withTimeout(PARSE_TIMEOUT_MS) {
                runInterruptible { dumpSingleJson(url) }
            }
            Result.success(result)
        } catch (e: TimeoutCancellationException) {
            AppLogger.w("parse timeout", TAG)
            Result.failure(AppException(ErrorCode.TIMEOUT, "解析超时，请稍后重试"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppException) {
            Result.failure(e)
        } catch (e: SerializationException) {
            AppLogger.e("json parse fail", e, TAG)
            Result.failure(AppException(ErrorCode.PARSER_ERROR, "解析结果异常，请稍后重试"))
        } catch (e: Exception) {
            AppLogger.e("unexpected error", e, TAG)
            Result.failure(AppException(ErrorCode.UNKNOWN_ERROR, "解析失败，请稍后重试"))
        }
    }

    /** 自检：执行 `yt-dlp --version`，把原样结果写入 status 并缓存版本号。 */
    suspend fun runVersionCheck(): YtDlpStatus {
        runSelfTest(
            request = YoutubeDLRequest("").apply { addOption("--version") },
            command = "--version",
        )
        val version = _status.value.lastStdout?.let(::firstLine)
        if (version != null) _status.value = _status.value.copy(version = version)
        return _status.value
    }

    /** 自检：执行 `--dump-single-json --no-playlist <url>`，把原样结果写入 status。可选强制 IPv4。 */
    suspend fun runDumpJsonForDebug(url: String, ipv4: Boolean = false): YtDlpStatus {
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            if (ipv4) addOption("-4")
        }
        applyBrowserHeaders(request, url)
        return runSelfTest(request, "--dump-single-json --no-playlist $url" + if (ipv4) " -4" else "")
    }

    /** 自检统一入口：先确保初始化，再执行命令；初始化失败时返回失败状态而非抛异常。 */
    private suspend fun runSelfTest(request: YoutubeDLRequest, command: String): YtDlpStatus =
        withContext(Dispatchers.IO) {
            try {
                ensureInitBlocking()
            } catch (e: AppException) {
                AppLogger.w("self-test blocked by init failure: ${e.message}", TAG)
                val status = YtDlpStatus(
                    initState = YtDlpInitState.FAILED,
                    initMessage = e.message,
                    lastCommand = command,
                    lastExitCode = -1,
                    lastStderr = e.message,
                )
                _status.value = status
                return@withContext status
            }
            try {
                val started = System.currentTimeMillis()
                val result = withTimeout(SELF_TEST_TIMEOUT_MS) {
                    runInterruptible { runRaw(request) }
                }
                updateStatus(command, result, System.currentTimeMillis() - started)
            } catch (e: TimeoutCancellationException) {
                AppLogger.w("self-test timeout command=$command", TAG)
                _status.value = _status.value.copy(
                    lastCommand = command,
                    lastExitCode = -2,
                    lastStdout = null,
                    lastStderr = "执行超时（>${SELF_TEST_TIMEOUT_MS / 1000}s）——疑似网络 hang（IPv6/DNS 阻塞）",
                    lastElapsedMs = SELF_TEST_TIMEOUT_MS,
                )
            } catch (e: CancellationException) {
                throw e
            }
            _status.value
        }

    /** 检查 yt-dlp 运行环境文件（yt-dlp 二进制 / Python 运行时 / QuickJS）。 */
    fun fileStatus(): List<FileStatus> {
        val base = File(appContext.noBackupFilesDir, YoutubeDL.baseName)
        val ytdlpBin = File(base, "${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
        val pythonDir = File(base, "packages/python")
        val nativeDir = appContext.applicationInfo.nativeLibraryDir

        return listOf(
            FileStatus("yt-dlp 二进制", ytdlpBin.exists() && ytdlpBin.length() > 0,
                "${ytdlpBin.absolutePath}  size=${if (ytdlpBin.exists()) ytdlpBin.length() else 0}"),
            FileStatus("Python 运行时(已解压)", pythonDir.exists(),
                pythonDir.absolutePath),
            FileStatus("libpython.so", File(nativeDir, "libpython.so").exists(),
                "$nativeDir/libpython.so"),
            FileStatus("libpython.zip.so", File(nativeDir, "libpython.zip.so").exists(),
                "$nativeDir/libpython.zip.so"),
            FileStatus("libqjs.so", File(nativeDir, "libqjs.so").exists(),
                "$nativeDir/libqjs.so"),
        )
    }

    /**
     * Provider 本地升级：若 App assets 携带了新版 yt-dlp zipapp，则用其覆盖
     * youtubedl-android 解压出的 bundled 版（库只在目标文件不存在时解压，覆盖一次即持久）。
     *
     * 幂等：marker 记录已应用版本；失败仅告警，不影响库内置旧版可用性（安全兜底）。
     * 本阶段只做「APK 内置替换」，不做任何网络下载。
     */
    private fun applyProviderUpgrade() {
        val assetName = provider.upgradeAssetName() ?: return
        val bundledVersion = provider.getBundledVersion()
        runCatching {
            val base = File(appContext.noBackupFilesDir, YoutubeDL.baseName)
            val targetDir = File(base, YoutubeDL.ytdlpDirName)
            val target = File(targetDir, YoutubeDL.ytdlpBin)
            if (!targetDir.exists() && !targetDir.mkdirs()) return@runCatching

            // marker 命中同版本 → 已升级过，跳过
            val marker = File(base, MARKER_PREFIX + (bundledVersion ?: "unknown"))
            if (marker.exists()) return@runCatching

            appContext.assets.open(assetName).use { input ->
                val tmp = File(targetDir, ".yt-dlp.update.tmp")
                tmp.outputStream().use { out -> input.copyTo(out) }
                if (tmp.length() <= 0) {
                    tmp.delete()
                    return@runCatching
                }
                val renamed = if (target.exists()) {
                    // POSIX rename 原子替换；个别实现需先删旧文件
                    tmp.renameTo(target) || (target.delete() && tmp.renameTo(target)) ||
                        tmp.copyTo(target, overwrite = true).also { tmp.delete() }.exists()
                } else {
                    tmp.renameTo(target)
                }
                if (!renamed) {
                    tmp.delete()
                    AppLogger.w("yt-dlp provider upgrade: rename failed, keep bundled", TAG)
                    return@runCatching
                }
            }
            marker.writeText(bundledVersion ?: "updated")
            AppLogger.i("yt-dlp upgraded to ${bundledVersion ?: "provider asset"} size=${target.length()}", TAG)
        }.onFailure { e ->
            AppLogger.e("yt-dlp provider upgrade failed（保留 bundled，不影响使用）", e, TAG)
        }
    }

    /** 执行 yt-dlp 并返回原样结果。阻塞调用，须在 IO 线程执行。 */
    private fun runRaw(request: YoutubeDLRequest): RawResult {
        val processId = "cmd-${System.nanoTime()}"
        val started = System.currentTimeMillis()
        AppLogger.i("execute start processId=$processId command=${request.buildCommand().joinToString(" ")}", TAG)
        try {
            val response = YoutubeDL.getInstance().execute(request, processId)
            AppLogger.i(
                "execute ok exit=${response.exitCode} elapsed_ms=${System.currentTimeMillis() - started} " +
                    "out=${response.out.take(200)} err=${response.err.take(200)}",
                TAG,
            )
            return RawResult(response.exitCode, response.out, response.err)
        } catch (e: YoutubeDLException) {
            AppLogger.w("execute fail stderr=${e.message?.take(500)}", TAG)
            return RawResult(-1, "", e.message.orEmpty())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("yt-dlp interrupted", e)
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    /** 执行 `--dump-single-json` 并解析 JSON；同时把诊断信息写入 status。 */
    private fun dumpSingleJson(url: String): YtDlpResult {
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--no-color")
        }
        applyBrowserHeaders(request, url)
        val command = "--dump-single-json --no-playlist --no-warnings --no-color $url"
        val started = System.currentTimeMillis()
        AppLogger.i("parse start url=$url", TAG)
        val raw = runRaw(request)
        val elapsed = System.currentTimeMillis() - started
        updateStatus(command, raw, elapsed)
        if (raw.stdout.isBlank()) {
            AppLogger.w("parse empty stdout exit=${raw.exitCode} stderr=${raw.stderr.take(300)}", TAG)
            throw mapStderrError(raw.stderr)
        }
        AppLogger.i("parse ok elapsed_ms=$elapsed json_len=${raw.stdout.length}", TAG)
        return json.decodeFromString<YtDlpResult>(raw.stdout)
    }

    private fun updateStatus(command: String, result: RawResult, elapsedMs: Long? = null) {
        _status.value = _status.value.copy(
            lastCommand = command,
            lastExitCode = result.exitCode,
            lastStdout = result.stdout.take(MAX_DISPLAY),
            lastStderr = result.stderr.take(MAX_DISPLAY),
            lastElapsedMs = elapsedMs,
        )
    }

    /**
     * 命令行日志脱敏：`--add-header Cookie: <值>` 的 Cookie 值只保留占位。
     * 其余参数原样（UA/Referer 等非敏感，保留下便于诊断）。
     */
    private fun redactedCommand(request: YoutubeDLRequest): String {
        val args = runCatching { request.buildCommand() }.getOrDefault(emptyList())
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg == "--add-header" && i + 1 < args.size) {
                val value = args[i + 1]
                out += arg
                out += if (value.startsWith("Cookie:", ignoreCase = true)) "Cookie: <redacted>" else value
                i += 2
                continue
            }
            out += arg
            i++
        }
        return out.joinToString(" ")
    }

    /** 补浏览器请求头，规避站点风控（如 Bilibili 412）。UA/Accept-Language 全局加；Referer/Origin 仅 B 站。 */
    private fun applyBrowserHeaders(request: YoutubeDLRequest, url: String) {
        request.addOption("--add-header", "User-Agent: $BROWSER_USER_AGENT")
        request.addOption("--add-header", "Accept-Language: $ACCEPT_LANGUAGE")
        if (isBilibili(url)) {
            request.addOption("--add-header", "Referer: https://www.bilibili.com/")
            request.addOption("--add-header", "Origin: https://www.bilibili.com")
        }
        // Phase 6：仅 douyin 使用快夏 WebView 会话的 Cookie —— 以 Netscape Cookie 文件 + `--cookies` 注入。
        // 禁止把 Cookie 拼成 `--add-header Cookie:`（yt-dlp 会告警 Deprecated 且可能被站点拒绝）。
        if (isDouyin(url)) {
            val jar = douyinCookieJarProvider()?.takeIf { it.exists() && it.length() > 0 }
            if (jar != null) {
                request.addOption("--cookies", jar.absolutePath)
            } else {
                AppLogger.w("douyin cookie jar 缺失（未登录或导出失败），将无 Cookie 解析 url=$url", TAG)
            }
        }
    }

    private fun isBilibili(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv"
    }

    private fun isDouyin(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "douyin.com" || host.endsWith(".douyin.com") || host.endsWith(".iesdouyin.com")
    }

    /**
     * 把 yt-dlp 的 stderr 文本映射为带分类错误码的 [AppException]。
     * [detail] 保留完整 stderr，供开发日志排查；UI 只显示 [AppException.message]。
     */
    private fun mapStderrError(stderr: String): AppException {
        val m = stderr.lowercase()
        val code = when {
            // 平台要求活跃 Cookie（抖音 "Fresh cookies ... are needed"）
            "fresh cookies" in m || "cookies are needed" in m || "cookie" in m ->
                ErrorCode.COOKIE_REQUIRED

            // 平台风控（B 站 HTTP 412 等）
            "412" in stderr || "precondition" in m || "risk control" in m || "blocked" in m ->
                ErrorCode.PLATFORM_BLOCKED

            // 需要登录
            "sign in" in m || "login" in m || "private video" in m ->
                ErrorCode.LOGIN_REQUIRED

            // 不支持 / 无效链接
            "unsupported url" in m || "not a valid url" in m ->
                ErrorCode.UNSUPPORTED_PLATFORM

            // 地区限制
            "not available in your country" in m || "geo-restricted" in m || "geo restriction" in m ->
                ErrorCode.ACCESS_DENIED

            // 视频不存在
            "video unavailable" in m || "this video is unavailable" in m ||
                "not found" in m || "no video" in m ->
                ErrorCode.MEDIA_NOT_FOUND

            // 会员
            "premium" in m || "members-only" in m ->
                ErrorCode.ACCESS_DENIED

            // 网络问题
            "timed out" in m || "timeout" in m || "connection" in m || "unreachable" in m ||
                "resolve" in m || "name resolution" in m || "getaddrinfo" in m || "network" in m ->
                ErrorCode.NETWORK_ERROR

            // ffmpeg
            "ffmpeg" in m || "ffprobe" in m ->
                ErrorCode.PARSER_ERROR

            else -> ErrorCode.UNKNOWN_ERROR
        }
        return AppException(code, messageFor(code), detail = stderr)
    }

    private fun messageFor(code: String): String = when (code) {
        ErrorCode.INVALID_URL -> "链接无效，仅支持 http/https"
        ErrorCode.UNSUPPORTED_PLATFORM -> "暂不支持该平台或链接无效"
        ErrorCode.PLATFORM_BLOCKED -> "该平台触发了风控拦截，暂时无法解析"
        ErrorCode.COOKIE_REQUIRED -> "该平台需要登录或 Cookie 才能解析"
        ErrorCode.NETWORK_ERROR -> "网络连接失败，请检查网络后重试"
        ErrorCode.TIMEOUT -> "解析超时，请稍后重试"
        ErrorCode.LOGIN_REQUIRED -> "该视频需要登录才能解析"
        ErrorCode.ACCESS_DENIED -> "该视频有访问限制，无法解析"
        ErrorCode.MEDIA_NOT_FOUND -> "视频不存在或已被删除"
        ErrorCode.PARSER_ERROR -> "解析结果异常，请稍后重试"
        else -> "解析失败，请稍后重试"
    }

    private companion object {
        const val PARSE_TIMEOUT_MS = 30_000L
        const val SELF_TEST_TIMEOUT_MS = 45_000L
        const val MAX_DISPLAY = 4_000
        const val MARKER_PREFIX = ".ytdlp-kuaixia-updated-"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"
    }
}
