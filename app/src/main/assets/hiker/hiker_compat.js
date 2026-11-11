/*!
 * HikerCompat —— 海阔运行时「兼容性适配层」（构建前置产物 / 阶段六）
 *
 * 解决的问题：海阔规则形态多变，且历史上出现过 5 套互不兼容的宿主桥。
 * 本层不实现业务逻辑，只做四件事：
 *   1) 桥探测   probe()   —— 枚举 5 类桥，输出「能力表」，不猜测、不硬编码单一桥
 *   2) 语义归一 normalize() —— 任何桥的返回统一收敛为「同步字符串」，异步桥显式标记不可用
 *   3) 作用域隔离 install() —— 独立命名空间 + 可开关的全局注入，默认不覆盖宿主 window.fm / fetch
 *   4) 形态降级 classify()  —— 识别 Rhino / Python / 加密 / 同步网络等形态，给出明确降级策略
 *
 * 设计依据（均为用户此前提供的接口代码，见 interfaces/ 快照）：
 *   - hiker_54.db / home_js      : fy_bridge_app.fetch(同步字符串)、getInternalJs、refreshPage
 *   - v390 jar / fm-api.js       : FM_SHELL.call、FM_HOST.invoke、JsReqBridge.req(同步)、fongmiBridge(异步)
 *   - v390 jar / JsT4            : 37 个全局 API，但 $ 是 cheerio 风格、fetch 返回对象
 *   - wogg.js                    : window.fm / fongmiBridge，await 异步，返回 {ok,status,body}
 *   - oktv-cat / HomeWebBridge   : invoke() -> Task.execute 线程池 -> fongmiNative.resolve(异步 Promise)
 *   - hk_source / HkGateway      : Java 侧五处拦截，无 WebView 桥，属另一条移植路线
 *
 * 兼容性红线：宿主首页 SDK 把 window.fetch 变成异步 Promise，并把 window.fm 注册为异步对象；
 * 海阔规则用 eval.call(null, fetch(url)) 期望同步字符串。二者绝不能放进同一个 JS 上下文。
 * 因此 HikerCompat 只允许运行在「独立 WebView」，绝不注入首页 webOverlay。
 *
 * 语法基线：ES5 + Promise（Chromium 50~70）；不用 ?. / ?? / 箭头 / 模板串 / class / async。
 */
(function (global) {
  'use strict';

  var HC = {};

  /* ---------------- 1) 桥探测：5 类历史桥，逐一列能力 ---------------- */
  var BRIDGE_SPECS = [
    { id: 'HikerBridge', kind: 'sync', desc: '本模块独立 Activity 注入的同步 Java 桥（推荐）' },
    { id: 'FM_SHELL',    kind: 'sync', desc: 'v390 jar：FM_SHELL.call(name,payload) 同步字符串' },
    { id: 'FM_JSREQ',    kind: 'sync', desc: 'v390 jar：JsReqBridge.req 同步字符串' },
    { id: 'fy_bridge_app', kind: 'sync', desc: '海阔原版 WebView：fetch/getInternalJs 同步' },
    { id: 'fongmiBridge', kind: 'async', desc: 'oktv-cat / wogg：invoke + Promise（不可用于同步规则）' },
    { id: 'window.fm',   kind: 'async', desc: 'fm SDK：req() 返回 Promise（不可用于同步规则）' }
  ];

  function has(o, k) { return !!(o && typeof o[k] === 'function'); }

  HC.probe = function () {
    var found = [];
    var i, s, obj, present = false;
    for (i = 0; i < BRIDGE_SPECS.length; i++) {
      s = BRIDGE_SPECS[i];
      obj = null; present = false;
      if (s.id === 'HikerBridge')   { obj = global.HikerBridge;   present = has(global.HikerBridge, 'req') || has(global.HikerBridge, 'request'); }
      else if (s.id === 'FM_SHELL') { obj = global.FM_SHELL;      present = has(global.FM_SHELL, 'call'); }
      else if (s.id === 'FM_JSREQ') { obj = global.FM_JSREQ;      present = has(global.FM_JSREQ, 'req'); }
      else if (s.id === 'fy_bridge_app') { obj = global.fy_bridge_app; present = has(global.fy_bridge_app, 'fetch'); }
      else if (s.id === 'fongmiBridge') { obj = global.fongmiBridge;  present = has(global.fongmiBridge, 'invoke'); }
      else if (s.id === 'window.fm') { obj = global.fm;            present = has(global.fm, 'req'); }
      found.push({ id: s.id, kind: s.kind, present: present, desc: s.desc });
    }
    var syncUsable = [];
    for (i = 0; i < found.length; i++) if (found[i].present && found[i].kind === 'sync') syncUsable.push(found[i].id);
    return {
      bridges: found,
      syncUsable: syncUsable,
      asyncOnly: syncUsable.length === 0,
      hasHomeSdk: !!(global.fm || global.fongmi || global.fongmiNative),
      documentIsolated: !(global.document && global.document.documentElement &&
                          global.document.documentElement.classList &&
                          global.document.documentElement.classList.contains('fm-native'))
    };
  };

  /* ---------------- 2) 语义归一：任何返回都收敛成同步字符串 ---------------- */
  function bodyOf(o) {
    if (o === null || o === undefined) return '';
    if (typeof o === 'string') return o;
    if (typeof o === 'object') {
      if (typeof o.body === 'string') return o.body;
      if (typeof o.text === 'string') return o.text;
      if (typeof o.content === 'string') return o.content;
      if (typeof o.data === 'string') return o.data;
      try { return JSON.stringify(o); } catch (e) { return ''; }
    }
    return String(o);
  }

  // 明确的反例：Promise / thenable 绝不能被 String() 静默转成 "[object Promise]"
  HC.normalize = function (value) {
    if (value && typeof value.then === 'function') {
      return { ok: false, reason: 'async-promise', text: '' };
    }
    return { ok: true, reason: 'sync-string', text: bodyOf(value) };
  };

  /* ---------------- 3) 作用域隔离安装 ---------------- */
  // isolation: 'strict'  = 只挂 HikerRuntime / HikerCompat，不碰宿主全局（默认，推荐）
  // isolation: 'compat'  = 额外注入海阔必需的全局（fetch/request/$/pdfh…），仍需独立 WebView
  HC.install = function (opts) {
    opts = opts || {};
    var p = HC.probe();
    var report = {
      probe: p,
      mode: opts.isolation || 'strict',
      shim: !!global.HikerRuntime,
      jst4: !!global.jsoup || !!(global.$ && typeof global.$.load === 'function'),
      ready: false,
      warnings: []
    };
    if (p.hasHomeSdk && (opts.isolation || 'strict') === 'compat') {
      report.warnings.push('检测到宿主 fm SDK；compat 模式会覆盖 window.fetch，禁止在首页 WebView 使用');
    }
    if (p.asyncOnly) {
      report.warnings.push('仅检测到异步桥，海阔同步规则不可用；已降级为离线/显式报错，不静默假成功');
    }
    if (!global.HikerRuntime) {
      report.warnings.push('未检测到 hiker_runtime_shim.js，规则 API 不完整');
    }
    // 装配 shim（若存在）：把探测到的同步桥显式传入，避免 shim 自己误选异步桥
    if (global.HikerRuntime && typeof global.HikerRuntime.install === 'function') {
      var page = opts.page || {};
      global.HikerRuntime.install({
        page: page,
        offlineHtml: opts.offlineHtml || '',
        request: opts.request || null,
        onResult: opts.onResult || function () {}
      });
      report.shim = true;
      report.ready = true;
      report.bridge = global.HikerRuntime.bridge;
    }
    return report;
  };

  /* ---------------- 4) 形态识别与降级策略（针对「海阔多变」） ---------------- */
  var FORMS = [
    { id: 'sync-fetch',    re: /\bfetch\s*\(/,            strategy: '需同步字符串桥',           need: 'sync' },
    { id: 'sync-request',  re: /\brequest\s*\(/,          strategy: '需同步字符串桥',           need: 'sync' },
    { id: 'dom-pdfh',      re: /\bpdfh\s*\(/,             strategy: '需 JsT4 / DOM 解析垫片',   need: 'dom' },
    { id: 'dom-pdfa',      re: /\bpdfa\s*\(/,             strategy: '需 JsT4 / DOM 解析垫片',   need: 'dom' },
    { id: 'jquery-style',  re: /\$\.(require|fn)\b|\b(lazyRule|clickRule|x5cKey|x5rule)\b/, strategy: '$ 必须 jQuery 风格化', need: 'dom' },
    { id: 'lifecycle',     re: /\b(MY_URL|MY_PAGE|input|params|setResult|setHomeResult)\b/, strategy: '需每页变量注入与结果回传', need: 'runtime' },
    { id: 'hiker-scheme',  re: /hiker:\/\//,              strategy: '需独立页面栈路由',         need: 'runtime' },
    { id: 'crypto-shim',   re: /\b(md5|aes|des|rsa|base64)\b/, strategy: '需加解密垫片（JsT4 已提供，可跑）', need: 'dom' },
    { id: 'encrypted-rule', re: /\bevalPrivateJS\b|加密规则/, strategy: '私有加密规则，需 Java 侧 evalPrivateJS 引擎降级', need: 'unsupported' },
    { id: 'rhino',         re: /\b(JavaAdapter|Packages\.|importPackage|importClass)\b/, strategy: 'Rhino 专有，WebView 不可执行，需 QuickJS/Rhino 引擎降级', need: 'unsupported' },
    { id: 'python',        re: /\bpy\s*:|chaquo|python\s*:/, strategy: 'Python 规则，需 Chaquopy 引擎降级', need: 'unsupported' }
  ];

  HC.classify = function (code) {
    code = String(code || '');
    var forms = [], unsupported = [], i, f;
    for (i = 0; i < FORMS.length; i++) {
      f = FORMS[i];
      if (f.re.test(code)) {
        forms.push({ id: f.id, strategy: f.strategy, need: f.need });
        if (f.need === 'unsupported') unsupported.push(f.id);
      }
    }
    return {
      forms: forms,
      unsupported: unsupported,
      needsSyncBridge: code.indexOf('fetch(') >= 0 || code.indexOf('request(') >= 0,
      runnable: unsupported.length === 0,
      action: unsupported.length ? 'degrade-to-engine' : 'run-in-webview'
    };
  };

  /* ---------------- 5) 安全执行（严格校验，杜绝假阳性） ---------------- */
  HC.run = function (code, page, opts) {
    opts = opts || {};
    var c = HC.classify(code);
    var rec = { id: opts.id || null, runnable: c.runnable, action: c.action,
                forms: c.forms, ok: false, out: null, err: null };
    if (!c.runnable) {
      rec.err = 'unsupported-form: ' + c.unsupported.join(',');
      return rec;
    }
    if (!global.HikerRuntime) { rec.err = 'no-runtime-shim'; return rec; }
    try {
      global.HikerRuntime.setPage(page || {});
      global.HikerRuntime.lastResult = null;
      var scope = new Proxy({}, {
        has: function () { return true; },
        get: function (t, k) {
          if (typeof k === 'symbol') return undefined;
          if (k === 'window' || k === 'globalThis' || k === 'self') return global;
          return (k in global) ? global[k] : undefined;
        },
        set: function (t, k, v) { try { global[k] = v; } catch (e) {} return true; }
      });
      /* eslint-disable no-new-func */
      new Function('scope', 'with(scope){' + code + '\n}').call({}, scope);
      // 严格：lastResult 必须存在；若被赋成 Promise，判失败而非 String() 转换
      var r = global.HikerRuntime.lastResult;
      if (r !== null && r !== undefined && !(r && typeof r.then === 'function')) {
        rec.ok = true;
        try { rec.out = JSON.stringify(r).slice(0, 200); } catch (e) { rec.out = String(r).slice(0, 200); }
      } else {
        rec.err = r && typeof r.then === 'function' ? 'async-result-not-string' : 'no-setResult';
      }
    } catch (e) {
      rec.err = (e && e.name ? e.name + ': ' : '') + String(e && e.message || e).slice(0, 160);
    }
    return rec;
  };

  HC.version = '1.0.0';
  HC.bridgeSpecs = BRIDGE_SPECS;
  global.HikerCompat = HC;

  if (typeof module !== 'undefined' && module.exports) module.exports = HC;
})(typeof window !== 'undefined' ? window : this);
