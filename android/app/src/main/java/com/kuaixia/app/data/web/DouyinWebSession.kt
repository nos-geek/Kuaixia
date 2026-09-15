package com.kuaixia.app.data.web

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * - 日志只输出 key-value 对数（如 `count=63`）、域列表与布尔（如 `hasSvWebId=true`）；
 * - 不持久化会话时间，是否过期以 yt-dlp 实际返回 COOKIE_REQUIRED 为准。
 *
 * 启动恢复（Phase 6.6 修复）：
 * Android WebView 的 Cookie 存储要等**本进程第一个 WebView 实例创建后**才异步就绪，
 * 冷启动若直接 `CookieManager.getCookie(...)` 会读到空 → 误判「未登录」。
 * [warmUpAndRestore] 在 App 启动时创建并立即销毁一个一次性 WebView 触发 provider 初始化，
 * 随后轮询读取已持久化的 douyin Cookie；有则 `hasSession=true`（只代表「Cookie 存在且可能有效」，
 * 是否真的有效由 yt-dlp 返回 COOKIE_REQUIRED 决定）。[awaitStartupRestore] 供解析前轻量等待该过程完成。
 *
 * 设备 Cookie 预热（P0，抖音高清兜底）：
 * yt-dlp 的 Douyin 提取器要求 `s_v_web_id`（缺失即 `raise_login_required` → 表现为
 * "Fresh cookies (not necessarily logged in) are needed"）。该 Cookie 由 `www.douyin.com`
 * 页面 JS 生成，而快夏解析页长期停留在 `www.iesdouyin.com` 分享页，可能从未被写入 —— 见 [ensureDeviceCookies]。
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

    /** 设备 Cookie 预热已尝试次数（进程内；防失败无限重试）。 */
    private var warmAttempts = 0

    /** 默认登录/首页地址。 */
    companion object {
        const val DOUYIN_PAGE = "https://www.douyin.com/"
        private const val RESTORE_MAX_MS = 3_000L
        private const val RESTORE_POLL_MS = 150L

        /** 设备 Cookie 预热：页面加载最长等待、加载完成后的停留时间（让页面 JS 落 Cookie）。 */
        private const val WARM_LOAD_TIMEOUT_MS = 4_000L
        private const val WARM_DWELL_MS = 400L

        /** 预热最多尝试次数（进程内），失败不再重试。 */
        private const val WARM_MAX_ATTEMPTS = 2
    }

    /**
     * 读取 Cookie 的锚点 + 写入 jar 时的归属域。
     *
     * 顺序敏感：douyin 系在前 → 同名 Cookie **优先归属 `.douyin.com`**
     * （yt-dlp 的 DouyinIE 只读 `https://www.douyin.com/` 的 Cookie，`s_v_web_id` 必须能在该域被读到）。
     * iesdouyin 系随后：快夏 WebView 实际解析页是 `www.iesdouyin.com/share/video/...`，
     * 其 Cookie 在旧实现中读不到；这里按 `.iesdouyin.com` 单独导出（与 douyin 域互不污染）。
     *
     * 说明：CookieManager 只返回 `name=value`，真实 domain 拿不到，故按锚点近似归属。
     */
    private val anchors = listOf(
        CookieAnchor("https://www.douyin.com/", ".douyin.com"),
        CookieAnchor("https://v.douyin.com/", ".douyin.com"),
        CookieAnchor("https://douyin.com/", ".douyin.com"),
        CookieAnchor("https://www.iesdouyin.com/", ".iesdouyin.com"),
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

    /**
     * P0（抖音高清兜底）：设备 Cookie 预热 —— 让 `s_v_web_id` 真正生成。
     *
     * 背景（真机取证）：yt-dlp 的 Douyin 提取器条件是
     * `not cookies.get('s_v_web_id')` → 直接 `raise_login_required`，表现为
     * `Fresh cookies (not necessarily logged in) are needed`（**不需要登录账号**）。
     * 该 Cookie 由 `www.douyin.com` 页面 **JS** 生成（无 JS 的裸请求只下发 `__ac_nonce`），
     * 而快夏 WebView 的解析页被平台导向 `www.iesdouyin.com/share/video/...`，
     * 因此进程内可能从未在 `www.douyin.com` 驻留 → `s_v_web_id` 从未写入。
     *
     * 做法（最小、隔离）：
     * 1) 用**一次性 WebView**（JS 开启）真实加载 [DOUYIN_PAGE]，等 `onPageFinished`
     *    （上限 [WARM_LOAD_TIMEOUT_MS]），再停留 [WARM_DWELL_MS] 让页面 JS 落 Cookie；
     * 2) `stopLoading()` + `destroy()`（**不读取、不保存任何页面内容**，也不进入 WebViewParser 的解析链）；
     * 3) `CookieManager.flush()` 使写入落盘，随后调用方 `exportCookieJar()` 即可读到。
     *
     * 约束与安全：
     * - 仅在「douyin + yt-dlp 因 Cookie 类错误失败、准备重试前」由 ParserManager 调用；
     * - 进程内最多 [WARM_MAX_ATTEMPTS] 次；已存在 `s_v_web_id` 时直接返回 true（零开销）；
     * - 任何异常都被吞掉：预热失败绝不影响解析主流程（仍按原逻辑导出/重试/回退）；
     * - 日志**只有** host/loaded/hasSvWebId/elapsed/attempts —— 不含任何 Cookie 名值明细。
     *
     * @return 预热后是否检测到 `s_v_web_id`。
     */
    suspend fun ensureDeviceCookies(): Boolean {
        if (hasCookieNamed(SV_WEB_ID_COOKIE)) {
            AppLogRepository.i(LogTags.DOUYIN, "Cookie 预热跳过（已存在 hasSvWebId=true）")
            return true
        }
        if (warmAttempts >= WARM_MAX_ATTEMPTS) {
            AppLogRepository.i(
                LogTags.DOUYIN,
                "Cookie 预热跳过（已达尝试上限 attempts=$warmAttempts）",
            )
            return false
        }
        warmAttempts++
        val started = System.currentTimeMillis()
        val loaded = runCatching {
            withContext(Dispatchers.Main) {
                val finished = CompletableDeferred<Unit>()
                val view = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView?, url: String?) {
                            if (!finished.isCompleted) finished.complete(Unit)
                        }
                    }
                    loadUrl(DOUYIN_PAGE)
                }
                val ok = withTimeoutOrNull(WARM_LOAD_TIMEOUT_MS) { finished.await() } != null
                // 页面已渲染：短暂停留，让页面 JS 写入设备指纹 Cookie（s_v_web_id 等）
                if (ok) delay(WARM_DWELL_MS)
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
                ok
            }
        }.getOrDefault(false)
        runCatching { CookieManager.getInstance().flush() }
        val has = hasCookieNamed(SV_WEB_ID_COOKIE)
        AppLogRepository.i(
            LogTags.DOUYIN,
            "Cookie 预热完成 host=www.douyin.com loaded=$loaded hasSvWebId=$has " +
                "elapsed_ms=${System.currentTimeMillis() - started} attempts=$warmAttempts",
        )
        return has
    }

    /** 刷新会话状态并返回 Cookie key-value 对数；>0 视为会话已建立。 */
    fun refreshAndCount(): Int {
        val count = countCookiePairs(readCookieRaw())
        val domains = readAnchorCookies().count { countCookiePairs(it.raw) > 0 }
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
     * P1：由「全部写成 `.douyin.com`」改为**按锚点分域**导出（douyin 系 → `.douyin.com`；
     * iesdouyin 系 → `.iesdouyin.com`），并新增 iesdouyin 读取锚点。合成规则见
     * [buildNetscapeCookieJar]（纯函数、可 JVM 单测）。
     *
     * 兼容说明：Android 公开 API（CookieManager）只能拿到每个域名的 `name=value` 串，
     * **拿不到逐条 Cookie 的真实 domain/path/expires/secure**（这些在 WebView 私有库里）。
     * 因此仍按锚点近似归属：path `/`、secure TRUE、expiry `0`（会话 Cookie，yt-dlp 视为 session；
     * **不因缺 expires 丢弃**），并保持按 name 去重（首个锚点优先）。返回 File；无可用 Cookie 返回 null。
     */
    fun exportCookieJar(): File? {
        val built = runCatching { buildNetscapeCookieJar(readAnchorCookies()) }.getOrNull()
        if (built == null || built.count == 0) {
            AppLogRepository.i(LogTags.DOUYIN, "Cookie 导出跳过（无可用 Cookie 条目）")
            return null
        }
        val dir = File(appContext.filesDir, "ytdlp").apply { mkdirs() }
        val jar = File(dir, "douyin-cookie.txt")
        runCatching { jar.writeText(built.text) }
            .onFailure {
                AppLogRepository.e(LogTags.DOUYIN, "Cookie 导出写文件失败", it)
                return null
            }
        // 安全日志：仅数量 / 归属域 / 是否含设备指纹 Cookie —— **绝不输出任何 Cookie 名值明细**
        AppLogRepository.i(
            LogTags.DOUYIN,
            "Cookie export success count=${built.count} domains=${built.domains} " +
                "hasSvWebId=${built.hasSvWebId} pathExists=${jar.exists()}",
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
        warmAttempts = 0
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
        readAnchorCookies().forEach { anchor ->
            anchor.raw?.split(';')?.forEach { pair ->
                val p = pair.trim()
                if (p.isEmpty()) return@forEach
                val key = p.substringBefore('=')
                if (key.isNotBlank() && !seen.containsKey(key)) seen[key] = p
            }
        }
        if (seen.isEmpty()) null else seen.values.joinToString("; ")
    }.getOrNull()

    /** 逐锚点读取原始 Cookie 串（该域无 Cookie 时为 null；调用方不得把结果写入日志/UI）。 */
    private fun readAnchorCookies(): List<AnchorCookie> = anchors.map { anchor ->
        val raw = runCatching { CookieManager.getInstance().getCookie(anchor.url) }.getOrNull()
        AnchorCookie(domain = anchor.domain, raw = raw)
    }

    /** 仅做「Cookie 名是否存在」判断（不打印、不返回任何 Cookie 值）。 */
    private fun hasCookieNamed(name: String): Boolean =
        readCookieRaw()
            ?.split(';')
            ?.any { it.trim().substringBefore('=').trim() == name } == true
}

/** Cookie 锚点：读取 URL + 写入 jar 时的归属域（不含任何 Cookie 值）。 */
internal data class CookieAnchor(val url: String, val domain: String)

/** 单次锚点读取结果：归属域 + 原始 Cookie 串（null = 该域无 Cookie）。 */
internal data class AnchorCookie(val domain: String, val raw: String?)

/** Cookie jar 合成结果（只含文本、条目数、归属域与布尔；**不含任何 Cookie 值明细**）。 */
internal data class CookieJarResult(
    val text: String,
    /** 实际写入条目数（0 = 无可用 Cookie，调用方应视为「无 jar」）。 */
    val count: Int,
    /** 实际有 Cookie 的 jar 归属域（去重、按锚点顺序）。 */
    val domains: List<String>,
    /** 是否包含设备指纹 Cookie `s_v_web_id`（布尔，不涉及其值）。 */
    val hasSvWebId: Boolean,
)

/** 设备指纹 Cookie 名（yt-dlp Douyin 提取器的硬性要求；只做存在性判断）。 */
internal const val SV_WEB_ID_COOKIE = "s_v_web_id"

/**
 * 纯逻辑（可 JVM 单测）：把各锚点的原始 Cookie 串合成为 Netscape/Mozilla Cookie Jar 文本。
 *
 * 规则（与旧实现兼容，仅新增分域）：
 * - 锚点**按传入顺序**处理，同名 Cookie **首个锚点优先**（调用方把 douyin 系排在前面，
 *   保证 `s_v_web_id` 归属 `.douyin.com` —— yt-dlp 的 DouyinIE 只读 www.douyin.com）；
 * - 每条写 `domain  TRUE  /  TRUE  0  name  value`（CookieManager 不提供真实
 *   domain/path/expires/secure，故按锚点近似归属 + 会话 Cookie）；
 * - 跳过空名/空值；name 中空格替换为 `_`，value 中的制表/换行替换为空格（沿用既有清洗）；
 * - 无有效条目时 `count == 0`（调用方据此返回 null，保持旧行为）。
 */
internal fun buildNetscapeCookieJar(anchors: List<AnchorCookie>): CookieJarResult {
    val sb = StringBuilder()
    sb.append("# Netscape HTTP Cookie File\n")
    sb.append("# https://curl.se/docs/http-cookies.html\n")
    sb.append("# 快夏 WebView douyin 会话导出；仅供本 App 本地解析使用\n")
    val seen = HashSet<String>()
    val domains = LinkedHashSet<String>()
    var count = 0
    var hasSvWebId = false
    anchors.forEach { anchor ->
        val raw = anchor.raw ?: return@forEach
        raw.split(';').forEach { pair ->
            val p = pair.trim()
            if (p.isEmpty()) return@forEach
            val idx = p.indexOf('=')
            if (idx <= 0) return@forEach
            val name = p.substring(0, idx).trim()
            val value = p.substring(idx + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach
            if (!seen.add(name)) return@forEach
            sb.append(anchor.domain).append('\t').append("TRUE").append('\t').append('/')
                .append('\t').append("TRUE").append('\t').append('0').append('\t')
                .append(sanitizeJarField(name)).append('\t').append(sanitizeJarValue(value))
                .append('\n')
            domains += anchor.domain
            count++
            if (name == SV_WEB_ID_COOKIE) hasSvWebId = true
        }
    }
    return CookieJarResult(
        text = sb.toString(),
        count = count,
        domains = domains.toList(),
        hasSvWebId = hasSvWebId,
    )
}

private fun sanitizeJarValue(v: String): String =
    v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

private fun sanitizeJarField(v: String): String =
    v.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace(' ', '_')

/** Cookie 串的 key-value 对数（纯逻辑，可 JVM 单测；值与脱敏无关）。 */
fun countCookiePairs(raw: String?): Int {
    if (raw.isNullOrBlank()) return 0
    return raw.split(';').count {
        val p = it.trim()
        p.contains('=') && p.substringBefore('=').trim().isNotBlank()
    }
}
