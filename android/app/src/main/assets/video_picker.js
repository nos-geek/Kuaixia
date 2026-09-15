// P8.1 定向 video 字段提取（仅 loaderData → page.videoInfoRes → item_list[].video，不递归全对象）
// 输出：{items, playUrls, downloadUrls, videoKeys, refs:[{field:'play_addr'|'download_addr', url}]}
// 原则：只读真实存在的字段；playwm 等水印语义由原生侧保守标注，本脚本不做任何判定/改写。
(function () {
  function isHttp(u) {
    return typeof u === 'string' && (u.indexOf('http://') === 0 || u.indexOf('https://') === 0);
  }
  var R = window._ROUTER_DATA || window.__ROUTER_DATA__ || window.ROUTER_DATA;
  var out = { items: 0, playUrls: 0, downloadUrls: 0, videoKeys: [], refs: [] };
  try {
    if (!R || !R.loaderData) return JSON.stringify(out);
    var c = 0;
    for (var k in R.loaderData) {
      if (c++ > 80) break;
      var o = R.loaderData[k];
      if (!o) continue;
      var vf = (o.page && o.page.videoInfoRes) || o.videoInfoRes;
      if (!vf) continue;
      var il = vf.item_list;
      if (!il) continue;
      for (var i = 0; i < il.length && i < 8; i++) {
        var it = il[i];
        if (!it || !it.video) continue;
        out.items++;
        var v = it.video;
        var pk = 0;
        for (var vk in v) {
          if (out.videoKeys.length < 16 && out.videoKeys.indexOf(vk) < 0) out.videoKeys.push(vk);
          pk++;
          if (pk > 60) break;
        }
        var pa = v.play_addr;
        if (pa && Array.isArray(pa.url_list)) {
          for (var p = 0; p < pa.url_list.length && p < 12; p++) {
            var u = pa.url_list[p];
            if (isHttp(u)) { out.playUrls++; out.refs.push({ field: 'play_addr', url: u }); }
          }
        }
        var da = v.download_addr;
        if (da && Array.isArray(da.url_list)) {
          for (var d = 0; d < da.url_list.length && d < 12; d++) {
            var u2 = da.url_list[d];
            if (isHttp(u2)) { out.downloadUrls++; out.refs.push({ field: 'download_addr', url: u2 }); }
          }
        }
      }
    }
  } catch (e) {
    out.refs = [];
  }
  return JSON.stringify(out);
})();
