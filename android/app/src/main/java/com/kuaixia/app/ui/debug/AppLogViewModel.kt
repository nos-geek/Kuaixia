package com.kuaixia.app.ui.debug

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.core.log.AppLog
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.CrashLogStore
import com.kuaixia.app.core.log.LogExport
import com.kuaixia.app.core.model.ParseMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 调试日志页 ViewModel（日志 V2）：日志流 + 设备信息 + 上次崩溃 + 范围导出（TXT/ZIP 经系统 SAF）。
 */
class AppLogViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KuaixiaApp

    val logs: StateFlow<List<AppLog>> = AppLogRepository.logs

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    /** 导出范围（默认最近 24 小时）。 */
    private val _exportRange = MutableStateFlow(LogExport.Range.LAST_24H)
    val exportRange: StateFlow<LogExport.Range> = _exportRange.asStateFlow()

    private var cachedParseMode: ParseMode = ParseMode.LOCAL_FIRST

    init {
        reloadCrash()
        refresh()
    }

    /** 刷新：重读「上次崩溃」与设备信息（日志流本身实时）。 */
    fun refresh() {
        reloadCrash()
        viewModelScope.launch(Dispatchers.IO) {
            val parseMode = app.container.settingsRepository.parseMode.first()
            cachedParseMode = parseMode
            val ytdlpVersion = app.container.ytDlpEngine.status.value.version
            _deviceInfo.value = DeviceInfoProvider.collect(app, ytdlpVersion, parseMode)
        }
    }

    private fun reloadCrash() {
        _lastCrash.value = CrashLogStore.read()
    }

    fun clearLogs() = AppLogRepository.clear()

    fun setExportRange(range: LogExport.Range) {
        _exportRange.value = range
    }

    /** 当前范围起始时间戳（null=全部）。 */
    fun rangeSinceMs(): Long? =
        LogExport.sinceMsOf(System.currentTimeMillis(), _exportRange.value)

    /** 文本导出内容（当前范围）。 */
    fun exportText(): String = AppLogRepository.exportText(rangeSinceMs())

    /** 旧路径：导出到 App 专属 Downloads（零权限，保留兼容）。 */
    fun exportToFile(): File? = AppLogRepository.exportToFile(app, rangeSinceMs())

    /** 默认文件名（SAF 预填）。 */
    fun textFileName(): String = LogExport.textFileName(System.currentTimeMillis())

    fun zipFileName(): String = LogExport.zipFileName(System.currentTimeMillis())

    /** SAF：写出文本日志到用户选择的位置。 */
    fun writeTextTo(uri: Uri): Boolean = runCatching {
        app.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(exportText().toByteArray(Charsets.UTF_8))
        } != null
    }.getOrDefault(false)

    /** SAF：写出诊断 ZIP（kuaixia.log + device.txt + README.txt）到用户选择的位置。 */
    fun writeZipTo(uri: Uri): Boolean = runCatching {
        app.contentResolver.openOutputStream(uri)?.use { os ->
            AppLogRepository.exportZipTo(os, deviceText(), rangeSinceMs())
        } ?: false
    }.getOrDefault(false)

    /** 设备摘要文本（device.txt 内容）。 */
    fun deviceText(): String {
        val info = _deviceInfo.value
            ?: DeviceInfoProvider.collect(app, null, cachedParseMode)
        return AppLogRepository.deviceTextOf(DeviceInfoProvider.summaryFields(info))
    }

    fun clearCrash() {
        CrashLogStore.clear()
        _lastCrash.value = null
    }
}
