package com.kuaixia.app.data.parser

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.kuaixia.app.core.ActivityProvider

/**
 * 解析专用 WebView：**完全不参与触摸派发**。
 *
 * ## 背景（P2-003 根因）
 * 解析 WebView 必须以整屏透明覆盖层 attach 到真实 Activity 窗口，否则 renderer 生命周期不完整、
 * `evaluateJavascript` callback 永不返回（P5/P6 实测结论，见 [HiddenWebViewHost] 类注释）。
 * 但 `alpha = 0f` **只影响绘制、不改变可见性** —— 该 View 仍是 `VISIBLE`，会被
 * `ViewGroup.dispatchTouchEvent` 正常派发，且 WebView 默认消费手势，于是宿主 Compose UI
 * （设置 / 下载 / 取消解析）在解析期间全部点不动。
 *
 * ## 修复
 * 覆盖 [dispatchTouchEvent] 直接返回 false。`ViewGroup.dispatchTouchEvent` 遍历子 View 时，
 * 收到 false 会**继续尝试下一个兄弟 View**，因此同一 `android.R.id.content` 内的 ComposeView
 * 能正常收到触摸事件。
 *
 * ## 为什么不影响解析能力
 * `evaluateJavascript`、`addJavascriptInterface` 桥、页面 JS 定时器、网络请求一律与 touch 无关；
 * 快夏的解析脚本明确**不点击、不模拟手势**（见 [WebViewProbeScript] 纪律），
 * 因此解析 WebView 无需接收任何用户输入。
 *
 * 注：这是比 `isClickable = false` 更**确定**的保证 —— WebView 覆写了 `onTouchEvent`，
 * 仅清 clickable 标记并不足以保证它不消费触摸；直接否决 `dispatchTouchEvent` 才与
 * WebView 内部实现无关。
 */
internal class NonTouchableWebView(context: Context) : WebView(context) {

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = false
}

/**
 * P7 正式架构：统一管理解析 WebView 的宿主 attach / detach。
 *
 * 背景（P5/P6 实验结论）：ApplicationContext 创建的「无 Window 宿主」WebView 的 renderer 生命周期
 * 异常——evaluateJavascript callback 永不返回（PAGE_JSON NO_CALLBACK）；将 WebView attach 到真实
 * RESUMED Activity 窗口（透明覆盖层）后 evaluate 恢复（实测 callback value=2 elapsed=1ms、PAGE_JSON
 * 正常、1440×2560 图集下载成功）。WindowManager 隐藏窗口方案（P5）因 application 无 window token
 * （BadTokenException）废弃。
 *
 * 职责边界：
 * - 本类只做「宿主 attach/detach」；WebViewParser 不再直接接触 Activity / addContentView / WindowManager；
 * - attach 使用透明覆盖层（alpha=0），不显示 UI、不改动用户布局；
 * - 生产保护：无 Activity / Activity finishing·destroyed / WebView 重复 attach / add 异常 均安全处理并返回原因；
 * - detach 幂等（parent 为空即 no-op），可重复调用。
 */
object HiddenWebViewHost {

    /** attach 结果：attached=是否已挂到窗口；activity=宿主类名；reason=失败原因（成功为 null）。 */
    data class Result(
        val attached: Boolean,
        val activity: String?,
        val reason: String?,
    ) {
        fun isSuccess(): Boolean = attached

        /** 日志行：`attach success activity=MainActivity` / `attach failed reason=no_activity`。 */
        fun logLine(): String =
            if (attached) "attach success activity=$activity attached=true"
            else "attach failed reason=${reason ?: "unknown"} attached=false"
    }

    /**
     * 把解析 WebView 挂到当前 RESUMED Activity 的透明覆盖层。
     * @param width / @param height 宿主布局尺寸（沿用解析视口，保持 Chromium 大视口语义）
     */
    @Synchronized
    fun attach(webView: WebView, width: Int, height: Int): Result {
        val activity = ActivityProvider.current()
        if (activity == null) return Result(false, null, "no_activity")
        if (activity.isFinishing || activity.isDestroyed) {
            return Result(false, activity.javaClass.simpleName, "activity_finishing")
        }
        if (webView.parent != null) {
            // 重复 attach 保护：已挂载则视为成功，不再二次 addContentView（避免 child-has-parent 异常）
            return Result(webView.isAttachedToWindow, activity.javaClass.simpleName, "already_attached")
        }
        return runCatching {
            webView.alpha = 0f
            // P2-003：alpha=0 不等于「不可点」。除 NonTouchableWebView 的 dispatchTouchEvent 否决外，
            // 再清除所有「会让 View 在触摸派发链上被当作可交互目标」的标记（双保险，无副作用）。
            webView.isClickable = false
            webView.isLongClickable = false
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            activity.addContentView(webView, FrameLayout.LayoutParams(width, height))
            Result(webView.isAttachedToWindow, activity.javaClass.simpleName, null)
        }.getOrElse { e ->
            Result(
                false,
                activity.javaClass.simpleName,
                "add_failed:${e.message?.take(80) ?: e.javaClass.simpleName}",
            )
        }
    }

    /** 从宿主摘除（幂等：无 parent 时 no-op；可重复调用）。 */
    @Synchronized
    fun detach(webView: WebView?) {
        if (webView == null) return
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
    }
}
