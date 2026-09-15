package com.kuaixia.app.ui.douyin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.R
import com.kuaixia.app.core.model.UiText
import com.kuaixia.app.data.web.DouyinWebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DouyinLoginUiState(
    /** 页面是否加载中。 */
    val pageLoading: Boolean = false,
    /** 页面标题（WebView 回报）。 */
    val pageTitle: String? = null,
    /** 「我已登录」确认失败时的提示（Cookie 为空）。 */
    val errorMessage: UiText? = null,
    /** 已确认登录成功（Screen 收到后返回上一页）。 */
    val confirmed: Boolean = false,
)

/**
 * 抖音网页登录页 ViewModel（Phase 6）。
 *
 * 职责：确认登录后读取 Cookie 并通知全局会话；不持有 Cookie 内容。
 */
class DouyinLoginViewModel(application: Application) : AndroidViewModel(application) {

    private val session: DouyinWebSession =
        (application as KuaixiaApp).container.douyinWebSession

    private val _uiState = MutableStateFlow(DouyinLoginUiState())
    val uiState: StateFlow<DouyinLoginUiState> = _uiState.asStateFlow()

    fun onPageLoading(loading: Boolean) {
        _uiState.update { it.copy(pageLoading = loading) }
    }

    fun onPageTitle(title: String?) {
        _uiState.update { it.copy(pageTitle = title) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * 「我已登录，继续」：读取抖音 Cookie 并刷新会话状态。
     * - Cookie 非空：广播会话建立（携带待重试 URL，供首页自动重试一次）→ confirmed
     * - Cookie 为空：提示用户先完成登录或刷新页面，不广播。
     */
    fun confirmLogin(pendingUrl: String?) {
        _uiState.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { session.refreshAndCount() }
            if (count > 0) {
                session.confirmEstablished(pendingUrl)
                _uiState.update { it.copy(confirmed = true) }
            } else {
                _uiState.update {
                    it.copy(errorMessage = UiText.Res(R.string.douyin_no_cookie_error))
                }
            }
        }
    }
}
