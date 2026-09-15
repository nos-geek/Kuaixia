package com.kuaixia.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuaixia.app.BuildConfig
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.R
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.model.AppLanguage
import com.kuaixia.app.core.model.ParseMode
import com.kuaixia.app.core.model.ParserServerConfig
import com.kuaixia.app.core.model.ThemeMode
import com.kuaixia.app.core.model.UiText
import com.kuaixia.app.core.util.LocaleHelper
import com.kuaixia.app.data.ParserRepository
import com.kuaixia.app.data.SettingsRepository
import com.kuaixia.app.data.ytdlp.YtDlpEngine
import com.kuaixia.app.data.ytdlp.YtDlpStatus
import com.kuaixia.app.data.web.DouyinWebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ServerSettingsUiState(
    val servers: List<ParserServerConfig> = emptyList(),
    val defaultServerId: String? = null,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val language: AppLanguage = AppLanguage.ZH,
    val parseMode: ParseMode = ParseMode.LOCAL_FIRST,
    val ytDlpVersion: String? = null,
    /** 「详细日志」开关（DEBUG 级别）。null=未设置，默认 debug 构建开启。 */
    val debugLoggingEnabled: Boolean = false,
    /** 「自动解析剪贴板链接」（默认关）。 */
    val clipboardAutoParse: Boolean = false,
    /** 抖音 WebView 会话是否已建立（Phase 6）。 */
    val douyinSessionEstablished: Boolean = false,
)

/** combine 链中间累加器（每步只合并两个流，规避多流 typed combine 的推断问题）。 */
private data class Acc(
    val servers: List<ParserServerConfig> = emptyList(),
    val defaultServerId: String? = null,
    val theme: ThemeMode = ThemeMode.LIGHT,
    val language: AppLanguage = AppLanguage.ZH,
    val parse: ParseMode = ParseMode.LOCAL_FIRST,
    val ytDlp: YtDlpStatus? = null,
    val debugLogging: Boolean? = null,
    val clipboard: Boolean = false,
    val douyin: Boolean = false,
)

/** 服务器设置与外观设置。Activity 级共享，供设置/服务器列表/添加服务器页面复用。 */
class ServerSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository: SettingsRepository =
        (application as KuaixiaApp).container.settingsRepository
    private val parserRepository: ParserRepository =
        (application as KuaixiaApp).container.parserRepository
    private val ytDlpEngine: YtDlpEngine =
        (application as KuaixiaApp).container.ytDlpEngine
    private val douyinWebSession: DouyinWebSession =
        (application as KuaixiaApp).container.douyinWebSession

    private val _uiState = MutableStateFlow(ServerSettingsUiState())
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.serverSettings
                .combine(settingsRepository.themeMode) { s, theme ->
                    Acc(servers = s.servers, defaultServerId = s.defaultServerId, theme = theme)
                }
                .combine(settingsRepository.parseMode) { a, p -> a.copy(parse = p) }
                .combine(ytDlpEngine.status) { a, y -> a.copy(ytDlp = y) }
                .combine(settingsRepository.debugLogging) { a, d -> a.copy(debugLogging = d) }
                .combine(settingsRepository.clipboardAutoParse) { a, c -> a.copy(clipboard = c) }
                .combine(settingsRepository.language) { a, l -> a.copy(language = l) }
                .combine(douyinWebSession.hasSession) { a, dy -> a.copy(douyin = dy) }
                .collect { a ->
                    _uiState.value = ServerSettingsUiState(
                        servers = a.servers,
                        defaultServerId = a.defaultServerId,
                        themeMode = a.theme,
                        language = a.language,
                        parseMode = a.parse,
                        ytDlpVersion = a.ytDlp?.version,
                        // null（未设置）= 跟随构建：debug 构建默认开详细日志
                        debugLoggingEnabled = a.debugLogging ?: BuildConfig.DEBUG,
                        clipboardAutoParse = a.clipboard,
                        douyinSessionEstablished = a.douyin,
                    )
                }
        }
        // 设置页可见时保持会话状态新鲜
        viewModelScope.launch(Dispatchers.IO) { douyinWebSession.refreshAndCount() }
    }

    /** 新增或更新服务器（按 id 判断）。 */
    fun saveServer(config: ParserServerConfig) {
        viewModelScope.launch {
            val exists = _uiState.value.servers.any { it.id == config.id }
            if (exists) settingsRepository.updateServer(config)
            else settingsRepository.addServer(config)
        }
    }

    fun deleteServer(id: String) {
        viewModelScope.launch { settingsRepository.deleteServer(id) }
    }

    fun setServerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setServerEnabled(id, enabled) }
    }

    fun setDefaultServer(id: String) {
        viewModelScope.launch { settingsRepository.setDefaultServer(id) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setLanguage(lang: AppLanguage) {
        LocaleHelper.set(lang)
        viewModelScope.launch { settingsRepository.setLanguage(lang) }
    }

    fun setParseMode(mode: ParseMode) {
        viewModelScope.launch { settingsRepository.setParseMode(mode) }
    }

    /** 切换「详细日志」并立即应用到日志仓库。 */
    fun setDebugLogging(enabled: Boolean) {
        AppLogRepository.setDebugLogging(enabled)
        viewModelScope.launch { settingsRepository.setDebugLogging(enabled) }
    }

    fun setClipboardAutoParse(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setClipboardAutoParse(enabled) }
    }

    /** 清除抖音 WebView 登录会话（只清快夏自己的 WebView Cookie）。 */
    fun clearDouyinSession() {
        viewModelScope.launch(Dispatchers.IO) { douyinWebSession.clear() }
    }

    /** 测试连接。返回 (是否成功, 提示文案)；文案由 UI 层翻译。 */
    suspend fun testConnection(baseUrl: String): Pair<Boolean, UiText> =
        parserRepository.testConnection(baseUrl).fold(
            onSuccess = { true to UiText.Res(R.string.server_connection_ok) },
            onFailure = { false to UiText.Res(R.string.server_connection_failed) },
        )
}
