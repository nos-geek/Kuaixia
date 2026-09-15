package com.kuaixia.app.data.parser

/**
 * 解析 WebView 的本地受信探针脚本（纯 Kotlin 常量，**零 Android 依赖**，可 JVM 单测）。
 *
 * 从 [WebViewParser] 内联常量抽出，目的：
 * 1. 让「timer/interval 生命周期」可被真实 JVM 单测断言（见 `WebViewProbeScriptTest`）；
 * 2. 明确 PROBE_JS 与 PROBE_STOP_JS 的成对关系（注入的 interval 必须有对应的停止入口）。
 *
 * 纪律：脚本只做**只读扫描 + 静音**，不注入远程代码、不替换 DOM 节点、不点击、不主动 play()。
 */
internal object WebViewProbeScript {

    /**
     * JS 桥名。PROBE_JS 内以字面量 `window.AndroidKuaixiaBridge` 引用它（JS 文本无法引用 Kotlin 常量），
     * 因此本常量与脚本内字面量必须一致 —— `WebViewProbeScriptTest` 用本常量做断言，
     * 一旦改名而脚本未同步，测试立即失败（防止 `removeJavascriptInterface` 摘不掉桥）。
     */
    const val BRIDGE_NAME = "AndroidKuaixiaBridge"

    /** 停止探针并清除其创建的两个 interval（幂等；无探针时返回 no_probe）。 */
    val PROBE_STOP_JS: String = """
        (function () {
            try {
                var f = window.__kxStopProbe;
                if (typeof f === 'function') { f(); return 'stopped'; }
                return 'no_probe';
            } catch (e) { return 'err'; }
        })();
    """.trimIndent()

    /** 本地受信探针 JS：只做只读扫描（+静音），不注入远程代码、不自动执行第三方脚本。 */
    val PROBE_JS: String = """
            (function () {
                if (window.__kuaixiaProbeInstalled__) return;
                window.__kuaixiaProbeInstalled__ = true;
                var seen = {};
                var imgStateSeen = {};
                var firstScan = true;
                function toAbs(u) { try { return new URL(u, location.href).href; } catch (e) { return null; } }
                function report(u) {
                    if (!u) return;
                    var a = toAbs(u);
                    if (a && !seen[a]) { seen[a] = 1; try { window.AndroidKuaixiaBridge.onMedia(a); } catch (e) {} }
                }
                function scan() {
                    try {
                        var els = document.querySelectorAll('video,audio');
                        for (var i = 0; i < els.length; i++) {
                            var v = els[i];
                            // 解析 WebView 绝不外放声音：只写 muted 属性，不改 DOM 结构、
                            // 不替换 src、不阻止媒体加载（因此不影响媒体请求捕获）。
                            try { if (v.muted !== true) v.muted = true; } catch (e) {}
                            if (v.currentSrc) report(v.currentSrc);
                            if (v.src && v.src.indexOf('blob:') !== 0 && v.src.indexOf('data:') !== 0) report(v.src);
                            var ss = v.querySelectorAll('source');
                            for (var j = 0; j < ss.length; j++) if (ss[j].src) report(ss[j].src);
                        }
                    } catch (e) {}
                    try {
                        if (window.performance && performance.getEntriesByType) {
                            var rs = performance.getEntriesByType('resource');
                            for (var k = 0; k < rs.length; k++) {
                                var r = rs[k];
                                if (r && (r.initiatorType === 'video' || r.initiatorType === 'audio')) report(r.name);
                            }
                        }
                    } catch (e) {}
                    // 只读图片补充源：元素级归组（src/currentSrc/srcset/picture>source/data-*）
                    // 整组一次上报，由原生侧组内选优；不改 DOM、不点击、不加载新资源。
                    try {
                        var imgs = document.querySelectorAll('img');
                        var candTotal = 0;
                        var changed = 0;
                        var stateOut = [];
                        var dk = ['data-src', 'data-original', 'data-url', 'data-lazy-src'];
                        for (var i = 0; i < imgs.length; i++) {
                            var im = imgs[i];
                            var srcAttr = im.getAttribute('src') || '';
                            var cur = im.currentSrc || '';
                            var srcsetAttr = im.getAttribute('srcset') || '';
                            var pic = im.parentNode;
                            if (pic && pic.tagName === 'PICTURE') {
                                var psrcs = pic.querySelectorAll('source');
                                for (var pi = 0; pi < psrcs.length; pi++) {
                                    var ps = psrcs[pi].getAttribute('srcset');
                                    if (ps) srcsetAttr = srcsetAttr ? srcsetAttr + ', ' + ps : ps;
                                }
                            }
                            var data = [];
                            for (var di = 0; di < dk.length; di++) {
                                var dv = im.getAttribute(dk[di]);
                                if (dv && dv !== srcAttr && dv !== cur) data.push(dv);
                            }
                            if (!srcAttr && !cur && !srcsetAttr && data.length === 0) continue;
                            candTotal++;
                            // 诊断（只读）：该 img 的加载状态。complete/naturalWidth/naturalHeight 只读，
                            // 不改 DOM、不替换 src、不触发重新加载；仅在状态变化时上报（去重）。
                            try {
                                var pool = [];
                                if (cur) pool.push(['cur', cur]);
                                if (srcAttr && srcAttr !== cur) pool.push(['src', srcAttr]);
                                for (var pi2 = 0; pi2 < pool.length && stateOut.length < 40; pi2++) {
                                    var au = toAbs(pool[pi2][1]);
                                    if (!au || au.indexOf('http') !== 0) continue;
                                    var stSig = (im.complete ? 1 : 0) + '|' + im.naturalWidth + '|' + im.naturalHeight;
                                    var stKey = pool[pi2][0] + au;
                                    if (imgStateSeen[stKey] === stSig) continue;
                                    imgStateSeen[stKey] = stSig;
                                    stateOut.push(pool[pi2][0] + '\u001F' + i + '\u001F' + au + '\u001F'
                                        + (im.complete ? 1 : 0) + '\u001F' + im.naturalWidth + '\u001F' + im.naturalHeight);
                                }
                            } catch (e) {}
                            var sig = srcAttr + '\u001F' + cur + '\u001F' + srcsetAttr + '\u001F' + data.join('\u001F');
                            if (im.__kxImgSig === sig) continue;
                            im.__kxImgSig = sig;
                            changed++;
                            try {
                                window.AndroidKuaixiaBridge.onImageGroup(
                                    String(srcAttr), String(cur), String(srcsetAttr),
                                    data.join('\u001F'), String(location.href));
                            } catch (e) {}
                        }
                        if (stateOut.length > 0) {
                            try { window.AndroidKuaixiaBridge.onImgStates(stateOut.join('\u001E')); } catch (e) {}
                        }
                        if (firstScan || changed > 0) {
                            firstScan = false;
                            try { window.AndroidKuaixiaBridge.onDomProbe(imgs.length, candTotal, changed); } catch (e) {}
                        }
                    } catch (e) {}
                }
                scan();   // 注入后立即首扫一次，不等首个 800ms interval
                var __t1 = setInterval(scan, 800);
                var __t2 = setInterval(function () {
                    try { if (document.title) window.AndroidKuaixiaBridge.onTitle(String(document.title)); } catch (e) {}
                }, 1500);
                // Debug/实验（单变量 A/B）：幂等停止本探针创建的 interval；重复调用/未定义时静默返回。
                // 仅供 PAGE_JSON 读取前释放 renderer 周期任务对照实验，不改 DOM、不影响业务数据。
                window.__kxStopProbe = function () {
                    if (__t1) { clearInterval(__t1); __t1 = 0; }
                    if (__t2) { clearInterval(__t2); __t2 = 0; }
                    return true;
                };
            })();
    """.trimIndent()
}
