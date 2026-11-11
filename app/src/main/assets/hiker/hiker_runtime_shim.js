/*
 * hiker_runtime_shim.js — 海阔规则运行时「补齐层」模块骨架（v0.1.0）
 *
 * 定位
 * ----
 * 这不是给用户看的 H5 页面，而是「海阔运行时模块」里注入 WebView 的那一层 JS。
 * 它不区分宿主是「外部 spider 插件 jar」还是「App 内新增模块」：两条路线共用本文件，
 * 差别只在谁来创建 WebView、谁来提供同步网络桥。
 *
 * 依赖与分工
 * ----------
 *  - 推荐先注入 v390 jar 的 JsT4 运行时垫片（37 个全局 API：pdfh/pdfa/pd/$/joinUrl/
 *    md5/aes/des/rsa/base64/gbk/local/定时器…）。本模块保留它们，不重复实现。
 *  - 本模块只补 JsT4 没有、而海阔规则必需的四类能力：
 *      L1 同步字符串 fetch/request（海阔 $hiker.js 用 eval.call(null, fetch(url))）
 *      L2 MY_* / input / params / d / host / config 等每页内置变量
 *      L3 生命周期与回传 API（setResult / getResCode / readFile / setPageTitle …）
 *      L4 $ 的 jQuery 风格化（$.require / $.fn.clickRule/lazyRule/x5cKey/x5rule）
 *      L5 hiker:// 虚拟路由表（page/search/empty/files/images/home/assets）
 *  - L5 只是路由解析，真实页面栈、原生 UI、加密规则、Python 规则、X5 嗅探
 *    必须由 Java 侧实现，通过 RT.onNavigate / RT.onResult 两个回调接出去。
 *
 * 诚实标注
 * --------
 *  - pdfh/pdfa/pd/parseDomForArray 在 JsT4 缺失时这里的正则是「离线自测 stub」，
 *    不是生产级 DOM 解析；生产环境务必复用 JsT4 或 Java 侧 jsoup。
 *  - 对齐目标是 hiker_54.db 的规则契约，覆盖率基线见阶段报告四：裸 WebView 0/78，
 *    加本层 22/78。剩余缺口在报告四第 4 节列出。
 *
 * 用法
 * ----
 *   // Java 侧：webView.addJavascriptInterface(...); 然后 evaluateJavascript(shim)
 *   HikerRuntime.install({
 *     page: {MY_URL:'...', MY_KEYWORD:'...'},
 *     onResult: function(v, json){ FM_HOST.report('hiker.result', json); },
 *     onNavigate: function(r){ HikerModule.openPage(r); }
 *   });
 */
(function (global) {
  'use strict';

  var RT = {
    version: '0.1.0-module-skeleton',
    lastResult: null,
    pageStack: [],
    onResult: null,
    onNavigate: null,
    bridge: 'unset',
    report: null
  };

  // ------------------------------------------------------------ 工具
  // FM_SHELL.call('req', ...) 在 v390 里返回 JSON 字符串；JsReqBridge.req /
  // fy_bridge_app.fetch 返回正文字符串；fm.req 返回对象。统一收敛成「字符串」。
  function coerceToString(r) {
    if (r == null) return '';
    if (typeof r === 'string') {
      var s = r;
      if (s.charAt(0) === '{' || s.charAt(0) === '[') {
        try {
          var o = JSON.parse(s);
          if (o && typeof o === 'object') return pickBody(o);
        } catch (e) { /* 不是 JSON，原样返回 */ }
      }
      return s;
    }
    if (typeof r === 'object') return pickBody(r);
    return String(r);
  }
  function pickBody(o) {
    if (typeof o.body === 'string') return o.body;
    if (typeof o.text === 'string') return o.text;
    if (typeof o.html === 'string') return o.html;
    if (typeof o.data === 'string') return o.data;
    return JSON.stringify(o);
  }

  function define(name, value, overwrite) {
    var exists = (typeof global[name] !== 'undefined');
    if (exists && !overwrite) return false;
    try {
      Object.defineProperty(global, name, {
        value: value, writable: true, configurable: true, enumerable: true
      });
    } catch (e) { global[name] = value; }
    return true;
  }

  // ------------------------------------------------------------ L1 同步桥选择
  // 优先级：显式注入 > FM_SHELL.call(同步) > FM_JSREQ.req(同步) >
  //          fy_bridge_app.fetch(原版海阔同步桥) > 离线 stub
  // 注意：wogg 的 fongmiBridge / window.fm 是异步 Promise，故意不选，
  //       它会导致 pdfa 收到 [object Promise]（阶段四实测 2/44）。
  function pickBridge(opts) {
    if (typeof opts.request === 'function') {
      RT.bridge = 'injected:opts.request';
      return opts.request;
    }
    if (global.FM_SHELL && typeof global.FM_SHELL.call === 'function') {
      RT.bridge = 'FM_SHELL.call(req)';
      return function (url, obj) {
        return coerceToString(global.FM_SHELL.call('req', {
          url: url, options: obj || {}
        }));
      };
    }
    if (global.FM_JSREQ && typeof global.FM_JSREQ.req === 'function') {
      RT.bridge = 'FM_JSREQ.req';
      return function (url, obj) {
        return coerceToString(global.FM_JSREQ.req(url, JSON.stringify(obj || {})));
      };
    }
    if (global.fy_bridge_app && typeof global.fy_bridge_app.fetch === 'function') {
      RT.bridge = 'fy_bridge_app.fetch';
      return function (url, obj) {
        return coerceToString(global.fy_bridge_app.fetch(url, JSON.stringify(obj || {})));
      };
    }
    RT.bridge = 'offline-stub';
    return function () { return opts.offlineHtml || ''; };
  }

  // ------------------------------------------------------------ L3 结果回传
  function emit(v) {
    RT.lastResult = v;
    var payload = (typeof v === 'string') ? v : JSON.stringify(v);
    if (typeof RT.onResult === 'function') {
      try { RT.onResult(v, payload); } catch (e) {}
    } else if (global.FM_HOST && typeof global.FM_HOST.report === 'function') {
      try { global.FM_HOST.report('hiker.result', payload); } catch (e) {}
    }
  }

  // ------------------------------------------------------------ L5 hiker:// 路由
  var ROUTES = {
    empty:  function ()      { return { type: 'empty' }; },
    page:   function (q)     { return { type: 'page', url: q.p || q.url || '' }; },
    search: function (q)     { return { type: 'search', keyword: q.kw || q.wd || q.s || '' }; },
    files:  function (q)     { return { type: 'files', path: q.p || q.path || '' }; },
    images: function (q)     { return { type: 'images', urls: q.u || '' }; },
    home:   function ()      { return { type: 'home' }; },
    assets: function (q)     { return { type: 'assets', path: q.p || '' }; }
  };
  RT.navigate = function (url) {
    var s = String(url || '');
    if (s.indexOf('hiker://') !== 0) return { type: 'external', url: s, raw: s };
    var rest = s.slice(8), i = rest.indexOf('?');
    var name = (i < 0 ? rest : rest.slice(0, i)).split('/')[0];
    var q = {};
    if (i >= 0) {
      rest.slice(i + 1).split('&').forEach(function (kv) {
        if (!kv) return;
        var p = kv.split('=');
        try {
          q[decodeURIComponent(p[0])] = decodeURIComponent(p.slice(1).join('='));
        } catch (e) { q[p[0]] = p.slice(1).join('='); }
      });
    }
    var r = (ROUTES[name] || ROUTES.page)(q);
    r.raw = s;
    RT.pageStack.push(r);
    if (typeof RT.onNavigate === 'function') {
      try { RT.onNavigate(r); } catch (e) {}
    }
    return r;
  };

  // ------------------------------------------------------------ L2 每页变量
  var DEFAULT_PAGE = {
    MY_URL: '', MY_HOME: '', MY_PAGE: 1, MY_KEYWORD: '',
    MY_RULE: {}, MY_PARAMS: '', input: '', params: {}, d: [], host: '',
    config: {}, MOBILE_UA: 'Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36',
    PC_UA: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
  };
  RT.setPage = function (o) {
    o = o || {};
    var p = {};
    Object.keys(DEFAULT_PAGE).forEach(function (k) {
      p[k] = (o[k] !== undefined) ? o[k] : DEFAULT_PAGE[k];
      define(k, p[k], true);          // 每页强制刷新
    });
    return p;
  };

  // ------------------------------------------------------------ 安装
  RT.install = function (opts) {
    opts = opts || {};
    var provided = [], overridden = [], kept = [];
    var syncReq = pickBridge(opts);

    // L1：强制同步字符串。JsT4.fetch 返回 {ok,text(),json()} 对象，必须覆盖；
    //     JsT4.request 虽是字符串，但统一由同一桥驱动，避免两套网络语义。
    define('fetch', function (url, obj) { return syncReq(url, obj); }, true);
    define('request', function (url, obj) { return syncReq(url, obj); }, true);
    overridden.push('fetch', 'request');

    // L2
    RT.setPage(opts.page || {});
    provided.push('MY_* / input / params / d / config / UA');

    // L3：DOM 解析类若 JsT4 已提供则保留真实现，否则给离线 stub
    var domStub = {
      pdfa: function (h) { return (String(h).match(/class="item"[^>]*>/g) || []); },
      pdfh: function (h) { return (String(h).match(/<a[^>]*>([^<]*)<\/a>/) || [])[1] || ''; },
      pd:   function (h) { return (String(h).match(/<a[^>]*>([^<]*)<\/a>/) || [])[1] || ''; },
      parseDomForArray: function (h) { return (String(h).match(/class="item"[^>]*>/g) || []); }
    };
    Object.keys(domStub).forEach(function (k) {
      if (typeof global[k] === 'undefined') { define(k, domStub[k], true); provided.push(k + '(stub)'); }
      else { kept.push(k + '(JsT4)'); }
    });

    var api = {
      setResult:     function (v) { emit(v); },
      setHomeResult: function (v) { emit(v); },
      log:           function () {},
      toast:         function () {},
      refreshPage:   function () {},
      back:          function () {},
      setPageTitle:  function () {},
      getParam:      function () { return ''; },
      getAppVersion: function () { return 9999; },
      getMyVar:      function () { return ''; },
      putMyVar:      function () {},
      getVar:        function () { return ''; },
      getItem:       function () { return ''; },
      setItem:       function () {},
      clearMyVar:    function () {},
      clearPublicItem: function () {},
      readFile:      function () { return opts.offlineHtml || ''; },
      writeFile:     function () {},
      fileExist:     function () { return false; },
      getPath:       function () { return ''; },
      getResCode:    function () { return { code: 200, headers: {} }; },
      getApi:        function () { return opts.offlineHtml || ''; },
      fetchPC:       function () { return opts.offlineHtml || ''; },
      getHome:       function () { return ''; },
      evalPrivateJS: function () { return ''; },   // 加密规则：待 Java 侧实现
      rc:            function () { return ''; },
      sleep:         function () {},
      require:       function () { return {}; },
      java:          {},
      py:            {}
    };
    Object.keys(api).forEach(function (k) {
      if (define(k, api[k], true)) provided.push(k);
    });

    // L4：$ 的 jQuery 风格化
    // 实测坑（阶段五）：JsT4 的 $ 是 cheerio 风格，$(html).lazyRule(...) 会抛
    // "lazyRule is not a function"，导致网络规则全挂。所以不能只挂静态属性，
    // 必须包装调用结果，并把方法挂到原型/fn 上让链式调用也带得上。
    var origDollar = global.$;
    var jq;
    if (typeof origDollar === 'function') {
      jq = function () {
        var r = origDollar.apply(this, arguments);
        if (r && typeof r === 'object') {
          if (typeof r.lazyRule !== 'function') r.lazyRule = function (f) { return f; };
          if (typeof r.clickRule !== 'function') r.clickRule = function () { return ''; };
          if (typeof r.x5cKey !== 'function') r.x5cKey = function () { return ''; };
          if (typeof r.x5rule !== 'function') r.x5rule = function () { return ''; };
        } else if (r == null) {
          r = { lazyRule: function (f) { return f; }, toString: function () { return ''; } };
        }
        return r;
      };
      Object.keys(origDollar).forEach(function (k) {
        try { jq[k] = origDollar[k]; } catch (e) {}
      });
      var proto = origDollar.fn ||
                  (typeof origDollar.prototype === 'object' ? origDollar.prototype : null);
      if (proto) {
        ['lazyRule', 'clickRule', 'x5cKey', 'x5rule'].forEach(function (k) {
          if (typeof proto[k] !== 'function') {
            proto[k] = (k === 'lazyRule') ? function (f) { return f; }
                                          : function () { return ''; };
          }
        });
      }
      define('$', jq, true);
      overridden.push('$(包装 JsT4，补 lazyRule/clickRule)');
      kept.push('$ 的 DOM 解析能力(JsT4)');
    } else {
      jq = function () {
        return { lazyRule: function (f) { return f; }, toString: function () { return ''; } };
      };
      define('$', jq, true);
      provided.push('$');
    }
    jq.require = jq.require || function () { return {}; };
    jq.toString = jq.toString || function (f) { return String(f); };
    jq.extend = jq.extend || function (o) { Object.keys(o || {}).forEach(function (k) { jq[k] = o[k]; }); return jq; };
    jq.get = jq.get || function () { return ''; };
    jq.post = jq.post || function () { return ''; };
    jq.fn = jq.fn || {};
    ['clickRule', 'lazyRule', 'x5cKey', 'x5rule', 'pdfh', 'pdfa'].forEach(function (k) {
      if (typeof jq.fn[k] !== 'function') jq.fn[k] = function () { return ''; };
    });
    provided.push('$.require / $.fn.clickRule|lazyRule|x5cKey|x5rule');

    RT.onResult = typeof opts.onResult === 'function' ? opts.onResult : null;
    RT.onNavigate = typeof opts.onNavigate === 'function' ? opts.onNavigate : null;

    RT.report = {
      version: RT.version,
      bridge: RT.bridge,
      provided: provided,
      overridden: overridden,
      keptFromJsT4: kept,
      routes: Object.keys(ROUTES),
      note: kept.length ? 'DOM 解析复用 JsT4' : 'DOM 解析为离线 stub，生产需换 JsT4/jsoup'
    };
    return RT.report;
  };

  global.HikerRuntime = RT;
})(typeof window !== 'undefined' ? window : globalThis);
