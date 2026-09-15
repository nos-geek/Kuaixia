package com.kuaixia.app.data.parser.douyin

/**
 * 抖音 PC 形态（`www.douyin.com/video/{id}`）高清取数脚本（纯 Kotlin 常量，**零 Android 依赖**，可 JVM 单测）。
 *
 * ## 与移动形态取数的根本差异
 * 移动分享页把精简数据放进 `window._ROUTER_DATA`（现有 `video_picker.js` 读它）；
 * PC 页**没有** `_ROUTER_DATA`，数据来自页面**自身**发起的 `aweme/v1/web/aweme/detail/` XHR。
 * 因此本脚本采用「**只读观察页面已发生的网络响应**」的方式取数。
 *
 * ## 硬性纪律（测试固化，见 `DouyinPcProbeScriptTest`）
 * - **只观察**：包装 fetch/XHR 后仅读响应（`clone().text()` / XHR `load` 事件），
 *   **不修改请求、不重放请求、不构造任何接口调用、不生成/推断签名、不改写 URL**；
 * - **幂等**：重复注入直接返回 `already`；**可停止**：`__kxPcStop()` 还原包装并清空缓存；
 * - **只缓存** `aweme/detail` 响应（截断 2MB），其余请求仅计数；
 * - 只回**紧凑 JSON**（档位/URL 列表/计数），**不回原始对象**；
 * - 不 `console.log`，不涉及任何 Cookie / token。
 */
internal object DouyinPcProbeScript {

    /** 捕获缓存上限（字符）：仅用于内存保护，超出即截断（不足则视为未捕获）。 */
    const val MAX_CAPTURE_CHARS = 2_000_000

    /**
     * 安装只读钩子（在 `onPageStarted` 注入，早于页面脚本执行）。
     * 幂等：已安装返回 `already`；失败不抛异常（返回 `err`）。
     */
    val HOOK_JS: String = """
        (function () {
            try {
                if (window.__kxPcHooked) return 'already';
                window.__kxPcHooked = true;
                window.__kxPcCap = { n: 0, detail: '', detailReq: 0 };
                var origFetch = window.fetch;
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                window.__kxPcOrig = { f: origFetch, o: origOpen, s: origSend };
                function isDetail(u) { return typeof u === 'string' && u.indexOf('aweme/detail') >= 0; }
                function save(u, text) {
                    try {
                        var cap = window.__kxPcCap;
                        cap.n++;
                        if (isDetail(u)) {
                            cap.detailReq++;
                            if (!cap.detail && typeof text === 'string' && text.length > 0) {
                                cap.detail = text.length > $MAX_CAPTURE_CHARS ? text.slice(0, $MAX_CAPTURE_CHARS) : text;
                            }
                        }
                    } catch (e) {}
                }
                if (typeof origFetch === 'function') {
                    window.fetch = function () {
                        var a = arguments[0];
                        var u = (a && a.url) ? a.url : String(a);
                        var p = origFetch.apply(this, arguments);
                        try {
                            p.then(function (r) {
                                try { r.clone().text().then(function (t) { save(u, t); }); } catch (e) {}
                            });
                        } catch (e) {}
                        return p;
                    };
                }
                XMLHttpRequest.prototype.open = function (m, u) {
                    try { this.__kxUrl = u; } catch (e) {}
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function () {
                    var self = this;
                    try {
                        this.addEventListener('load', function () {
                            try {
                                var t = (self.responseType === '' || self.responseType === 'text') ? self.responseText : '';
                                save(self.__kxUrl || '', t);
                            } catch (e) {}
                        });
                    } catch (e) {}
                    return origSend.apply(this, arguments);
                };
                return 'hooked';
            } catch (e) { return 'err'; }
        })();
    """.trimIndent()

    /**
     * 读取捕获结果（轮询调用；幂等；失败带 `reason`）。
     *
     * 返回紧凑 JSON：
     * `{ok, reason, title, cover, videoId, durationMs, captureCount, detailReq,
     *   gears:[{gear,w,h,size,bitrate,urls[]}], play:{w,h,size,urls[]}, download:{w,h,size,urls[]}}`
     *
     * `reason` 取值：`no_hook`（未安装钩子）/ `no_capture`（页面未发起或响应未到）/ `no_video`（响应无 video）/ `parse_error`。
     */
    val EXTRACT_JS: String = """
        (function () {
            var out = { ok: false, reason: 'no_hook', title: '', cover: '', videoId: '', durationMs: 0,
                        captureCount: 0, detailReq: 0, gears: [], play: null, download: null };
            try {
                var cap = window.__kxPcCap;
                if (!cap) return JSON.stringify(out);
                out.captureCount = cap.n || 0;
                out.detailReq = cap.detailReq || 0;
                var d = cap.detail;
                if (!d) { out.reason = 'no_capture'; return JSON.stringify(out); }
                out.reason = 'no_video';
                var j = JSON.parse(d);
                var ad = j && j.aweme_detail;
                if (!ad || !ad.video) return JSON.stringify(out);
                var v = ad.video;
                function pack(addr) {
                    if (!addr || !addr.url_list) return null;
                    var urls = [];
                    for (var i = 0; i < addr.url_list.length && i < 6; i++) {
                        var u = addr.url_list[i];
                        if (typeof u === 'string' && (u.indexOf('http://') === 0 || u.indexOf('https://') === 0)) urls.push(u);
                    }
                    if (urls.length === 0) return null;
                    return { w: addr.width || 0, h: addr.height || 0, size: addr.data_size || 0, urls: urls };
                }
                function firstUrl(addr) {
                    if (!addr || !addr.url_list || addr.url_list.length === 0) return '';
                    var u = addr.url_list[0];
                    return (typeof u === 'string' && u.indexOf('http') === 0) ? u : '';
                }
                out.title = document.title || '';
                out.cover = firstUrl(v.cover) || firstUrl(v.origin_cover) || firstUrl(v.dynamic_cover);
                out.videoId = (v.play_addr && v.play_addr.uri) ? v.play_addr.uri : '';
                out.durationMs = (typeof v.duration === 'number' && isFinite(v.duration)) ? v.duration : 0;
                out.play = pack(v.play_addr);
                out.download = pack(v.download_addr);
                var br = v.bit_rate;
                if (Object.prototype.toString.call(br) === '[object Array]') {
                    for (var k = 0; k < br.length && k < 24; k++) {
                        var b = br[k];
                        if (!b) continue;
                        var pa = pack(b.play_addr);
                        if (!pa) continue;
                        out.gears.push({
                            gear: (typeof b.gear_name === 'string') ? b.gear_name :
                                  ((typeof b.gearName === 'string') ? b.gearName : ''),
                            w: pa.w, h: pa.h, size: pa.size,
                            bitrate: (typeof b.bit_rate === 'number' && isFinite(b.bit_rate)) ? b.bit_rate : 0,
                            urls: pa.urls
                        });
                    }
                }
                out.ok = (out.gears.length > 0) || (out.play !== null);
                if (out.ok) out.reason = '';
            } catch (e) {
                out.ok = false;
                out.reason = 'parse_error';
            }
            return JSON.stringify(out);
        })();
    """.trimIndent()

    /** 停止并还原（teardown 时调用；幂等）。 */
    val STOP_JS: String = """
        (function () {
            try {
                var cap = window.__kxPcCap;
                if (cap) { cap.detail = ''; cap.n = 0; cap.detailReq = 0; }
                var o = window.__kxPcOrig;
                if (o) {
                    if (typeof o.f === 'function') window.fetch = o.f;
                    if (typeof o.o === 'function') XMLHttpRequest.prototype.open = o.o;
                    if (typeof o.s === 'function') XMLHttpRequest.prototype.send = o.s;
                }
                window.__kxPcHooked = false;
                return 'stopped';
            } catch (e) { return 'err'; }
        })();
    """.trimIndent()
}
