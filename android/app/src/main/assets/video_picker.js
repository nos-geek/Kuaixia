// P8.1 定向 video 字段提取（仅 loaderData → page.videoInfoRes → item_list[].video，不递归全对象）
// P8.2 增补：video.bit_rate[] 多档清晰度枚举（gear_name / play_addr.width,height / bit_rate / play_addr.url_list）
// 输出：{items, playUrls, downloadUrls, videoKeys, bitRateCount, bitRateKeys,
//        refs:[{field,url,source,gear,width,height,bitrate,dataSize}]}
// 原则：只读真实存在的字段；playwm 等水印语义由原生侧保守标注，本脚本不做任何判定/改写。
//       P8.2 新增键全部为「可选元数据」，旧消费方只读 field/url，行为与 P8.1 完全一致。
(function () {
  function isHttp(u) {
    return typeof u === 'string' && (u.indexOf('http://') === 0 || u.indexOf('https://') === 0);
  }
  // 数值字段严格校验：非有限正数一律 null（不做字符串转数字、不猜测）
  function num(x) {
    return (typeof x === 'number' && isFinite(x) && x > 0) ? x : null;
  }
  var MAX_BITRATE_GEARS = 8;   // bit_rate 档位枚举上限（防 evaluate 超时）
  var MAX_URLS_PER_GEAR = 6;   // 每档 play_addr.url_list 取用上限
  var R = window._ROUTER_DATA || window.__ROUTER_DATA__ || window.ROUTER_DATA;
  var out = {
    items: 0,
    playUrls: 0,
    downloadUrls: 0,
    videoKeys: [],
    bitRateCount: 0,
    bitRateKeys: [],
    refs: [],
  };
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
            if (isHttp(u)) {
              out.playUrls++;
              out.refs.push({
                field: 'play_addr',
                url: u,
                source: 'play_addr',
                gear: null,
                width: num(pa.width),
                height: num(pa.height),
                bitrate: null,
                dataSize: num(pa.data_size)
              });
            }
          }
        }
        var da = v.download_addr;
        if (da && Array.isArray(da.url_list)) {
          for (var d = 0; d < da.url_list.length && d < 12; d++) {
            var u2 = da.url_list[d];
            if (isHttp(u2)) {
              out.downloadUrls++;
              out.refs.push({
                field: 'download_addr',
                url: u2,
                source: 'download_addr',
                gear: null,
                width: null,
                height: null,
                bitrate: null,
                dataSize: null
              });
            }
          }
        }
        // P8.2 新增：video.bit_rate[] 多档清晰度（只枚举真实字段；每档 play_addr.url_list 追加为候选）
        var br = v.bit_rate;
        if (Array.isArray(br)) {
          for (var b = 0; b < br.length && b < MAX_BITRATE_GEARS; b++) {
            var g = br[b];
            if (!g) continue;
            out.bitRateCount++;
            if (out.bitRateKeys.length === 0) {
              var gk = 0;
              for (var key in g) {
                if (out.bitRateKeys.length < 12) out.bitRateKeys.push(key);
                gk++;
                if (gk > 40) break;
              }
            }
            var ua = g.play_addr;
            if (!ua || !Array.isArray(ua.url_list)) continue;
            var gear = (typeof g.gear_name === 'string') ? g.gear_name
              : ((typeof g.gearName === 'string') ? g.gearName : null);
            var gw = num(ua.width);
            var gh = num(ua.height);
            var gb = num(g.bit_rate);
            var gs = num(ua.data_size);
            for (var q = 0; q < ua.url_list.length && q < MAX_URLS_PER_GEAR; q++) {
              var uq = ua.url_list[q];
              if (isHttp(uq)) {
                out.refs.push({
                  field: 'play_addr',
                  url: uq,
                  source: 'bit_rate',
                  gear: gear,
                  width: gw,
                  height: gh,
                  bitrate: gb,
                  dataSize: gs
                });
              }
            }
          }
        }
      }
    }
  } catch (e) {
    out.refs = [];
  }
  return JSON.stringify(out);
})();
