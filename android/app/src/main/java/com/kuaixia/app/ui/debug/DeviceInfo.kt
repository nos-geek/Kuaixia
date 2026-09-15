package com.kuaixia.app.ui.debug

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.kuaixia.app.BuildConfig
import com.kuaixia.app.R
import com.kuaixia.app.core.model.ParseMode
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.log.DeviceSummary
import com.kuaixia.app.core.log.LogTags

/**
 * 全局调试信息（日志页顶部展示 + 导出 device.txt）。
 *
 * 只采集系统真实可获取、无隐私风险的字段（无 IMEI/IMSI/AndroidID/账号/位置等）。
 */
data class DeviceInfo(
    val appVersion: String,
    val androidVersion: String,
    val deviceModel: String,
    val cpuAbi: String,
    val ytdlpVersion: String?,
    val ffmpegVersion: String?,
    val parseMode: String,
    val downloaderVersion: String,
    /** 以下字段日志 V2 新增（导出摘要用；UI 可选展示）。 */
    val sdk: Int = Build.VERSION.SDK_INT,
    val webViewVersion: String? = null,
    val screen: String? = null,
    val memory: String? = null,
)

object DeviceInfoProvider {

    fun collect(context: Context, ytdlpVersion: String?, parseMode: ParseMode): DeviceInfo {
        val ffmpegVersion = runCatching { FFmpegKitConfig.getVersion() }
            .onFailure { AppLogger.w("读取 FFmpeg 版本失败 err=${it.message}", LogTags.FFMPEG) }
            .getOrNull()
        return DeviceInfo(
            appVersion = context.getString(
                R.string.debug_device_app_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            androidVersion = context.getString(
                R.string.debug_device_android_version,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
            ),
            deviceModel = context.getString(
                R.string.debug_device_model,
                Build.MANUFACTURER,
                Build.MODEL,
            ),
            cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: context.getString(R.string.debug_unknown),
            ytdlpVersion = ytdlpVersion,
            ffmpegVersion = ffmpegVersion,
            parseMode = context.getString(parseMode.labelRes),
            downloaderVersion = context.getString(R.string.debug_device_downloader),
            sdk = Build.VERSION.SDK_INT,
            webViewVersion = webViewVersion(),
            screen = screenOf(context),
            memory = memoryOf(context),
        )
    }

    /** WebView/Chromium 版本（API 26+ 可靠获取；取不到返回 null → 摘要显示 unknown）。 */
    private fun webViewVersion(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 26) {
            WebView.getCurrentWebViewPackage()?.versionName
        } else {
            null
        }
    }.getOrNull()

    private fun screenOf(context: Context): String? = runCatching {
        val dm = context.resources.displayMetrics
        context.getString(R.string.debug_device_screen, dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }.getOrNull()

    private fun memoryOf(context: Context): String? = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        context.getString(R.string.debug_device_memory, (mi.totalMem / 1024 / 1024).toInt())
    }.getOrNull()

    /** 组装导出用设备摘要字段（键名固定，缺失项由 DeviceSummary 输出 unknown）。 */
    fun summaryFields(info: DeviceInfo): List<DeviceSummary.Field> = listOf(
        DeviceSummary.Field("appVersion", info.appVersion),
        DeviceSummary.Field("androidVersion", info.androidVersion),
        DeviceSummary.Field("sdk", info.sdk.toString()),
        DeviceSummary.Field("deviceModel", info.deviceModel),
        DeviceSummary.Field("cpuAbi", info.cpuAbi),
        DeviceSummary.Field("webViewVersion", info.webViewVersion),
        DeviceSummary.Field("screen", info.screen),
        DeviceSummary.Field("memory", info.memory),
        DeviceSummary.Field("ytdlpVersion", info.ytdlpVersion),
        DeviceSummary.Field("ffmpegVersion", info.ffmpegVersion),
        DeviceSummary.Field("parseMode", info.parseMode),
    )
}
