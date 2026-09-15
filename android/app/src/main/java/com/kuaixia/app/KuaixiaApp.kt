package com.kuaixia.app

import android.app.Application
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.CrashHandler
import com.kuaixia.app.core.log.CrashLogStore
import com.kuaixia.app.core.log.DebugContext
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.core.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KuaixiaApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 日志与崩溃捕获必须最先初始化，确保后续任何异常都可追溯
        AppLogRepository.init(this, debugBuild = BuildConfig.DEBUG)
        CrashLogStore.init(this)
        installCrashHandler()
        AppLogger.i("App 启动", LogTags.KUAIXIA)

        container = AppContainer(this)
        // 预读应用语言到内存（MainActivity.attachBaseContext 需同步读取；数据极小，读失败回落默认中文）
        runBlocking {
            runCatching { LocaleHelper.set(container.settingsRepository.language.first()) }
        }
        // P6 diagnostic：注册当前 Activity 弱引用提供者（WebView 宿主实验用；正式业务不使用）
        com.kuaixia.app.core.ActivityProvider.register(this)
        // 应用「详细日志」设置（null=用户未设置时保持 init 默认：debug 构建含 DEBUG，release 仅 INFO+）
        appScope.launch {
            container.settingsRepository.debugLogging.firstOrNull()?.let { enabled ->
                AppLogRepository.setDebugLogging(enabled)
            }
        }
        // 持续采集解析器状态 → DebugContext（崩溃报告需含当前 Parser 状态）
        appScope.launch {
            container.ytDlpEngine.status.collect { st ->
                DebugContext.setParserInfo("yt-dlp v${st.version ?: "?"} state=${st.initState}")
            }
        }
        // 抖音 WebView 会话启动恢复：一次性 WebView 初始化 Cookie 存储后轮询读取，
        // 冷启动即恢复 hasSession（Cookie 是否仍有效由 yt-dlp COOKIE_REQUIRED 决定，不强制用户重新登录）
        appScope.launch {
            runCatching { container.douyinWebSession.warmUpAndRestore() }
                .onFailure { AppLogger.e("douyin session startup restore failed", it, LogTags.DOUYIN) }
        }
        // 后台预热 yt-dlp 初始化（解压 Python 运行时），失败状态由 YtDlpEngine.status 暴露给诊断页
        appScope.launch {
            try {
                container.ytDlpEngine.initialize()
            } catch (e: Exception) {
                AppLogger.e("yt-dlp prewarm failed", e, LogTags.YTDLP)
            }
        }
        // 后台 FFmpeg 最小 smoke test：尽早暴露 native lib / smartexception 依赖加载问题，
        // 避免延迟到用户点下载才失败（DeviceInfo 的 getVersion() 只触达 getVersion，不真正加载 native 库）
        appScope.launch {
            try {
                val session = FFmpegKit.executeWithArguments(arrayOf("-version"))
                val code = session.returnCode.value
                val ver = FFmpegKitConfig.getVersion()
                if (code == 0) {
                    AppLogger.i("FFmpeg smoke test ok version=$ver", LogTags.FFMPEG)
                } else {
                    AppLogger.e("FFmpeg smoke test fail exit=$code", null, LogTags.FFMPEG)
                }
            } catch (e: Throwable) {
                AppLogger.e("FFmpeg smoke test exception（依赖或 native 库加载失败）", e, LogTags.FFMPEG)
            }
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this, defaultHandler))
    }
}
