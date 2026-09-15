package com.kuaixia.app.data.parser

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 抖音 `/share/slides/` slidesinfo 接口的只读获取（业务侧，与 Debug 取证页独立）。
 *
 * 关键（真机证据，2026-09-12）：必须**复用页面自身请求头**（User-Agent / Referer / Accept /
 * sec-ch-ua* / Agw-Js-Conv 等）才能拿到真实响应体；只带 UA+Cookie → `200 + application/json + len=0`。
 * 因此调用方传入页面发起该请求时带的请求头（`WebViewClient.shouldInterceptRequest` 只读镜像），
 * 本类逐条回放，跳过 host / content-length / connection / accept-encoding 等受保护/长度类头。
 *
 * 纪律：不写死 Cookie / 签名 / 临时 token；Cookie 由 [CookieManager] 会话取得。
 * 任何失败/空体一律返回 null，由调用方回退 NETWORK/DOM（绝不因本接口失败而中断解析）。
 */
object SlidesInfoFetcher {

    suspend fun fetch(url: String, pageHeaders: Map<String, String>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 9_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", DEFAULT_UA)
                setRequestProperty("Accept", "application/json, text/plain, */*")
                runCatching { setRequestProperty("Referer", "https://www.iesdouyin.com/") }
                // 复用页面自身请求头（权威，覆盖默认值）；跳过受保护/长度类头
                pageHeaders.forEach { (k, v) ->
                    val lk = k.lowercase(Locale.ROOT)
                    if (lk == "host" || lk == "content-length" || lk == "connection" ||
                        lk == "accept-encoding"
                    ) {
                        return@forEach
                    }
                    runCatching { setRequestProperty(k, v) }
                }
                runCatching {
                    CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                }
            }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            }.getOrNull()
            if (code !in 200..299 || body == null || body.isEmpty()) null
            else String(body, Charsets.UTF_8)
        }.getOrNull()
    }

    private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/119.0.0.0 Mobile Safari/537.36"
}
