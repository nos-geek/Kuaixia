package com.kuaixia.app.ui.debug

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.R
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.model.UiText
import com.kuaixia.app.data.ytdlp.FileStatus
import com.kuaixia.app.data.ytdlp.YtDlpEngine
import com.kuaixia.app.data.ytdlp.YtDlpStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class YtDlpDebugUiState(
    val running: Boolean = false,
)

class YtDlpDebugViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: YtDlpEngine =
        (application as KuaixiaApp).container.ytDlpEngine

    val status: StateFlow<YtDlpStatus> = engine.status

    private val _uiState = MutableStateFlow(YtDlpDebugUiState())
    val uiState: StateFlow<YtDlpDebugUiState> = _uiState.asStateFlow()

    /** 当前环境文件状态（同步读取）。 */
    fun fileStatus(): List<FileStatus> = engine.fileStatus()

    /** 网络环境（代理 / 环境变量）；返回可本地化文本（UI 层翻译）。 */
    fun networkEnvReport(): List<UiText> {
        val app = getApplication<Application>()
        val lines = mutableListOf<UiText>()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val proxy = cm.getDefaultProxy()
        val proxyText = if (proxy != null) {
            "${proxy.host}:${proxy.port}"
        } else {
            app.getString(R.string.debug_net_direct)
        }
        lines.add(UiText.Res(R.string.debug_net_android_proxy, listOf(proxyText)))
        val notSet = app.getString(R.string.debug_net_not_set)
        listOf("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy")
            .forEach { k ->
                val value = System.getenv(k) ?: notSet
                lines.add(UiText.Res(R.string.debug_net_env_var, listOf(k, value)))
            }
        val none = app.getString(R.string.debug_net_none)
        lines.add(
            UiText.Res(
                R.string.debug_net_jvm_http_proxy_host,
                listOf(System.getProperty("http.proxyHost") ?: none),
            ),
        )
        lines.add(
            UiText.Res(
                R.string.debug_net_jvm_https_proxy_host,
                listOf(System.getProperty("https.proxyHost") ?: none),
            ),
        )
        lines.add(UiText.Res(R.string.debug_net_hint))
        return lines
    }

    fun reinitialize() = runJob { engine.initialize() }

    private fun runJob(block: suspend () -> Unit) {
        _uiState.update { it.copy(running = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                AppLogger.e("debug test error", e, "YtDlp")
            }
            _uiState.update { it.copy(running = false) }
        }
    }
}
