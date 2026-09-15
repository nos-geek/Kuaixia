package com.kuaixia.app.data.parser.douyin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抖音 PC 取数脚本的**契约单测**（纯 JVM）。
 *
 * 目的：把「只读观察 / 不实现签名 / 不自建请求 / 幂等 / 可停止 / 有界缓存」这些硬性纪律
 * 固化成可执行断言 —— 一旦有人往脚本里加入签名实现或自建接口调用，测试立即失败。
 */
class DouyinPcProbeScriptTest {

    private val all = listOf(
        "HOOK_JS" to DouyinPcProbeScript.HOOK_JS,
        "EXTRACT_JS" to DouyinPcProbeScript.EXTRACT_JS,
        "STOP_JS" to DouyinPcProbeScript.STOP_JS,
    )

    @Test
    fun scriptsContainNoSignatureImplementation() {
        // 禁止：任何签名/风控参数的计算或推断
        val forbidden = listOf("a_bogus", "A_BOGUS", "X-Bogus", "x-bogus", "msToken=", "sign(", "byted_acrawler", "mssdk")
        all.forEach { (name, js) ->
            forbidden.forEach { token ->
                assertFalse("$name 不得包含签名相关实现：$token", js.contains(token))
            }
        }
    }

    @Test
    fun scriptsDoNotBuildOwnRequests() {
        // 禁止：自建接口调用（不得出现具体接口路径或 aweme_id 参数拼接）
        val forbidden = listOf("aweme_id", "douyin.com", "/aweme/v1/", "XMLHttpRequest()", "new Request(")
        all.forEach { (name, js) ->
            forbidden.forEach { token ->
                assertFalse("$name 不得自建请求：$token", js.contains(token))
            }
        }
    }

    @Test
    fun hookIsReadOnlyPassThrough() {
        // 只读：fetch 用 clone() 读响应；XHR 用 load 事件读 responseText；两者都**透传**原调用
        val hook = DouyinPcProbeScript.HOOK_JS
        assertTrue(hook.contains("origFetch.apply(this, arguments)"))
        assertTrue(hook.contains("origSend.apply(this, arguments)"))
        assertTrue(hook.contains("clone()"))
        assertTrue(hook.contains("addEventListener('load'"))
        assertFalse("不得改写/替换 URL（无重写调用）", Regex("url\\s*=|replace\\(").containsMatchIn(hook))
    }

    @Test
    fun hookIsIdempotentAndStoppable() {
        val hook = DouyinPcProbeScript.HOOK_JS
        assertTrue("必须幂等", hook.contains("__kxPcHooked") && hook.contains("'already'"))
        assertTrue(hook.contains("__kxPcCap"))
        val stop = DouyinPcProbeScript.STOP_JS
        assertTrue("停止脚本必须还原原实现", stop.contains("__kxPcOrig") && stop.contains("__kxPcHooked = false"))
        assertTrue("停止脚本必须清理缓存", stop.contains("cap.detail = ''"))
    }

    @Test
    fun captureBufferIsBounded() {
        // 内存保护：捕获上限（字符）写入脚本，且不得无限增长
        assertTrue(
            "捕获上限必须生效",
            DouyinPcProbeScript.HOOK_JS.contains(DouyinPcProbeScript.MAX_CAPTURE_CHARS.toString()),
        )
        assertTrue(DouyinPcProbeScript.MAX_CAPTURE_CHARS in 100_000..10_000_000)
    }

    @Test
    fun extractReportsCompactResultWithReasons() {
        val js = DouyinPcProbeScript.EXTRACT_JS
        listOf("no_hook", "no_capture", "no_video", "parse_error").forEach {
            assertTrue("必须给出失败原因：$it", js.contains(it))
        }
        assertTrue("必须回传档位数组", js.contains("bit_rate") && js.contains("gears"))
        assertTrue("必须回传档位元数据", js.contains("gear_name") && js.contains("bit_rate"))
        assertTrue("必须回传 cover/title（UI 用）", js.contains("cover") && js.contains("title"))
        assertFalse("不得 console 输出（避免日志泄漏）", js.contains("console."))
    }

    @Test
    fun noCookieOrTokenHandlingAnywhere() {
        val forbidden = listOf("document.cookie", "localStorage", "token", "Cookie")
        all.forEach { (name, js) ->
            forbidden.forEach { token ->
                assertFalse("$name 不得触碰 Cookie/token：$token", js.contains(token))
            }
        }
    }
}
