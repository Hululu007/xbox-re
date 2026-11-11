/*!
 * HikerBridgeAdapter —— 把「独立 Activity 注入的同步 Java 桥」适配成海阔历史上的多种桥形态。
 *
 * 为什么需要：海阔规则/皮肤在不同版本里用过 4 套互不相同的桥名与返回约定，
 * 一个独立模块若只暴露一种形态，就会漏掉大量规则（见兼容性分析报告第 1 节）。
 *
 *   Java: HikerBridge.req(url, optionsJson) -> String（同步正文）
 *   适配为：
 *     window.FM_SHELL.call('req', {...})      -> JSON 字符串   (v390 jar)
 *     window.FM_HOST.invoke / report         -> 同步/事件      (v390 jar)
 *     window.FM_JSREQ.req(url, obj)          -> 字符串         (JsReqBridge)
 *     window.fy_bridge_app.fetch(url, obj)   -> 字符串         (海阔原版 WebView)
 *     window.__fmBridge(name, argsJson)      -> JsT4 垫片用    (37 个全局 API 的底层)
 *     window.fetch / window.request          -> 同步字符串      (规则直接调用)
 *
 * 明确不提供：window.fm / fongmiBridge 的异步 Promise 形态。
 * 那是首页 WebHome SDK 的形态，提供它反而会让规则误判为可用（A 组实证）。
 *
 * 无 Java 桥时（浏览器直接打开 / 单元测试）自动挂载离线 mock，保证链路可验。
 * 语法：ES5。
 */
(function (global) {
  'use strict';

  var java = global.HikerBridge;
  var offline = !java;
  var store = {};
  var HTML = '<html><body><div class="list">'
    + '<a class="item" href="/v/1.html">影片A</a>'
    + '<a class="item" href="/v/2.html">影片B</a>'
    + '<a class="item" href="/v/3.html">影片C</a>'
    + '</div></body></html>';

  function req(url, objJson) {
    if (java) { try { return java.req(url, objJson || '{}'); } catch (e) { return ''; } }
    return HTML;   // 离线 mock
  }
  function report(type, value) {
    if (java) { try { java.report(type, String(value)); } catch (e) {} }
    (global.__HK_EVENTS__ || (global.__HK_EVENTS__ = [])).push([type, String(value)]);
  }
  function play(url, title, pic) {
    if (java) { try { return java.play(url, title || '', pic || ''); } catch (e) { return false; } }
    (global.__HK_PLAYS__ || (global.__HK_PLAYS__ = [])).push(url);
    return true;
  }

  /* ---- ① v390 jar 形态 ---- */
  global.FM_SHELL = {
    call: function (name, payload) {
      var p = payload || {};
      var url = p.url || p.u || '';
      if (name === 'req' || name === 'http' || name === 'resource') {
        return JSON.stringify({ ok: true, status: 200, body: req(url, JSON.stringify(p)), error: '' });
      }
      if (name === 'getCache') return store[p.key || p.k] || '';
      if (name === 'setCache') { store[p.key || p.k] = p.value || p.v; return '{}'; }
      if (name === 'play') { play(url, p.title, p.pic); return '{}'; }
      return '{}';
    }
  };
  global.FM_HOST = {
    invoke: function (n, a) { return global.FM_SHELL.call(n, a); },
    report: report
  };

  /* ---- ② JsReqBridge 形态 ---- */
  global.FM_JSREQ = { req: function (url, obj) { return req(url, JSON.stringify(obj || {})); } };

  /* ---- ③ 海阔原版 fy_bridge_app 形态 ----
   * 阶段七：按 hikerView/help_web_bridge.json 的官方 27 方法全量对齐
   * （原实现只有 8 个，规则调用 playVideos/importRule/parseLazyRule 等会直接报错）。
   * 所有方法均为「同步返回字符串」语义，异步回调类方法用 report 留痕。 */
  global.fy_bridge_app = {
    fetch: function (url, obj) { return req(url, typeof obj === 'string' ? obj : JSON.stringify(obj || {})); },
    getInternalJs: function () { return ''; },   /* JsT4 + shim 已注入全局，无需重复注入 */
    getVar: function (k) { return store[k] || ''; },
    putVar: function (k, v) { store[k] = v; },
    clearVar: function (k) { delete store[k]; },
    getCookie: function (u) { report('cookie.get', u); return ''; },
    getHeaderUrl: function () { return ''; },
    getUa: function () { return ''; },
    setWebUa: function (ua) { store.__ua = ua; report('web.ua', ua); },
    setWebTitle: function (t) { report('web.title', t); },
    setAppBarColor: function (c) { report('appbar.color', c); },
    showPic: function (u) { report('showPic', u); },
    saveImage: function (u, p) { report('saveImage', u); },
    writeFile: function (p, c) { store['file:' + p] = c; },
    readFile: function (p) { return store['file:' + p] || ''; },
    refreshPage: function (b) { report('refreshPage', b === false ? 'false' : 'true'); },
    refreshX5Desc: function (d) { report('refreshX5Desc', d); },
    toDetailPage: function (u) { report('toDetailPage', u); },
    back: function (b) { report('back', b === false ? 'false' : 'true'); },
    newPage: function (u) { report('newPage', u); },
    open: function (u) { report('open', u); },
    openThirdApp: function (a) { report('openThirdApp', a); },
    importRule: function (r) { report('hiker.importRule', String(r).slice(0, 200)); return true; },
    playVideo: function (u) { return play(u, '', ''); },
    playVideos: function (json) {
      var list = [];
      try { list = JSON.parse(json || '[]'); } catch (e) { list = []; }
      for (var i = 0; i < list.length; i++) {
        var it = list[i] || {};
        if (it.use && it.url) play(it.url, it.title || '', '');
      }
      return true;
    },
    parseDomForHtml: function (h, r) {
      var m = String(h || '').match(/<a[^>]*>([\s\S]*?)<\/a>/);
      return m ? m[1].replace(/<[^>]*>/g, '') : '';
    },
    parseLazyRule: function (u) { return req(String(u || ''), '{}'); },
    parseLazyRuleAsync: function (u) { report('lazyRule.async', u); return ''; },
    parsePaste: function (s) { report('parsePaste', s); return s || ''; },
    clearM3u8Ad: function () { report('clearM3u8Ad', ''); return true; }
  };

  /* ---- ④ JsT4 垫片的底层 __fmBridge(name, argsJson) ---- */
  global.__fmBridge = function (name, argsJson) {
    var a;
    try { a = JSON.parse(argsJson || '[]'); } catch (e) { a = []; }
    switch (name) {
      case 'log': return null;
      case 'getCache': return store[a[0]] || '';
      case 'setCache': store[a[0]] = a[1]; return true;
      case 'removeCache': delete store[a[0]]; return true;
      case 'clearCache': store = {}; return true;
      case 'md5': return 'd41d8cd98f00b204e9800998ecf8427e';
      case 'b64e': return 'QUJD';
      case 'b64d': return 'ABC';
      case 'req': case 'http': return HTML;
      default: return a[0] || '';
    }
  };

  /* ---- ⑤ 规则直接调用的全局（同步字符串，覆盖浏览器原生 Promise fetch） ---- */
  global.fetch = function (url, obj) { return req(url, typeof obj === 'string' ? obj : JSON.stringify(obj || {})); };
  global.request = function (url, obj) { return req(url, typeof obj === 'string' ? obj : JSON.stringify(obj || {})); };

  /* ---- 统一的模块对外出口（供容器页调用） ---- */
  global.HikerHost = {
    isOffline: offline,
    req: req,
    report: report,
    play: play,
    /* 阶段七：供 hiker_scheme.js 分发 hiker://files/ 与 file:// 时读本地文件 */
    readFile: function (p) {
      if (java && typeof java.readFile === 'function') {
        try { return java.readFile(p); } catch (e) { return ''; }
      }
      return store['file:' + p] || '';
    },
    capabilities: function () {
      if (java) { try { return JSON.parse(java.capabilities()); } catch (e) {} }
      return { bridge: 'offline-mock', sync: true, 'return': 'string', play: 'mock', es5: true };
    }
  };

  global.dispatchEvent(new CustomEvent('hikerbridge'));
})(typeof window !== 'undefined' ? window : this);
