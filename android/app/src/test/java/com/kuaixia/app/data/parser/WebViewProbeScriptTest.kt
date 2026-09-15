package com.kuaixia.app.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-002（静音）/ P2-006（timer 生命周期）—— 对探针脚本做**真实文本断言**。
 *
 * 为什么可以对常量做断言：`WebViewProbeScript` 是纯 Kotlin `internal object`，零 Android 依赖，
 * 因此这些断言运行在真实 JVM 上，校验的是**真正会被注入 WebView 的那段脚本**，
 * 而不是"假测试"。
 *
 * 覆盖用户要求的第 6 项：timer/interval teardown。
 */
class WebViewProbeScriptTest {

    private val probe = WebViewProbeScript.PROBE_JS
    private val stop = WebViewProbeScript.PROBE_STOP_JS

    private fun countOf(src: String, needle: String): Int {
        var from = 0
        var n = 0
        while (true) {
            val i = src.indexOf(needle, from)
            if (i < 0) break
            n++
            from = i + needle.length
        }
        return n
    }

    // ============ 6a. 注入的 interval 数量 == 可清除的 interval 数量（成对保证） ============

    @Test
    fun `6a 注入两个 interval 且 stop 脚本恰好清除两个（不会越积越多）`() {
        val created = countOf(probe, "setInterval(")
        val cleared = countOf(probe, "clearInterval(")
        assertEquals("探针应只创建 2 个 interval（scan 800ms / title 1500ms）", 2, created)
        assertEquals("每个 interval 都必须有对应的 clearInterval", created, cleared)
    }

    @Test
    fun `6b scan 与 title 两个 interval 的句柄都被保存且被清除`() {
        // 句柄必须保存在函数作用域变量里，__kxStopProbe 才能闭包捕获
        assertTrue(probe.contains("var __t1 = setInterval(scan, 800)"))
        assertTrue(probe.contains("var __t2 = setInterval("))
        assertTrue(probe.contains("clearInterval(__t1)"))
        assertTrue(probe.contains("clearInterval(__t2)"))
    }

    @Test
    fun `6c stop 脚本通过 window 句柄调用 __kxStopProbe（teardown 可触发）`() {
        assertTrue("stop 脚本必须调用 window.__kxStopProbe", stop.contains("window.__kxStopProbe"))
        assertTrue("stop 脚本必须真的执行该函数", stop.contains("f()"))
        assertTrue("探针必须导出 __kxStopProbe", probe.contains("window.__kxStopProbe = function"))
    }

    @Test
    fun `6d stop 脚本幂等 无探针时静默返回 no_probe（重复 teardown 不报错）`() {
        assertTrue("无探针时必须安全返回 no_probe", stop.contains("no_probe"))
        assertTrue("必须用 typeof 判定，避免二次 clearInterval 抛错", stop.contains("typeof f === 'function'"))
        assertTrue("整体必须包 try-catch（幂等 / 异常安全）", stop.contains("catch (e)"))
    }

    @Test
    fun `6e 探针安装是幂等的（不重复注入第二个探针集合）`() {
        assertTrue(
            "必须用 __kuaixiaProbeInstalled__ 防止重复注入",
            probe.contains("window.__kuaixiaProbeInstalled__"),
        )
        assertTrue(probe.contains("if (window.__kuaixiaProbeInstalled__) return;"))
    }

    // ================== P2-002：脚本必须静音，且绝不主动播放 ==================

    @Test
    fun `p2a 探针会把页面 video_audio 元素静音（第二层保险）`() {
        assertTrue("必须写入 muted 属性实现静音", probe.contains("v.muted = true"))
        assertTrue("静音必须 try-catch 包裹，绝不因页面异常中断扫描", probe.contains("try { if (v.muted !== true)"))
    }

    @Test
    fun `p2b 探针绝不主动调用 play（必须由 WebSettings 阻止自动播放）`() {
        assertFalse("脚本不得调用 play()：静音由 WebSettings + muted 负责", probe.contains(".play("))
        assertFalse("stop 脚本同样不得播放", stop.contains(".play("))
    }

    @Test
    fun `p2c 探针只读 DOM 不改写媒体 URL`() {
        // 只允许读属性，禁止写 src / 替换节点
        assertFalse("不得写 video,audio 的 src", probe.contains("v.src ="))
        assertFalse("不得写 source 的 src", probe.contains("ss[j].src ="))
        assertFalse("不得替换 img.src", probe.contains("im.src ="))
        assertTrue("只允许读取 getAttribute", probe.contains("im.getAttribute("))
    }

    // ================== 探针身份 / 桥名一致性（teardown 才能摘掉桥） ==================

    @Test
    fun `bridge 名称与探针脚本内引用的名字一致`() {
        // 关键：用 WebViewProbeScript.BRIDGE_NAME（而不是硬编码字面量）做断言 ——
        // 一旦改名而脚本内字面量未同步，本测试立即失败（这正是 removeJavascriptInterface
        // 摘不掉桥的那种隐性 bug）。
        val bridgeName = WebViewProbeScript.BRIDGE_NAME
        assertTrue("探针必须使用与该桥名一致的全局对象", probe.contains("window.$bridgeName."))
        assertTrue(probe.contains("window.$bridgeName.onMedia("))
        assertTrue(probe.contains("window.$bridgeName.onImageGroup("))
        assertTrue(probe.contains("window.$bridgeName.onImgStates("))
        assertTrue(probe.contains("window.$bridgeName.onTitle("))
        assertTrue(probe.contains("window.$bridgeName.onDomProbe("))
    }
}
