package com.kuaixia.app

import android.content.Context
import com.kuaixia.app.data.ParserRepository
import com.kuaixia.app.data.SettingsRepository
import com.kuaixia.app.data.download.DownloadRepository
import com.kuaixia.app.data.download.db.KuaixiaDatabase
import com.kuaixia.app.data.parser.ParserManager
import com.kuaixia.app.data.parser.ServerParser
import com.kuaixia.app.data.parser.WebViewParser
import com.kuaixia.app.data.parser.YtDlpParser
import com.kuaixia.app.data.ytdlp.YtDlpEngine
import com.kuaixia.app.data.web.DouyinWebSession

/** 手动依赖容器。Phase 1 不引入 Hilt，保持简单。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val parserRepository: ParserRepository = ParserRepository(settingsRepository)

    // Phase 6：抖音 WebView 会话（Cookie 读/清/状态；CookieManager 为应用私有）
    val douyinWebSession: DouyinWebSession = DouyinWebSession(appContext)

    // 本地 yt-dlp 引擎（惰性初始化）。解析 douyin 时自动携带 WebView 会话的 Netscape Cookie 文件。
    val ytDlpEngine: YtDlpEngine = YtDlpEngine(
        appContext,
        douyinCookieJarProvider = { douyinWebSession.cookieJarFile().takeIf { it.exists() } },
    )

    // 解析器
    private val localParser = YtDlpParser(ytDlpEngine)
    private val serverParser = ServerParser(parserRepository)

    // 解析编排（本地/服务器 + fallback + douyin Cookie 刷新重试/WebView 兜底）
    val parserManager: ParserManager = ParserManager(
        localParser = localParser,
        serverParser = serverParser,
        settings = settingsRepository,
        douyinSession = douyinWebSession,
        webParserProvider = { webViewParser }, // 惰性：仅 douyin Cookie 失败时创建
    )

    // Phase 3.6：下载仓库（Room 持久化 + CDN 失效自动重解析）。依赖 parserManager 需在其后。
    val downloadRepository: DownloadRepository = DownloadRepository(
        context = appContext,
        dao = KuaixiaDatabase.get(appContext).downloadTaskDao(),
        parserManager = parserManager,
    )

    // Phase 5：WebView 嗅探解析（仅调试页使用，暂不接入 ParserManager 默认流程）
    val webViewParser: WebViewParser by lazy { WebViewParser(appContext) }
}
