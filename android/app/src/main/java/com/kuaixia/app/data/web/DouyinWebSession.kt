package com.kuaixia.app.data.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.LogTags
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 抖音网页登录会话（Phase 6 / 6.6 启动恢复）。
 *
 * 只读取/清除 **快夏自己 WebView** 的 Cookie（[CookieManager] 实例为应用私有），
 * 不读 Chrome/Edge/抖音 App 的 Cookie，不做任何系统级/沙箱外操作。
 *
 * 安全约束（硬性）：
 * - Cookie **值** 绝不写入日志 / Room / DataStore / UI / crash report；
 * - 日志只输出 key-value 对数（如 `count=63`）与状态；
 * - 不持久化会话时间，是否过期以 yt-dlp 实际返回 COOKIE_REQUIRED 为准。
 *
 * 启动恢复（Phase 6.6 修复）：
 * Android WebView 的 Cookie 存储要等**本进程第一个 WebView 实例创建后**才异步就绪，
 * 冷启动若直接 `CookieManager.getCookie(...)` 会读到空 → 误判「未登录」。
 * [warmUpAndRestore] 在 App 启动时创建并立即销毁一个一次性 WebView 触发 provider 初始化，
 * 随后轮询读取已持久化的 douyin Cookie；有则 `hasSession=true`（只代表「Cookie 存在且可能有效」，
 * 是否真的有效由 yt-dlp 返回 COOKIE_REQUIRED 决定）。[awaitStartupRestore] 供解析前轻量等待该过程完成。
 */
class DouyinWebSession(context: Context) {

    private val appContext = context.applicationContext

    private val _hasSession = MutableStateFlow(false)

    /** 当前是否检测到抖音会话 Cookie（供设置页等 UI 显示「已建立/未建立」）。 */
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    /** 登录成功事件（携带待重试的原始 URL）。首页收集后自动用新会话重试一次。 */
    private val _sessionEstablished = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val sessionEstablished: SharedFlow<String> = _sessionEstablished.asSharedFlow()

    /** 启动恢复是否已完成（成功/失败/超时都会置完成）。 */
    private val restoreDone = CompletableDeferred<Unit>()

    /** 默认登录/首页地址。 */
    companion object {
        const val DOUYIN_PAGE = "https://www.douyin.com/"
        private const val RESTORE_MAX_MS = 3_000L
        private const val RESTORE_POLL_MS = 150L
    }

    /** 读取 Cookie 的锚点域名（v.douyin.com 短链最终跳转 www.douyin.com，Cookie 一般落在 douyin.com 域）。 */
    private val readUrls = listOf(
        "https://www.douyin.com/",
        "https://v.douyin.com/",
        "https://douyin.com/",
    )

    /**
     * 启动恢复：创建并销毁一次性 WebView 让 Chromium Cookie 存储就绪，然后轮询读取。
     * 幂等：已完成则直接返回。App 启动后台调用。
     */
    suspend fun warmUpAndRestore() {
        if (restoreDone.isCompleted) return
        try {
            // 主线程一次性 WebView 触发 WebView provider 初始化（cookie store 随之异步加载）
            runCatching {
                withContext(Dispatchers.Main) {
                    WebView(appContext).apply {
                        settings.javaScriptEnabled = false
                    }.destroy()
                }
            }
            val count = pollForCookies()
            _hasSession.value = count > 0
            AppLogRepository.i(
                LogTags.DOUYIN,
                if (count > 0) "抖音 WebView Cookie（启动恢复）count=$count"
                else "抖音 WebView Cookie（启动恢复）count=0",
            )
        } finally {
            restoreDone.complete(Unit)
        }
    }

    /** 解析/剪贴板自动解析前的轻量等待：确保启动恢复至少完成一次（最多 ~3.5s，幂等则立即返回）。 */
    suspend fun awaitStartupRestore(): Boolean {
        if (restoreDone.isCompleted) return _hasSession.value
        return withTimeoutOrNull(RESTORE_MAX_MS + 500L) { restoreDone.await() } != null
    }

    /** 刷新会话状态并返回 Cookie key-value 对数；>0 视为会话已建立。 */
    fun refreshAndCount(): Int {
        val count = countCookiePairs(readCookieRaw())
        val domains = readUrls.count { url ->
            val raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            countCookiePairs(raw) > 0
        }
        _hasSession.value = count > 0
        AppLogRepository.i(
            LogTags.DOUYIN,
            if (count > 0) "抖音 WebView Cookie updated count=$count domains=$domains"
            else "抖音 WebView Cookie（未检测到）",
        )
        return count
    }

    /** 当前抖音 Cookie 原始串（仅供内部导出；调用方不得将其入日志/UI）。无则 null。 */
    fun cookieRawOrNull(): String? {
        val raw = readCookieRaw()
        return raw?.takeIf { countCookiePairs(it) > 0 }
    }

    /**
     * 导出 Netscape/Mozilla Cookie 文件（App 私有目录），供 yt-dlp `--cookies` 使用。
     *
     * 说明：Android 公开 API（CookieManager）只能拿到每个域名的 `name=value` 串，
     * **拿不到逐条 Cookie 的真实 domain/path/expires/secure**（这些在 WebView 私有库里）。
     * 因此以会话锚点为准合成：domain `.douyin.com`（含子域）、path `/`、secure TRUE、
     * expiry `0`（会话 Cookie，yt-dlp 视为 session；**不因缺 expires 丢弃**）。
     * 已按 key 去重合并（沿用既有逻辑）。返回 File；无 Cookie 返回 null。
     */
    fun exportCookieJar(): File? {
        val raw = readCookieRaw() ?: return null
        val pairs = raw.split(';').mapNotNull { pair ->
            val p = pair.trim()
            val idx = p.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = p.substring(0, idx).trim()
            val value = p.substring(idx + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to sanitizeValue(value)
        }
        if (pairs.isEmpty()) return null

        val dir = File(appContext.filesDir, "ytdlp").apply { mkdirs() }
        val jar = File(dir, "douyin-cookie.txt")
        val sb = StringBuilder()
        sb.append("# Netscape HTTP Cookie File\n")
        sb.append("# https://curl.se/docs/http-cookies.html\n")
        sb.append("# 快夏 WebView douyin 会话导出；仅供本 App 本地解析使用\n")
        val seen = HashSet<String>()
        pairs.forEach { (name, value) ->
            if (seen.add(name)) {
                sb.append(".douyin.com\tTRUE\t/\tTRUE\t0\t")
                    .append(sanitizeField(name)).append('\t').append(value).append('\n')
            }
        }
        runCatching { jar.writeText(sb.toString()) }
            .onFailure {
                AppLogRepository.e(LogTags.DOUYIN, "Cookie 导出写文件失败", it)
                return null
            }
        AppLogRepository.i(
            LogTags.DOUYIN,
            "Cookie export success count=${pairs.size} pathExists=${jar.exists()}",
        )
        return jar.takeIf { it.exists() && it.length() > 0 }
    }

    /** Cookie 文件路径（可能不存在；供日志/诊断确认）。 */
    fun cookieJarFile(): File = File(File(appContext.filesDir, "ytdlp"), "douyin-cookie.txt")

    /** 直接刷一次 Cookie 并重新导出 jar；返回 jar（无 Cookie 则 null）。供「刷新后重试」使用。 */
    fun refreshAndExportJar(): File? {
        refreshAndCount()
        return exportCookieJar()
    }

    private fun sanitizeValue(v: String): String = v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun sanitizeField(v: String): String =
        v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(' ', '_')

    /** 清除快夏自己 WebView 的全部 Cookie（应用私有 CookieManager）+ 删除导出的 Cookie 文件，并刷新状态。 */
    fun clear() {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        runCatching { cookieJarFile().delete() }
        _hasSession.value = false
        AppLogRepository.i(LogTags.DOUYIN, "抖音 WebView Cookie 已清除")
    }

    /** 「我已登录，继续」确认：刷新状态；如有待重试 URL 则广播一次（首页自动重试）。 */
    fun confirmEstablished(originalUrl: String?) {
        refreshAndCount()
        if (originalUrl.isNullOrBlank()) return
        AppLogRepository.i(LogTags.DOUYIN, "抖音登录会话已建立，等待重试原链接")
        _sessionEstablished.tryEmit(originalUrl)
    }

    /** 启动恢复轮询：最多 RESTORE_MAX_MS，读到 >0 立即返回；超时按最后一次结果返回。 */
    private suspend fun pollForCookies(): Int {
        val deadline = System.currentTimeMillis() + RESTORE_MAX_MS
        var last = 0
        while (true) {
            val raw = readCookieRaw()
            val count = countCookiePairs(raw)
            if (count > 0) return count
            last = count
            if (System.currentTimeMillis() >= deadline) return last
            delay(RESTORE_POLL_MS)
        }
    }

    /** 合并各 douyin 锚点的 Cookie 串（按 key 去重，保序）。 */
    private fun readCookieRaw(): String? = runCatching {
        val seen = LinkedHashMap<String, String>()
        readUrls.forEach { url ->
            CookieManager.getInstance().getCookie(url)?.split(';')?.forEach { pair ->
                val p = pair.trim()
                if (p.isEmpty()) return@forEach
                val key = p.substringBefore('=')
                if (key.isNotBlank() && !seen.containsKey(key)) seen[key] = p
            }
        }
        if (seen.isEmpty()) null else seen.values.joinToString("; ")
    }.getOrNull()
}

/** Cookie 串的 key-value 对数（纯逻辑，可 JVM 单测；值与脱敏无关）。 */
fun countCookiePairs(raw: String?): Int {
    if (raw.isNullOrBlank()) return 0
    return raw.split(';').count {
        val p = it.trim()
        p.contains('=') && p.substringBefore('=').trim().isNotBlank()
    }
}
