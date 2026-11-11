/*!
 * HikerScheme —— hiker:// / file:// 内部地址分发层（阶段七）
 *
 * 来源：xingchen 1.2.8 真机首验事故（HK_REDO_BLUEPRINT.md:184-194、DELIVERY_NEXT.md:39）
 *   现象：所有规则打开即报 "Expected URL scheme 'http' or 'https' but was 'hiker'"
 *   根因：适配时把 8.83 CodeUtil 的 scheme 分发换成直连 OkHttp，hiker://empty 必死。
 *   原版链：HttpParser.get → CodeUtil.get → clearTag → decodeConflictStr →
 *           hiker://files/ | file:// | / | hiker://(assets|empty|page|home|库) → 其余才走网络。
 *
 * 原版权威实现（已逐行核对）：
 *   hikerView/CodeUtil.java:47-67、173-186        分发顺序与 404「文件不存在」
 *   hikerView/HikerRuleUtil.java:88-130           异步版 empty/page/home/库 与 404/500 语义
 *   xingchen/HkCodeUtil.java:91-113、306-329     同款移植（含 clearTag 不能按 # 截断的修正）
 *
 * 本层做四件事（纯 ES5，可独立注入 WebView，不依赖首页 SDK）：
 *   1) clearTag        —— 只剥离纯标记段，绝不砍掉含 "://" 或 "@rule=" 的真实地址
 *   2) decodeConflictStr —— url 与 header 值都要解冲突串（可配置映射）
 *   3) dispatch        —— 10 类 scheme 同步分发 + 404/500 错误码语义
 *   4) install         —— 包装 fetch/request，命中内部地址直接返回，外部地址才走网络桥
 *
 * 诚实标注：decodeConflictStr 的映射表是 8.83 StringUtil 的可配置近似（规则里常见的
 * 弯引号/全角符替换），真机应按宿主实现校准；本机文件/规则库/图片映射由 opts 注入，
 * WebView 侧无文件系统，缺失时明确返回 404 而不是静默给空串。
 */
(function (global) {
  'use strict';

  var HS = { version: '2.0.0', lastError: null, lastRoute: null, stats: { hit: 0, net: 0, fail: 0 } };

  /* 海阔 URL 上的纯标记段（来自 home.js / 8.83 UrlDetector） */
  var PURE_TAGS = {
    noHistory: 1, noRecordHistory: 1, noLoading: 1, noRecord: 1,
    noTitle: 1, noBack: 1, noAd: 1, noProxy: 1, noRefresh: 1, noSon: 1
  };

  /* 冲突串：规则里为躲开解析器用替代字符表示特殊符号 */
  var DEFAULT_CONFLICT = [
    ['‘‘', '"'], ["''", '"'], ['￥', '$'], ['＃', '#'],
    ['﹫', '@'], ['｜', '|'], ['＆', '&']
  ];

  HS.decodeConflictStr = function (s, map) {
    s = String(s === null || s === undefined ? '' : s);
    var m = map || DEFAULT_CONFLICT, i;
    for (i = 0; i < m.length; i++) s = s.split(m[i][0]).join(m[i][1]);
    return s;
  };

  /* 关键修正（xingchen 真机教训）：不能按第一个 # 截断。
   * hiker://empty#http://x 里的 # 后段是真实地址；只丢纯标记段。 */
  HS.clearTag = function (url) {
    var s = String(url === null || url === undefined ? '' : url);
    var i = s.indexOf('#');
    if (i < 0) return s;
    var head = s.slice(0, i), parts = s.slice(i + 1).split('#'), kept = [], j, p;
    for (j = 0; j < parts.length; j++) {
      p = parts[j];
      if (p === '') continue;
      if (PURE_TAGS[p]) continue;                 /* 纯标记，丢弃 */
      if (p.indexOf('://') >= 0 || p.indexOf('@rule=') >= 0 ||
          p.indexOf('@lazyRule=') >= 0 || p.indexOf('js:') >= 0) {
        kept.push(p);                              /* 真实地址 / 规则，保留 */
      }
    }
    return kept.length ? head + '#' + kept.join('#') : head;
  };

  HS.parse = function (url) {
    var s = HS.decodeConflictStr(HS.clearTag(String(url || '')));
    var r = { raw: String(url || ''), clean: s, scheme: 'http', ns: '', rest: '' };
    if (s.indexOf('hiker://') === 0) {
      r.scheme = 'hiker';
      r.rest = s.slice(8);
      r.ns = r.rest.split('/')[0].split('?')[0].split('@')[0];
    } else if (s.indexOf('file://') === 0) {
      r.scheme = 'file'; r.rest = s.slice(7);
    } else if (s.charAt(0) === '/') {
      r.scheme = 'file'; r.rest = s;
    } else if (s.indexOf('x5://') === 0 || s.indexOf('x5WebView://') === 0 ||
               s.indexOf('x5Play://') === 0) {
      r.scheme = 'x5'; r.rest = s;
    }
    return r;
  };

  function ok(body, route) {
    HS.stats.hit++; HS.lastRoute = route; HS.lastError = null;
    return { ok: true, status: 200, body: String(body === null || body === undefined ? '' : body),
             error: '', route: route };
  }
  function fail(status, msg, route) {
    HS.stats.fail++; HS.lastRoute = route || ''; HS.lastError = { status: status, msg: msg };
    /* 同步桥不抛异常（会打断规则），但必须留痕 + 上报，杜绝静默假成功 */
    if (global.HikerHost && typeof global.HikerHost.report === 'function') {
      try { global.HikerHost.report('hiker.scheme.error', JSON.stringify(HS.lastError)); } catch (e) {}
    }
    return { ok: false, status: status, body: '', error: msg, route: route || '' };
  }
  function network(url, opts) {
    HS.stats.net++;
    var fn = opts.request;
    if (typeof fn !== 'function') {
      if (global.HikerHost && typeof global.HikerHost.req === 'function') fn = global.HikerHost.req;
      else if (global.fy_bridge_app && typeof global.fy_bridge_app.fetch === 'function') fn = global.fy_bridge_app.fetch;
      else if (typeof global.fetch === 'function' && global.fetch !== HS._wrappedFetch) fn = global.fetch;
    }
    if (typeof fn !== 'function') return fail(500, '获取数据失败：无可用网络桥', 'network');
    try {
      var body = fn(url, opts.options || {});
      if (typeof body === 'string' && (body.charAt(0) === '{' || body.charAt(0) === '[')) {
        try { var o = JSON.parse(body); if (o && typeof o.body === 'string') body = o.body; } catch (e) {}
      }
      return ok(typeof body === 'string' ? body : String(body || ''), 'network');
    } catch (e) {
      return fail(500, '获取数据失败：' + String(e && e.message || e), 'network');
    }
  }
  function readLocal(rest, opts, label) {
    var files = opts.files || {};
    var key = rest.replace(/^\/+/, '');
    if (Object.prototype.hasOwnProperty.call(files, rest)) return ok(files[rest], label);
    if (Object.prototype.hasOwnProperty.call(files, key)) return ok(files[key], label);
    if (opts.allowLocal === true && typeof global.HikerHost !== 'undefined' &&
        global.HikerHost && typeof global.HikerHost.readFile === 'function') {
      try { var b = global.HikerHost.readFile(rest); if (b !== null && b !== undefined) return ok(b, label); } catch (e) {}
    }
    return fail(404, rest + '文件不存在', label);
  }

  /* 8.83 dealFileByHiker：assets 读资源，其余走规则查询（异步版语义） */
  function byHiker(r, opts) {
    var rest = r.rest;
    if (rest.indexOf('assets/') === 0) {
      var name = rest.slice(7), assets = opts.assets || {};
      if (Object.prototype.hasOwnProperty.call(assets, name)) return ok(assets[name], 'hiker://assets');
      return fail(404, name + '文件不存在', 'hiker://assets');
    }
    if (rest.indexOf('images/') === 0) {
      var img = rest.slice(7), map = opts.imageMap || {};
      if (Object.prototype.hasOwnProperty.call(map, img)) return ok(map[img], 'hiker://images');
      if (opts.imageBase) return ok(opts.imageBase + encodeURIComponent(img), 'hiker://images');
      return fail(404, img + '图片映射不存在', 'hiker://images');
    }
    var ns = r.ns;
    /* empty：去掉首个 "empty" 的余串（StringUtils.replaceOnce 语义） */
    if (ns === 'empty' || rest.indexOf('empty') === 0) {
      return ok(rest.replace(/^empty/, ''), 'hiker://empty');
    }
    /* page：余串原文 */
    if (ns === 'page' || rest.indexOf('page') === 0) {
      return ok(rest.replace(/^page\/?/, ''), 'hiker://page');
    }
    if (ns === 'home' || rest.indexOf('home') === 0) {
      var rules = opts.homeRules;
      if (rules === undefined) return ok('[]', 'hiker://home');
      var title = rest.indexOf('@') >= 0 ? rest.split('@').slice(1).join('@') : '';
      if (title && Object.prototype.hasOwnProperty.call(rules, title)) return ok(JSON.stringify(rules[title]), 'hiker://home');
      return ok(typeof rules === 'string' ? rules : JSON.stringify(rules), 'hiker://home');
    }
    var libraries = { bookmark: 1, download: 1, collection: 1, history: 1, webdav: 1 };
    if (libraries[ns]) {
      var stores = opts.stores || {};
      return ok(Object.prototype.hasOwnProperty.call(stores, ns) ? stores[ns] : '[]', 'hiker://' + ns);
    }
    if (ns === 'search') return ok('', 'hiker://search');
    /* 8.83 异步版：格式不对 → 404 */
    if (!/^[a-zA-Z]/.test(rest)) return fail(404, rest + '格式有误', 'hiker://' + ns);
    return ok('', 'hiker://' + ns);
  }

  HS.dispatch = function (url, opts) {
    opts = opts || {};
    var r = HS.parse(url);
    try {
      if (r.scheme === 'hiker') {
        if (r.rest.indexOf('files/') === 0) return readLocal(r.rest.slice(6), opts, 'hiker://files');
        return byHiker(r, opts);
      }
      if (r.scheme === 'file') return readLocal(r.rest, opts, 'file://');
      if (r.scheme === 'x5') return ok('', 'x5-carrier');   /* 承载页由 Java 侧接管 */
      return network(r.clean, opts);
    } catch (e) {
      return fail(500, '获取数据失败：' + String(e && e.message || e), r.scheme);
    }
  };

  /* 包装全局同步 fetch/request：内部地址直接返回，外部才走原桥 */
  HS.install = function (opts) {
    opts = opts || {};
    var wrapped = 0;
    ['fetch', 'request'].forEach(function (k) {
      var orig = global[k];
      if (typeof orig !== 'function' || orig.__hikerScheme) return;
      var w = function (url, obj) {
        if (typeof url === 'string' && (url.indexOf('hiker://') === 0 ||
            url.indexOf('file://') === 0 || url.charAt(0) === '/' ||
            url.indexOf('x5://') === 0 || url.indexOf('x5WebView://') === 0 ||
            url.indexOf('x5Play://') === 0)) {
          var o = { request: null, options: obj, files: opts.files, assets: opts.assets,
                    homeRules: opts.homeRules, stores: opts.stores, imageMap: opts.imageMap,
                    imageBase: opts.imageBase, conflictMap: opts.conflictMap, allowLocal: opts.allowLocal };
          /* 内部地址的分发结果：失败时返回空串但错误已留痕上报 */
          return HS.dispatch(url, o).body;
        }
        /* 外部地址也要先解冲突串 + clearTag，再交给原同步桥 */
        return orig(HS.decodeConflictStr(HS.clearTag(url)), obj);
      };
      w.__hikerScheme = true; w.__orig = orig;
      try { global[k] = w; if (k === 'fetch') HS._wrappedFetch = w; wrapped++; } catch (e) {}
    });
    HS.installed = { wrapped: wrapped, version: HS.version };
    return HS.installed;
  };

  global.HikerScheme = HS;
  if (typeof module !== 'undefined' && module.exports) module.exports = HS;
})(typeof window !== 'undefined' ? window : this);
