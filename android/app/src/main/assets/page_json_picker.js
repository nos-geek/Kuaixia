/*
 * 从页面运行时 JSON 提取作品图片高清候选（PAGE_JSON 数据源）——v2.1 诊断版。
 *
 * 目标结构（真实样本已证实，key 名变化通过 loaderData 二级对象浅层定向兼容）：
 *   window._ROUTER_DATA → loaderData → note<id> → page.videoInfoRes → item_list[*] → images[*] → url_list[*]
 *
 * 策略：定向浅扫 + 命中即 early exit（不递归整树）；download_url_list 不读取；
 * url_list[*] = 同一张图的镜像/质量档（非多图），全部保留给组内评分。
 *
 * 输出 {"images":[{seq,obj,urls}] , "meta":{router,loader,loaderKeys,vfAny,vfHit,item,img,urls,found}}
 * meta 仅供诊断（区分“数据不存在/结构不匹配/未命中”），业务只读 images。
 */
(function () {
  'use strict';
  var R = window._ROUTER_DATA || window.__ROUTER_DATA__ || window.ROUTER_DATA;
  var empty = '{"images":[],"meta":{}}';
  if (!R) { try { window.__kxPickerResult__ = empty; } catch (e) {} return empty; }

  var meta = {
    router: true,
    loader: !!(R.loaderData && typeof R.loaderData === 'object'),
    loaderKeys: [],
    vfAny: false,   // 扫描路径上是否存在 videoInfoRes
    vfHit: 0,       // 含 item_list 的 videoInfoRes 命中数
    item: 0,
    img: 0,
    urls: 0,
    found: 0
  };
  try {
    var lk = [];
    var lc = 0;
    for (var lk0 in R.loaderData) { if (lc++ < 3) lk.push(lk0); else break; }
    meta.loaderKeys = lk;
  } catch (e) { /* 取证静默 */ }

  function objKeyOf(u) {
    if (!u) return '';
    var clean = String(u).split('?')[0].split('#')[0];
    var sl = clean.indexOf('://');
    var path = '';
    if (sl >= 0) { var slash = clean.indexOf('/', sl + 3); path = slash >= 0 ? clean.slice(slash) : ''; }
    else { path = clean; }
    var ti = path.indexOf('~tplv');
    var seg = path.slice(path.lastIndexOf('/') + 1);
    if (ti >= 0) {
      var pre = seg.slice(0, seg.indexOf('~tplv'));
      var dot = pre.indexOf('.');
      return (dot > 0 ? pre.slice(0, dot) : pre);
    }
    return path.replace(/^\/obj\//, '').slice(0, 64);
  }

  var out = [];
  var budget = 0;
  var MAX_NODES = 10000;
  var MAX_ITEMS = 60;
  var MAX_IMAGES = 60;
  var MAX_URLS = 20;

  function collectFromVideoInfo(vir) {
    if (!vir || !vir.item_list || Object.prototype.toString.call(vir.item_list) !== '[object Array]') return false;
    meta.vfHit++;
    var items = vir.item_list;
    for (var it = 0; it < items.length && it < MAX_ITEMS; it++) {
      if (++budget > MAX_NODES) return true;
      meta.item++;
      var item = items[it];
      if (!item || typeof item !== 'object') continue;
      var imgs = item.images;
      if (!imgs || Object.prototype.toString.call(imgs) !== '[object Array]') continue;
      for (var g = 0; g < imgs.length && g < MAX_IMAGES; g++) {
        if (++budget > MAX_NODES) return true;
        meta.img++;
        var gi = imgs[g];
        if (!gi || typeof gi !== 'object') continue;
        var ul = gi.url_list;
        if (!ul || Object.prototype.toString.call(ul) !== '[object Array]') continue;
        var urls = [];
        for (var uu = 0; uu < ul.length && uu < MAX_URLS; uu++) {
          var s = ul[uu];
          if (typeof s !== 'string') continue;
          if (s.indexOf('http://') !== 0 && s.indexOf('https://') !== 0) continue;
          if (s.indexOf('data:') === 0 || s.indexOf('blob:') === 0) continue;
          var dup = false;
          for (var d = 0; d < urls.length; d++) { if (urls[d] === s) { dup = true; break; } }
          if (!dup) urls.push(s);
        }
        meta.urls += urls.length;
        if (urls.length === 0) continue;
        out.push({ seq: out.length, obj: objKeyOf(urls[0]), urls: urls });
      }
    }
    return out.length > 0;
  }

  function scanLoader(ld) {
    var keys = [];
    var c = 0;
    try { for (var k in ld) { if (c++ >= MAX_ITEMS) break; keys.push(k); } } catch (e) {}
    for (var i = 0; i < keys.length; i++) {
      var v = ld[keys[i]];
      if (!v || typeof v !== 'object') continue;
      try {
        var page = v.page;
        if (page && typeof page === 'object' && page.videoInfoRes) {
          meta.vfAny = true;
          if (collectFromVideoInfo(page.videoInfoRes)) return true;
        }
      } catch (e) { /* 取证静默 */ }
      try {
        if (v.videoInfoRes) {
          meta.vfAny = true;
          if (collectFromVideoInfo(v.videoInfoRes)) return true;
        }
      } catch (e) { /* 取证静默 */ }
      if (out.length > 0) return true;
    }
    return out.length > 0;
  }

  var found = false;
  try { if (meta.loader && R.loaderData) found = scanLoader(R.loaderData); } catch (e) {}
  if (!found && R.page && R.page.videoInfoRes) {
    try { meta.vfAny = true; found = collectFromVideoInfo(R.page.videoInfoRes); } catch (e) {}
  }
  if (!found && R.videoInfoRes) {
    try { meta.vfAny = true; found = collectFromVideoInfo(R.videoInfoRes); } catch (e) {}
  }
  meta.found = out.length;

  try {
    var resultStr = '{"images":' + JSON.stringify(out) + ',"meta":' + JSON.stringify(meta) + '}';
    try { window.__kxPickerResult__ = resultStr; } catch (e) {}
    return resultStr;
  } catch (e) {
    try { window.__kxPickerResult__ = empty; } catch (e2) {}
    return empty;
  }
})();
