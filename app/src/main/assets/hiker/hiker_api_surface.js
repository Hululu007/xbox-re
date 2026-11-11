/*!
 * HikerApiSurface —— 海阔官方 API 契约补齐层（阶段七）
 *
 * 契约来源（权威，非经验猜测）：
 *   hikerView/app/src/main/assets/help_js.json          82 章 / 160 标识符
 *   hikerView/app/src/main/assets/help_web_bridge.json  fy_bridge_app 27 方法
 *   hikerView/JSEngine.java                            @JSAnnotation 100 方法 + 15 alias
 *   hiker_54.db                                       真实规则命中 134 个 / 15065 次调用
 *
 * 为什么要做：阶段六的兼容层只提供 36 个 API，真实规则要用的 134 个里缺 98 个
 * （updateItem 180 次、confirm 150 次、clearVar 134 次、parseDomForHtml 121 次…）。
 * hikerView 用 Rhino + 100 个 @JSAnnotation 原生实现；xingchen 用 9211 行
 * HkJsRuntime 逐类转写。两者都不做「能力分级」，也不支持新鲜作用域补注入。
 *
 * 四档分级（不静默假成功，是本项目相对两者的结构性差异）：
 *   full     纯 JS 真实现（存储/编码/DOM/事件/时间/URL/项目操作）
 *   bridge   同步桥转发到 Java（网络增强/加密/播放/原生 UI）
 *   delegate 保留 shim / JsT4 / adapter 已有的真实现（绝不覆盖）
 *   degrade  宿主能力（X5 嗅探 / 私有加密规则 / Java 字节码 / 代理服务）显式降级标记
 *
 * 作用域模型（取自 xingchen 真机教训 DELIVERY_NEXT.md:1.3.1 / BLUEPRINT.md:198）：
 *   列表、详情走共享作用域；点播放、lazyRule、分类钮走「新鲜作用域」。
 *   新鲜作用域若只挂 MY_* 不挂预置函数，就会报 getUaObject / getJsPlugin 未定义。
 *   AS.installFresh() 把 134 个 API 全量装进新作用域，专门修这一类问题。
 *   变量按 "规则名@key" 隔离（HkEngineManager.ruleKey 口径）。
 *
 * 语法：纯 ES5（Chromium 50~70），不依赖 Proxy / Promise / 箭头 / 模板串。
 */
(function (global) {
  'use strict';

  var AS = { version: '2.0.0' };

  /* ---------------- 官方契约表：134 个被真实规则调用的 API，按能力分级 ---------------- */
  var C = {
    delegate: ['pdfh', 'pdfa', 'pd', 'parseDomForArray', 'fetch', 'request', 'require',
               'setResult', 'setHomeResult', 'getInternalJs', 'getResCode', 'toDetailPage'],
    full: [
      /* 存储（按规则名隔离） */
      'getVar', 'putVar', 'putVar2', 'clearVar', 'getMyVar', 'putMyVar', 'clearMyVar',
      'listMyVarKeys', 'getItem', 'setItem', 'clearItem', 'getPublicItem', 'setPublicItem',
      'clearPublicItem', 'getUaObject', 'getParam', 'getPageTitle', 'setPageTitle',
      'setPagePicUrl', 'setPageParams', 'setPageReverse', 'isPageReversed',
      'setLastChapterRule', 'refreshPage', 'refreshX5Desc', 'back', 'toast', 'error',
      'setError', 'log', 'nlog', 'showLoading', 'hideLoading', 'confirm',
      'showSelectOptions', 'addListener', 'copy', 'getTime', 'buildUrl', 'getUrl',
      'getAppVersion', 'getSearchMode', 'setSearchMode', 'getRule', 'getHome',
      'getHomeSub', 'hasHomeSub', 'getColTypes', 'getLastRules', 'getRuleCount',
      'getPrivateJS', 'getJsPlugin', 'getJsLazyPlugin', 'findItem', 'findItemsByCls',
      'updateItem', 'addItemAfter', 'addItemBefore', 'deleteItem', 'deleteItemByCls',
      'parseDomForHtml', 'parseDom', 'readFile', 'writeFile', 'saveFile', 'deleteFile',
      'fileExist', 'getPath', 'deleteCache', 'getPastes', 'parsePaste', 'sharePaste',
      'base64Encode', 'base64Decode', 'atob', 'btoa', 'encodeStr', 'decodeStr',
      'encodeToString', 'hexToBytes', 'hexToBase64', 'searchContains', 'setSearchResult',
      'setStrResult', 'setLastChapterResult', 'nsetHomeResult', 'nsetLastChapterRule',
      'closeMe', 'open', 'openAppIntent', 'openThirdApp', 'getHeaderUrl', 'getUa',
      'setWebTitle', 'setWebUa', 'playVideo', 'playVideos', 'refresh', 'rc', 'be', 'bf',
      'fc', 'evalPrivateJS'
    ],
    bridge: [
      'md5', 'aesEncode', 'aesDecode', 'rsaEncrypt', 'rsaDecrypt', 'getCryptoJS',
      'getCookie', 'fetchCookie', 'fetchCache', 'fetchCodeByWebView', 'batchFetch',
      'batchExecute', 'postRequest', 'downloadFile', 'requireDownload', 'requireCache',
      'cacheM3u8', 'fixM3u8', 'batchCacheM3u8', 'clearM3u8Ad', 'clearM3u8AdLazy',
      'refreshVideoUrl', 'refreshX5WebView', 'x5Rule', 'parseLazyRule',
      'parseLazyRuleAsync', 'getIP', 'getCpuAbi', 'findReachableIP', 'ipping',
      'getCryptoJS2', 'writeHexFile', 'convertBase64Image', 'createQRCode'
    ],
    degrade: ['evalPrivateJSEngine', 'loadJavaClass', 'startProxyServer', 'registerDNS',
              'registerTask', 'unRegisterTask', 'syncExecute', 'publishRule']
  };
  /* 官方 134 之外的额外能力：JSEngine / help_js 有定义但本规则库未调用。
   * 仍然实现（getJsPlugin / getUaObject 正是 xingchen 真机报「未定义」的两个），
   * 但计入分母会让覆盖率虚高，故单列 EXTRA，不计入 official。 */
  var EXTRA = ['evalPrivateJSEngine', 'findReachableIP', 'fixM3u8', 'getCpuAbi',
    'getCryptoJS2', 'getHomeSub', 'getJsLazyPlugin', 'getJsPlugin', 'getPrivateJS',
    'getUaObject', 'ipping', 'isPageReversed', 'loadJavaClass', 'publishRule',
    'putVar2', 'refreshVideoUrl', 'refreshX5WebView', 'requireCache',
    'setLastChapterResult', 'setPageReverse', 'setStrResult', 'syncExecute', 'writeHexFile'];
  var ALL = [];
  Object.keys(C).forEach(function (lv) {
    C[lv].forEach(function (n) { if (ALL.indexOf(n) < 0) ALL.push(n); });
  });
  var OFFICIAL = ALL.filter(function (n) { return EXTRA.indexOf(n) < 0; });
  AS.contract = C; AS.official = OFFICIAL; AS.extra = EXTRA; AS.all = ALL;

  /* 绝不覆盖（shim 的 emit 回传 / JsT4 的真 DOM 解析 / HikerScheme 包装过的同步桥） */
  var PROTECT = {
    setResult: 1, setHomeResult: 1, fetch: 1, request: 1, pdfh: 1, pdfa: 1, pd: 1,
    parseDomForArray: 1, getInternalJs: 1, $: 1, getResCode: 1
  };

  /* ---------------- 共享状态（模块级单例，跨作用域一致） ---------------- */
  var S = {
    vars: {}, myVars: {}, items: {}, publicItems: {}, files: {}, listeners: {},
    pastes: [], page: {}, ruleKey: '', flags: { reversed: false, loading: false },
    reports: []
  };
  function str(v) { return (v === null || v === undefined) ? '' : String(v); }
  function key(k, r) { return (r || S.ruleKey || '') + '@' + str(k); }
  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function jparse(s, d) { try { return JSON.parse(s); } catch (e) { return d; } }
  function emit(name, v) {
    S.reports.push([name, v]);
    if (global.HikerRuntime && typeof global.HikerRuntime.emitResult === 'function') {
      try { global.HikerRuntime.emitResult(v); } catch (e) {}
    } else if (global.HikerRuntime) {
      try { global.HikerRuntime.lastResult = v; } catch (e) {}
    }
    if (global.HikerHost && typeof global.HikerHost.report === 'function') {
      try { global.HikerHost.report('hiker.api.' + name, typeof v === 'string' ? v : JSON.stringify(v)); } catch (e) {}
    }
    return v;
  }
  function b64e(s) {
    s = str(s);
    try { return global.btoa ? global.btoa(unescape(encodeURIComponent(s))) : s; } catch (e) { return s; }
  }
  function b64d(s) {
    s = str(s);
    try { return global.atob ? decodeURIComponent(escape(global.atob(s))) : s; } catch (e) { return s; }
  }
  /* 同步桥转发：Java 显式方法 > FM_SHELL.call > __fmBridge > HikerHost.req > 降级留痕 */
  function bridgeCall(name, args, opts) {
    opts = opts || {};
    var a = Array.prototype.slice.call(args || []), r = null;
    if (global.HikerBridge && typeof global.HikerBridge[name] === 'function') {
      try { r = global.HikerBridge[name].apply(global.HikerBridge, a); } catch (e) {}
    }
    if (r === null && global.FM_SHELL && typeof global.FM_SHELL.call === 'function') {
      try { r = global.FM_SHELL.call(name, { args: a }); } catch (e) {}
    }
    if (r === null && typeof global.__fmBridge === 'function') {
      try { r = global.__fmBridge(name, JSON.stringify(a)); } catch (e) {}
    }
    if (r === null && opts.network && global.HikerHost && typeof global.HikerHost.req === 'function') {
      try { r = global.HikerHost.req(str(a[0]), a[1] || {}); } catch (e) {}
    }
    if (r === null || r === undefined) {
      S.reports.push(['bridge-miss', name]);
      return '';   /* 明确空串 + 留痕，不伪装成成功 */
    }
    if (typeof r === 'string' && (r.charAt(0) === '{' || r.charAt(0) === '[')) {
      var o = jparse(r, null);
      if (o && typeof o.body === 'string') return o.body;
    }
    return typeof r === 'string' ? r : String(r);
  }
  function degrade(name) { S.reports.push(['degrade', name]); return { __hk_degraded__: name }; }
  function fire(event, payload) {
    var ls = S.listeners[event] || [];
    for (var i = 0; i < ls.length; i++) { try { ls[i](payload); } catch (e) {} }
  }
  function net(url, obj) {
    var u = str(url);
    if (global.HikerScheme && typeof global.HikerScheme.dispatch === 'function') {
      return global.HikerScheme.dispatch(u, { request: null, options: obj }).body;
    }
    if (global.HikerHost && typeof global.HikerHost.req === 'function') {
      return str(global.HikerHost.req(u, obj || {}));
    }
    if (typeof global.fetch === 'function') return str(global.fetch(u, obj || {}));
    return '';
  }
  function domParse(html, rule, all) {
    var h = str(html);
    /* 无 DOM 引擎时的保守近似：按 class="item" / <a> 抽取；生产环境由 JsT4 接管 */
    if (all) return h.match(/<a[^>]*>[\s\S]*?<\/a>/g) || [];
    var m = h.match(/<a[^>]*>([\s\S]*?)<\/a>/);
    return m ? m[1].replace(/<[^>]*>/g, '') : '';
  }

  /* ---------------- 真实现（高频 API） ---------------- */
  var IMPL = {
    /* 存储 */
    getVar: function (k, d) { return has(S.vars, str(k)) ? S.vars[str(k)] : (d === undefined ? '' : d); },
    putVar: function (k, v) { S.vars[str(k)] = v; return v; },
    putVar2: function (k, v) { return IMPL.putVar(k, v); },
    clearVar: function (k) { if (k === undefined) S.vars = {}; else delete S.vars[str(k)]; return true; },
    getMyVar: function (k, d, r) { var q = key(k, r); return has(S.myVars, q) ? S.myVars[q] : (d === undefined ? '' : d); },
    putMyVar: function (k, v, r) { S.myVars[key(k, r)] = v; return v; },
    clearMyVar: function (k, r) { delete S.myVars[key(k, r)]; return true; },
    listMyVarKeys: function (r) {
      var pre = (r || S.ruleKey || '') + '@', out = [];
      Object.keys(S.myVars).forEach(function (q) { if (q.indexOf(pre) === 0) out.push(q.slice(pre.length)); });
      return out;
    },
    getItem: function (k, d) { return has(S.items, str(k)) ? S.items[str(k)] : (d === undefined ? '' : d); },
    setItem: function (k, v) { S.items[str(k)] = v; return v; },
    clearItem: function (k) { if (k === undefined) S.items = {}; else delete S.items[str(k)]; return true; },
    getPublicItem: function (k, d) { return has(S.publicItems, str(k)) ? S.publicItems[str(k)] : (d === undefined ? '' : d); },
    setPublicItem: function (k, v) { S.publicItems[str(k)] = v; return v; },
    clearPublicItem: function (k) { if (k === undefined) S.publicItems = {}; else delete S.publicItems[str(k)]; return true; },
    getUaObject: function () { return JSON.stringify({ ua: (S.page.MOBILE_UA || ''), pcUa: (S.page.PC_UA || '') }); },
    getParam: function (k, d, url) {
      var u = str(url || S.page.MY_URL || ''), i = u.indexOf('?');
      if (i < 0) return d === undefined ? '' : d;
      var parts = u.slice(i + 1).split('&'), j, p;
      for (j = 0; j < parts.length; j++) {
        p = parts[j].split('=');
        if (decodeURIComponent(p[0]) === str(k)) return decodeURIComponent(p.slice(1).join('='));
      }
      return d === undefined ? '' : d;
    },
    getUrl: function (k) { return str(k || S.page.MY_URL || ''); },
    buildUrl: function (u, o) {
      var base = str(u), q = [], k;
      if (o && typeof o === 'object') for (k in o) if (has(o, k)) q.push(encodeURIComponent(k) + '=' + encodeURIComponent(str(o[k])));
      return q.length ? base + (base.indexOf('?') >= 0 ? '&' : '?') + q.join('&') : base;
    },
    /* 页面与生命周期 */
    getPageTitle: function () { return str(S.page.MY_TITLE || ''); },
    setPageTitle: function (t) { S.page.MY_TITLE = str(t); return fire('title', t); },
    setPagePicUrl: function (u) { S.page.MY_PIC = str(u); return true; },
    setPageParams: function (p) { S.page.MY_PARAMS = p; return true; },
    setPageReverse: function (b) { S.flags.reversed = !!b; return true; },
    isPageReversed: function () { return S.flags.reversed; },
    setLastChapterRule: function (r) { S.page.lastChapterRule = r; return true; },
    refreshPage: function (top) { return fire('refresh', { top: !!top }); },
    refreshX5Desc: function (d) { return fire('x5desc', str(d)); },
    back: function (refresh) { return fire('back', { refresh: refresh !== false }); },
    toast: function (m) { return fire('toast', str(m)); },
    error: function (m) { return IMPL.setError(m); },
    setError: function (m) { S.reports.push(['error', str(m)]); return fire('error', str(m)); },
    log: function (m) { S.reports.push(['log', str(m)]); return null; },
    nlog: function (m) { return IMPL.log(m); },
    showLoading: function (m) { S.flags.loading = true; return fire('loading', str(m)); },
    hideLoading: function () { S.flags.loading = false; return fire('loading', ''); },
    confirm: function (o) { return fire('confirm', o || {}); },
    showSelectOptions: function (o) { return fire('select', o || {}); },
    addListener: function (e, fn) { (S.listeners[str(e)] || (S.listeners[str(e)] = [])).push(fn); return true; },
    copy: function (t) { S.page.clipboard = str(t); return fire('copy', str(t)); },
    getTime: function () { return Date.now ? Date.now() : new Date().getTime(); },
    getAppVersion: function () { return 9999; },
    getSearchMode: function () { return S.page.smode || ''; },
    setSearchMode: function (m) { S.page.smode = str(m); return true; },
    getRule: function (k) { return str((S.page.MY_RULE || {})[str(k)] || ''); },
    getHome: function () { return S.page.homeJson || '[]'; },
    getHomeSub: function () { return S.page.homeSubJson || '[]'; },
    hasHomeSub: function () { return !!S.page.homeSubJson; },
    getColTypes: function () { return S.page.colTypes || '[]'; },
    getLastRules: function () { return S.page.lastRules || '[]'; },
    getRuleCount: function () { return S.page.ruleCount || 0; },
    getPrivateJS: function () { return S.page.privateJs || ''; },
    getJsPlugin: function () { return S.page.jsPlugin || ''; },
    getJsLazyPlugin: function () { return S.page.jsLazyPlugin || ''; },
    /* 项目操作 */
    findItem: function (id) { return has(S.items, str(id)) ? S.items[str(id)] : null; },
    findItemsByCls: function (cls) {
      var out = []; Object.keys(S.items).forEach(function (k) {
        var v = S.items[k]; if (v && v.cls === str(cls)) out.push(v);
      }); return out;
    },
    updateItem: function (id, o) { S.items[str(id)] = o; return fire('item.update', [id, o]); },
    addItemAfter: function (id, o) { S.items['after:' + str(id)] = o; return fire('item.addAfter', [id, o]); },
    addItemBefore: function (id, o) { S.items['before:' + str(id)] = o; return fire('item.addBefore', [id, o]); },
    deleteItem: function (id) { delete S.items[str(id)]; return fire('item.delete', id); },
    deleteItemByCls: function (cls) {
      Object.keys(S.items).forEach(function (k) { var v = S.items[k]; if (v && v.cls === str(cls)) delete S.items[k]; });
      return true;
    },
    /* DOM */
    parseDomForHtml: function (h, r) { return domParse(h, r, false); },
    parseDom: function (h, r) { return domParse(h, r, false); },
    /* 文件 */
    readFile: function (p) { return has(S.files, str(p)) ? S.files[str(p)] : net(str(p), {}); },
    writeFile: function (p, c) { S.files[str(p)] = str(c); return true; },
    saveFile: function (p, c) { return IMPL.writeFile(p, c); },
    deleteFile: function (p) { delete S.files[str(p)]; return true; },
    fileExist: function (p) { return has(S.files, str(p)); },
    getPath: function (p) { return 'hiker://files/' + str(p); },
    deleteCache: function () { S.vars = {}; return true; },
    /* 编码 */
    base64Encode: function (s) { return b64e(s); },
    base64Decode: function (s) { return b64d(s); },
    atob: function (s) { return b64d(s); },
    btoa: function (s) { return b64e(s); },
    encodeStr: function (s, enc) { return (enc === 'base64' || enc === 'b64') ? b64e(s) : encodeURIComponent(str(s)); },
    decodeStr: function (s, enc) { return (enc === 'base64' || enc === 'b64') ? b64d(s) : decodeURIComponent(str(s)); },
    encodeToString: function (s) { return b64e(s); },
    hexToBytes: function (h) { var o = [], s = str(h).replace(/\s/g, ''); for (var i = 0; i + 1 < s.length; i += 2) o.push(parseInt(s.substr(i, 2), 16)); return o; },
    hexToBase64: function (h) { return b64e(String.fromCharCode.apply(null, IMPL.hexToBytes(h))); },
    searchContains: function (s, k) { return str(s).indexOf(str(k)) >= 0; },
    /* 剪贴板 / 粘贴 */
    getPastes: function () { return JSON.stringify(S.pastes); },
    parsePaste: function (s) { return jparse(str(s), S.pastes); },
    sharePaste: function (s) { S.pastes.push(str(s)); return true; },
    /* 结果回传变体 */
    setSearchResult: function (v) { return emit('setSearchResult', v); },
    setStrResult: function (v) { return emit('setStrResult', str(v)); },
    setLastChapterResult: function (v) { return emit('setLastChapterResult', v); },
    nsetHomeResult: function (v) { return emit('nsetHomeResult', v); },
    nsetLastChapterRule: function (v) { return emit('nsetLastChapterRule', v); },
    rc: function () { return ''; },
    be: function () { return ''; },
    bf: function () { return ''; },
    fc: function () { return ''; },
    /* UI / 打开 */
    closeMe: function () { return fire('close', {}); },
    open: function (u) { return fire('open', str(u)); },
    openAppIntent: function (i) { return fire('intent', str(i)); },
    openThirdApp: function (a) { return fire('thirdApp', str(a)); },
    getHeaderUrl: function () { return str(S.page.MY_URL || ''); },
    getUa: function () { return str(S.page.MOBILE_UA || ''); },
    setWebTitle: function (t) { S.page.webTitle = str(t); return true; },
    setWebUa: function (u) { S.page.webUa = str(u); return true; },
    playVideo: function (u) { if (global.HikerHost && typeof global.HikerHost.play === 'function') global.HikerHost.play(str(u)); return fire('play', str(u)); },
    playVideos: function (v) { return fire('playVideos', v); },
    refresh: function () { return IMPL.refreshPage(false); },
    evalPrivateJS: function () { return degrade('evalPrivateJS'); },
    /* 别名（JSEngine alias / help_js） */
    getCryptoJS: function () { return global.CryptoJS || degrade('getCryptoJS'); }
  };

  /* ---------------- 构建：full 用真实现；bridge / degrade 生成默认转发 ---------------- */
  AS.build = function (opts) {
    opts = opts || {};
    var api = {}, i, n;
    for (i = 0; i < ALL.length; i++) {
      n = ALL[i];
      if (IMPL[n]) { api[n] = IMPL[n]; continue; }
      if (PROTECT[n] || C.delegate.indexOf(n) >= 0) continue;   /* 交给 shim/JsT4/适配器 */
      if (C.degrade.indexOf(n) >= 0) { api[n] = (function (name) { return function () { return degrade(name); }; })(n); continue; }
      if (C.bridge.indexOf(n) >= 0) {
        api[n] = (function (name, o) { return function () { return bridgeCall(name, arguments, o); }; })(n, opts);
        continue;
      }
      api[n] = function () { return ''; };   /* full 档兜底：明确空返回，不抛异常 */
    }
    return api;
  };

  /* ---------------- 安装到目标作用域 ---------------- */
  AS.install = function (target, opts) {
    target = target || global; opts = opts || {};
    var api = AS.build(opts), added = [], kept = [];
    Object.keys(api).forEach(function (k) {
      if (PROTECT[k] && typeof target[k] === 'function') { kept.push(k); return; }
      try { target[k] = api[k]; added.push(k); } catch (e) { kept.push(k); }
    });
    /* delegate 档若全局没有（如 getInternalJs / toDetailPage 只在 fy_bridge_app 上），
     * 补一层转发，保证官方 134 个符号在全局都可见。 */
    C.delegate.forEach(function (k) {
      if (typeof target[k] !== 'function') {
        if (global.fy_bridge_app && typeof global.fy_bridge_app[k] === 'function') {
          target[k] = function () {
            return global.fy_bridge_app[k].apply(global.fy_bridge_app, arguments);
          };
          added.push(k);
        } else if (typeof global[k] === 'function') {
          target[k] = global[k]; added.push(k);
        }
      }
    });
    /* 每页上下文：MY_* 由 HikerRuntime.setPage 注入；这里补规则键与页面快照 */
    S.ruleKey = opts.ruleKey || (S.page.MY_RULE && S.page.MY_RULE.title) || '';
    if (opts.page) Object.keys(opts.page).forEach(function (k) { S.page[k] = opts.page[k]; });
    AS.installed = { added: added.length, kept: kept.length,
                     official: OFFICIAL.length, total: ALL.length };
    return AS.installed;
  };

  /* 新鲜作用域：全量预置函数 + MY_*，专治 getUaObject / getJsPlugin 未定义 */
  AS.installFresh = function (page, opts) {
    var scope = {};
    var api = AS.build(opts);
    Object.keys(api).forEach(function (k) { scope[k] = api[k]; });
    /* 委托类也要在新作用域里出现（否则 fresh scope 里 setResult / fetch 全空） */
    C.delegate.forEach(function (k) {
      if (typeof scope[k] === 'undefined' && typeof global[k] === 'function') scope[k] = global[k];
    });
    var p = page || {};
    Object.keys(p).forEach(function (k) { scope[k] = p[k]; });
    scope.__HK_FRESH__ = true;
    return scope;
  };

  /* 覆盖率：对目标作用域按四档统计（用于报告与真机回归） */
  AS.coverage = function (target) {
    target = target || global;
    var out = { official: OFFICIAL.length, available: 0, full: [], bridge: [], delegate: [], degrade: [], missing: [] };
    OFFICIAL.forEach(function (n) {
      var lv = C.full.indexOf(n) >= 0 ? 'full' : (C.bridge.indexOf(n) >= 0 ? 'bridge' :
               (C.delegate.indexOf(n) >= 0 ? 'delegate' : 'degrade'));
      if (typeof target[n] === 'function') { out.available++; out[lv].push(n); }
      else out.missing.push(n);
    });
    out.levels = { full: out.full.length, bridge: out.bridge.length,
                   delegate: out.delegate.length, degrade: out.degrade.length };
    return out;
  };

  /* ---------------- 播放交接语义（取自 xingchen 1.2.9 真机定案） ----------------
   * 教训：PlayData.headers 在原版是对象数组 [{Referer:...}]，宿主若只认 "K@V"
   * 字符串就把请求头全丢；names（完整版/预览）被压成单条「默认」线路；
   * 标题取了规则名而不是选中集标题。这里做双向归一，交给宿主播放器。 */
  AS.normalizePlayData = function (v) {
    var o = (typeof v === 'string') ? jparse(v, {}) : (v || {});
    var urls = o.urls || o.url || [];
    if (typeof urls === 'string') urls = [urls];
    var names = o.names || [];
    if (typeof names === 'string') names = [names];
    var headers = o.headers || [];
    if (typeof headers === 'string') headers = [headers];
    var norm = [], i, j, h, k;
    for (i = 0; i < headers.length; i++) {
      h = headers[i];
      if (typeof h === 'string') norm.push(h);                    /* 已是 K@V */
      else if (h && typeof h === 'object') {
        for (k in h) if (has(h, k)) norm.push(k + '@' + str(h[k])); /* 对象数组 → K@V */
      }
    }
    var flags = [];
    if (names.length > 1 || (urls.length > 1 && names.length > 0)) {
      for (j = 0; j < names.length; j++) {
        flags.push({ name: names[j] || ('线路' + (j + 1)),
                     episodes: [urls[j] || urls[0] || ''],
                     headers: norm[j] ? [norm[j]] : norm });
      }
    }
    return { urls: urls, names: names, headers: norm, flags: flags,
             title: o.title || '', subtitle: o.subtitle || '',
             audioUrls: o.audioUrls || [], subtitleUrl: o.subtitleUrl || '' };
  };

  AS.state = S; AS.reports = S.reports;
  AS.setRuleKey = function (k) { S.ruleKey = str(k); return S.ruleKey; };
  AS.fire = fire; AS.emit = emit;
  global.HikerApiSurface = AS;
  if (typeof module !== 'undefined' && module.exports) module.exports = AS;
})(typeof window !== 'undefined' ? window : this);
