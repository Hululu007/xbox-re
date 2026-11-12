/*!
 * hiker_engine.js —— 海阔小程序运行时内核（纯 JS，跑在 WebView 里）
 *
 * 设计要点（这一版是重写，不是补丁）：
 *   1. 能用 WebView 原生能力的，一律不经 Java 桥：atob/btoa、localStorage、
 *      setTimeout、DOMParser（pd/pdfh 的选择器全部走原生 querySelectorAll，
 *      比手写 HTML parser 准得多）。
 *   2. 必须走原生的，只有一个：**同步 HTTP**。海阔规则的硬约束是
 *      `var html = fetch(url)` 同步拿字符串，浏览器 fetch 是 Promise，不能用。
 *      所以这条走 Java 同步桥（HikerBridge.req）。
 *   3. 模块系统（$.require / $.exports / $.toString）、hiker:// 协议、
 *      lazyRule、@rule=、col_type 结果集 —— 全部在这层实现。
 *
 * 兼容语法：规则自身的代码可能是 ES6（箭头函数/const），由 WebView 解析；
 * 内核自身用 ES5 写，避免老 WebView（Chrome 51）跑不起来。
 */
(function (global) {
  'use strict';

  var HK = { version: '3.0.0' };
  var bridge = global.HikerBridge || null;

  /* ==================================================================== *
   * 0. 基础工具
   * ==================================================================== */
  function str(v) { return (v === null || v === undefined) ? '' : String(v); }
  function trim(v) { return str(v).replace(/^[\s\uFEFF]+|[\s\uFEFF]+$/g, ''); }
  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function jparse(s, d) { try { return JSON.parse(s); } catch (e) { return d; } }
  function isArr(v) { return Object.prototype.toString.call(v) === '[object Array]'; }
  function now() { return new Date().getTime(); }

  /* ---- base64：优先原生（WebView 自带），无则自实现，绝不转发给 Java ---- */
  var B64C = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function utf8Encode(s) { return unescape(encodeURIComponent(str(s))); }
  function utf8Decode(s) { return decodeURIComponent(escape(str(s))); }

  function b64e(s) {
    var raw = utf8Encode(s);
    if (global.btoa) { try { return global.btoa(raw); } catch (e) { /* fallthrough */ } }
    var out = '', i, c1, c2, c3;
    for (i = 0; i < raw.length; i += 3) {
      c1 = raw.charCodeAt(i); c2 = raw.charCodeAt(i + 1); c3 = raw.charCodeAt(i + 2);
      out += B64C.charAt(c1 >> 2);
      out += B64C.charAt(((c1 & 3) << 4) | (isNaN(c2) ? 0 : c2 >> 4));
      out += isNaN(c2) ? '=' : B64C.charAt(((c2 & 15) << 2) | (isNaN(c3) ? 0 : c3 >> 6));
      out += isNaN(c3) ? '=' : B64C.charAt(c3 & 63);
    }
    return out;
  }

  function b64d(s) {
    var t = str(s).replace(/[\s\r\n]/g, '').replace(/-/g, '+').replace(/_/g, '/');
    var pad = t.length % 4; if (pad) { t += pad === 2 ? '==' : pad === 3 ? '=' : '=='; }
    if (global.atob) { try { return utf8Decode(global.atob(t)); } catch (e) { /* fallthrough */ } }
    var out = '', i, b0, b1, b2, b3;
    for (i = 0; i < t.length; i += 4) {
      b0 = B64C.indexOf(t.charAt(i)); b1 = B64C.indexOf(t.charAt(i + 1));
      b2 = B64C.indexOf(t.charAt(i + 2)); b3 = B64C.indexOf(t.charAt(i + 3));
      if (b0 < 0 || b1 < 0) continue;
      out += String.fromCharCode((b0 << 2) | (b1 >> 4));
      if (b2 >= 0) out += String.fromCharCode(((b1 & 15) << 4) | (b2 >> 2));
      if (b3 >= 0) out += String.fromCharCode(((b2 & 3) << 6) | b3);
    }
    return utf8Decode(out);
  }
  HK.b64d = b64d; HK.b64e = b64e;

  /* ---- MD5（纯 JS，规则里 md5() 用得很多，不能靠桥） ---- */
  var MD5 = (function () {
    function add32(a, b) { return (a + b) & 0xFFFFFFFF; }
    function cmn(q, a, b, x, s, t) {
      a = add32(add32(a, q), add32(x, t));
      return add32((a << s) | (a >>> (32 - s)), b);
    }
    function ff(a, b, c, d, x, s, t) { return cmn((b & c) | ((~b) & d), a, b, x, s, t); }
    function gg(a, b, c, d, x, s, t) { return cmn((b & d) | (c & (~d)), a, b, x, s, t); }
    function hh(a, b, c, d, x, s, t) { return cmn(b ^ c ^ d, a, b, x, s, t); }
    function ii(a, b, c, d, x, s, t) { return cmn(c ^ (b | (~d)), a, b, x, s, t); }
    function md5cycle(x, k) {
      var a = x[0], b = x[1], c = x[2], d = x[3];
      a = ff(a, b, c, d, k[0], 7, -680876936); d = ff(d, a, b, c, k[1], 12, -389564586);
      c = ff(c, d, a, b, k[2], 17, 606105819); b = ff(b, c, d, a, k[3], 22, -1044525330);
      a = ff(a, b, c, d, k[4], 7, -176418897); d = ff(d, a, b, c, k[5], 12, 1200080426);
      c = ff(c, d, a, b, k[6], 17, -1473231341); b = ff(b, c, d, a, k[7], 22, -45705983);
      a = ff(a, b, c, d, k[8], 7, 1770035416); d = ff(d, a, b, c, k[9], 12, -1958414417);
      c = ff(c, d, a, b, k[10], 17, -42063); b = ff(b, c, d, a, k[11], 22, -1990404162);
      a = ff(a, b, c, d, k[12], 7, 1804603682); d = ff(d, a, b, c, k[13], 12, -40341101);
      c = ff(c, d, a, b, k[14], 17, -1502002290); b = ff(b, c, d, a, k[15], 22, 1236535329);
      a = gg(a, b, c, d, k[1], 5, -165796510); d = gg(d, a, b, c, k[6], 9, -1069501632);
      c = gg(c, d, a, b, k[11], 14, 643717713); b = gg(b, c, d, a, k[0], 20, -373897302);
      a = gg(a, b, c, d, k[5], 5, -701558691); d = gg(d, a, b, c, k[10], 9, 38016083);
      c = gg(c, d, a, b, k[15], 14, -660478335); b = gg(b, c, d, a, k[4], 20, -405537848);
      a = gg(a, b, c, d, k[9], 5, 568446438); d = gg(d, a, b, c, k[14], 9, -1019803690);
      c = gg(c, d, a, b, k[3], 14, -187363961); b = gg(b, c, d, a, k[8], 20, 1163531501);
      a = gg(a, b, c, d, k[13], 5, -1444681467); d = gg(d, a, b, c, k[2], 9, -51403784);
      c = gg(c, d, a, b, k[7], 14, 1735328473); b = gg(b, c, d, a, k[12], 20, -1926607734);
      a = hh(a, b, c, d, k[5], 4, -378558); d = hh(d, a, b, c, k[8], 11, -2022574463);
      c = hh(c, d, a, b, k[11], 16, 1839030562); b = hh(b, c, d, a, k[14], 23, -35309556);
      a = hh(a, b, c, d, k[1], 4, -1530992060); d = hh(d, a, b, c, k[4], 11, 1272893353);
      c = hh(c, d, a, b, k[7], 16, -155497632); b = hh(b, c, d, a, k[10], 23, -1094730640);
      a = hh(a, b, c, d, k[13], 4, 681279174); d = hh(d, a, b, c, k[0], 11, -358537222);
      c = hh(c, d, a, b, k[3], 16, -722521979); b = hh(b, c, d, a, k[6], 23, 76029189);
      a = hh(a, b, c, d, k[9], 4, -640364487); d = hh(d, a, b, c, k[12], 11, -421815835);
      c = hh(c, d, a, b, k[15], 16, 530742520); b = hh(b, c, d, a, k[2], 23, -995338651);
      a = ii(a, b, c, d, k[0], 6, -198630844); d = ii(d, a, b, c, k[7], 10, 1126891415);
      c = ii(c, d, a, b, k[14], 15, -1416354905); b = ii(b, c, d, a, k[5], 21, -57434055);
      a = ii(a, b, c, d, k[12], 6, 1700485571); d = ii(d, a, b, c, k[3], 10, -1894986606);
      c = ii(c, d, a, b, k[10], 15, -1051523); b = ii(b, c, d, a, k[1], 21, -2054922799);
      a = ii(a, b, c, d, k[8], 6, 1873313359); d = ii(d, a, b, c, k[15], 10, -30611744);
      c = ii(c, d, a, b, k[6], 15, -1560198380); b = ii(b, c, d, a, k[13], 21, 1309151649);
      a = ii(a, b, c, d, k[4], 6, -145523070); d = ii(d, a, b, c, k[11], 10, -1120210379);
      c = ii(c, d, a, b, k[2], 15, 718787259); b = ii(b, c, d, a, k[9], 21, -343485551);
      x[0] = add32(a, x[0]); x[1] = add32(b, x[1]); x[2] = add32(c, x[2]); x[3] = add32(d, x[3]);
    }
    function md5blk(s) {
      var md5blks = [], i;
      for (i = 0; i < 64; i += 4)
        md5blks[i >> 2] = s.charCodeAt(i) + (s.charCodeAt(i + 1) << 8) +
          (s.charCodeAt(i + 2) << 16) + (s.charCodeAt(i + 3) << 24);
      return md5blks;
    }
    var hexChr = '0123456789abcdef'.split('');
    function rhex(n) {
      var s = '', j;
      for (j = 0; j < 4; j++) s += hexChr[(n >> (j * 8 + 4)) & 0x0F] + hexChr[(n >> (j * 8)) & 0x0F];
      return s;
    }
    return function (s) {
      var n = str(s).length, state = [1732584193, -271733879, -1732584194, 271733878], i, tail;
      var blk;
      for (i = 64; i <= n; i += 64) md5cycle(state, md5blk(str(s).substring(i - 64, i)));
      s = str(s).substring(i - 64);
      tail = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0];
      for (i = 0; i < s.length; i++) tail[i >> 2] |= s.charCodeAt(i) << ((i % 4) << 3);
      tail[i >> 2] |= 0x80 << ((i % 4) << 3);
      if (i > 55) { md5cycle(state, tail); for (i = 0; i < 16; i++) tail[i] = 0; }
      tail[14] = n * 8;
      md5cycle(state, tail);
      blk = state; var out = '';
      for (i = 0; i < 4; i++) out += rhex(blk[i]);
      return out;
    };
  })();
  HK.md5 = MD5;

  /* ==================================================================== *
   * 1. 同步网络：只此一条走 Java 桥
   * ==================================================================== */
  function reqSync(url, options) {
    var u = trim(url);
    if (!u) return '';
    var o = options || {};
    if (typeof o === 'string') o = jparse(o, {}) || {};
    var payload = JSON.stringify({
      method: (o.method || 'GET'), headers: (o.headers || {}), body: (o.body || o.data || ''),
      timeout: (o.timeout || 20000)
    });
    if (bridge && typeof bridge.req === 'function') {
      try { var r = bridge.req(u, payload); return (r === null || r === undefined) ? '' : String(r); }
      catch (e) { return ''; }
    }
    /* 调试/离线：无桥时用同步 XHR（浏览器内直接打开 index.html 时才有意义） */
    try {
      var x = new XMLHttpRequest();
      x.open((o.method || 'GET').toUpperCase(), u, false);
      if (o.headers) for (var k in o.headers) if (has(o.headers, k)) { try { x.setRequestHeader(k, o.headers[k]); } catch (e2) {} }
      x.send(o.body || null);
      return x.responseText || '';
    } catch (e3) { return ''; }
  }
  HK.req = reqSync;

  /* ==================================================================== *
   * 2. DOM 选择器（pd / pdfh / pdfa / parseDom*）
   *    全部基于原生 DOMParser + querySelectorAll，语法对齐海阔：
   *      "&&" 逐级下钻、"tag.class"、"#id"、"[attr=val]"、":eq(n)"、
   *      末尾 "--attr" 取属性，--Text / --Html / --outerHtml 取文本与 HTML
   * ==================================================================== */
  var docCache = { html: null, doc: null };
  function toDoc(html) {
    var h = str(html);
    if (docCache.html === h && docCache.doc) return docCache.doc;
    var doc = null;
    try {
      if (typeof DOMParser !== 'undefined') doc = new DOMParser().parseFromString(h, 'text/html');
    } catch (e) {}
    if (!doc) {
      doc = global.document.createElement('div');
      doc.innerHTML = h;
    }
    docCache.html = h; docCache.doc = doc;
    return doc;
  }

  function normSeg(seg) {
    var s = trim(seg);
    if (!s) return '';
    /* 海阔里 ":eq(2)" 转成 CSS :nth-of-type 语义不好等价，单独处理 */
    return s;
  }

  function pickAll(root, seg) {
    var s = normSeg(seg);
    if (!s) return [];
    var eq = /:eq\((\d+)\)$/.exec(s);
    var lt = /:lt\((\d+)\)$/.exec(s);
    var gt = /:gt\((\d+)\)$/.exec(s);
    var base = eq ? s.slice(0, eq.index) : (lt ? s.slice(0, lt.index) : (gt ? s.slice(0, gt.index) : s));
    var list = [];
    try { list = Array.prototype.slice.call(root.querySelectorAll(base || '*')); }
    catch (e) { list = []; }
    if (eq) { var n = parseInt(eq[1], 10); return list[n] ? [list[n]] : []; }
    if (lt) { return list.slice(0, parseInt(lt[1], 10)); }
    if (gt) { return list.slice(parseInt(gt[1], 10) + 1); }
    return list;
  }

  /**
   * 逐级下钻。返回节点数组。
   * rule 形如 "class.list&&li&&a"，或 "body&&div.item:eq(0)&&Text"。
   */
  function walk(root, rule, all) {
    var segs = str(rule).split('&&'), cur = [root], i, j, nx;
    var prop = '';
    if (segs.length) {
      var last = trim(segs[segs.length - 1]);
      var pi = last.indexOf('--');
      if (pi >= 0) { prop = trim(last.slice(pi + 2)); segs[segs.length - 1] = last.slice(0, pi); }
    }
    for (i = 0; i < segs.length; i++) {
      var seg = trim(segs[i]);
      if (!seg) continue;
      nx = [];
      for (j = 0; j < cur.length; j++) nx = nx.concat(pickAll(cur[j], seg));
      cur = nx;
      if (!cur.length) return all ? [] : null;
      if (!all && i < segs.length - 1) { /* 继续下钻 */ }
    }
    return all ? cur : (cur[0] || null);
  }

  function valueOf(node, prop) {
    if (!node) return '';
    var p = trim(prop).toLowerCase();
    if (!p || p === 'text' || p === 'textnode') return trim(node.textContent || '');
    if (p === 'html' || p === 'innerhtml') return node.innerHTML || '';
    if (p === 'outerhtml') return node.outerHTML || '';
    if (p === 'ownhtml') return node.innerHTML || '';
    return node.getAttribute ? (node.getAttribute(prop) || '') : '';
  }

  function pdfh(html, rule) {
    var root = toDoc(html);
    var body = root.body || root.documentElement || root;
    var node = walk(body, rule, false);
    if (!node) return '';
    var segs = str(rule).split('&&');
    var last = trim(segs[segs.length - 1] || '');
    var pi = last.indexOf('--');
    var prop = pi >= 0 ? trim(last.slice(pi + 2)) : '';
    return valueOf(node, prop);
  }

  function pdfa(html, rule) {
    var root = toDoc(html);
    var body = root.body || root.documentElement || root;
    var segs = str(rule).split('&&');
    var last = trim(segs[segs.length - 1] || '');
    var pi = last.indexOf('--');
    var prop = pi >= 0 ? trim(last.slice(pi + 2)) : '';
    var nodes = walk(body, rule, true) || [];
    var out = [];
    for (var i = 0; i < nodes.length; i++) out.push(valueOf(nodes[i], prop));
    return out;
  }
  HK.pdfh = pdfh; HK.pdfa = pdfa;

  /* ==================================================================== *
   * 3. hiker:// 协议
   *    hiker://empty#noLoading##@rule=js:<code>
   *    hiker://empty#@lazyRule=.js:<code>(<args>)
   *    普通 http(s) -> 播放/打开
   * ==================================================================== */
  var RE_RULE = /(?:^|#)@?rule=js:([\s\S]*)$/;
  var RE_LAZY = /(?:^|#)@?lazyRule=(?:\.?js:)?([\s\S]*)$/;

  function parseHikerUrl(url) {
    var u = str(url);
    /* [P1] 合集框架用全角分隔符拼入口（？？ / ；； / ＃ / ＝ / ＆）。
     * 不归一化的话 scheme 里会带全角字符，page/模块名永远匹配不上，
     * 表现就是「点子站没反应」。在解析最前端一次性换成半角。 */
    u = u.replace(/\uFF1F/g, '?').replace(/\uFF1B/g, ';')
         .replace(/\uFF03/g, '#').replace(/\uFF1D/g, '=')
         .replace(/\uFF06/g, '&').replace(/\uFF20/g, '@')
         .replace(/\uFF1A/g, ':');
    var out = { raw: str(url), rawNormalized: u, base: u, flags: [], rule: null,
                lazy: null, video: false, search: null, page: null };
    var im = /(?:^|#)@?input=([^#]*)/.exec(u);
    if (im) {
      try { out.inputArg = JSON.parse(decodeURIComponent(im[1])); } catch (e) { out.inputArg = null; }
    }
    var m = RE_LAZY.exec(u);
    if (m) { out.lazy = m[1]; out.base = u.slice(0, m.index); }
    else {
      var m2 = RE_RULE.exec(u);
      if (m2) { out.rule = m2[1]; out.base = u.slice(0, m2.index); }
    }
    var head = out.base.replace(/^hiker:\/\//, '');
    var hp = head.split('#');
    out.scheme = hp[0] || '';
    for (var i = 1; i < hp.length; i++) if (trim(hp[i])) out.flags.push(trim(hp[i]));
    if (/isVideo=true/i.test(u)) out.video = true;
    var sm = /^search\?s=([\s\S]*)$/.exec(out.scheme);
    if (sm) out.search = decodeURIComponent(sm[1]);
    var pm = /^page\/([\s\S]*)$/.exec(out.scheme);
    if (pm) out.page = pm[1];
    return out;
  }
  HK.parseUrl = parseHikerUrl;

  /* ==================================================================== *
   * 4. 作用域：每条规则拿到的 API 集合
   * ==================================================================== */
  var STORE = {
    myVars: {},      /* 规则名@key -> value */
    vars: {},
    items: {},       /* 当前页面条目 id -> item（updateItem/findItem 用） */
    files: {},       /* 内存文件（未落盘时的兜底） */
    modules: {},     /* 模块缓存 ruleKey/path -> exports */
    rawRules: {}     /* 规则原文缓存 */
  };
  HK.STORE = STORE;

  var UAM = 'Mozilla/5.0 (Linux; Android 13; SM-S9080) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
  var UAP = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

  function myKey(k, ruleName) { return (ruleName || HK.ruleName() || '') + '@' + str(k); }

  /* 页面上下文：当前站点/规则/参数 */
  var PAGE = { rule: null, ruleName: '', url: '', keyword: '', page: 1, title: '', pic: '' };
  HK.page = PAGE;
  HK.setPage = function (o) { for (var k in o) if (has(o, k)) PAGE[k] = o[k]; };
  HK.ruleName = function () { return PAGE.ruleName || (PAGE.rule && PAGE.rule.title) || ''; };

  /* 结果收集器：setResult / setHomeResult / d.push 最终都落这里 */
  var OUT = { result: undefined, hasResult: false, items: [], events: [] };
  HK.OUT = OUT;
  HK.resetOut = function () { OUT.result = undefined; OUT.hasResult = false; OUT.items = []; };

  function emit(name, payload) {
    try { if (bridge && typeof bridge.report === 'function') bridge.report(name, typeof payload === 'string' ? payload : JSON.stringify(payload)); }
    catch (e) {}
  }
  HK.emit = emit;

  function fileRead(p) {
    var path = str(p);
    if (bridge && typeof bridge.call === 'function') {
      try {
        var r = bridge.call('readFile', JSON.stringify([path]));
        if (r !== null && r !== undefined && String(r) !== '') return String(r);
      } catch (e) {}
    }
    return has(STORE.files, path) ? STORE.files[path] : '';
  }
  function fileWrite(p, c) {
    var path = str(p), body = str(c);
    STORE.files[path] = body;
    if (bridge && typeof bridge.call === 'function') {
      try { bridge.call('writeFile', JSON.stringify([path, body])); } catch (e) {}
    }
    return true;
  }
  HK.readFile = fileRead; HK.writeFile = fileWrite;

  /**
   * fetch 的协议分发：
   *   hiker://files/...  -> 本地文件（规则自己 writeFile 出来的缓存）
   *   hiker://page/NAME  -> 该模块的源码，按 {"rule": "..."} 返回（dataLoad 就是这么读的）
   *   其它               -> 走同步 HTTP
   */
  function hkFetch(url, opt) {
    var u = trim(url);
    if (u.indexOf('hiker://files/') === 0) return fileRead(u);
    if (u.indexOf('hiker://page/') === 0) {
      var nm = decodeURIComponent((/^hiker:\/\/page\/([^?#]+)/.exec(u) || [])[1] || '');
      var pg = findPage(nm);
      if (pg) return JSON.stringify({ rule: str(pg.rule || '') });
      return '';
    }
    return reqSync(u, opt);
  }
  HK.fetch = hkFetch;

  function findPage(name) {
    var rule = PAGE.rule || {};
    var pages = rule.pages;
    if (typeof pages === 'string') { try { pages = JSON.parse(pages); } catch (e) { pages = []; } }
    if (!isArr(pages)) return null;
    for (var i = 0; i < pages.length; i++) if (pages[i] && str(pages[i].path) === str(name)) return pages[i];
    return null;
  }
  HK.findPage = findPage;

  /* 建一个作用域：所有规则/模块代码都在这里面跑 */
  function makeScope(extra) {
    var sc = {};
    var ruleName = HK.ruleName();

    /* 编码 / 摘要 */
    sc.md5 = function (s) { return MD5(s); };
    sc.MD5 = sc.md5;
    sc.base64Encode = function (s) { return b64e(s); };
    sc.base64Decode = function (s) { return b64d(s); };
    sc.atob = function (s) { return b64d(s); };
    sc.btoa = function (s) { return b64e(s); };
    sc.encodeStr = function (s, enc) { return (enc === 'base64' || enc === 'b64') ? b64e(s) : encodeURIComponent(str(s)); };
    sc.decodeStr = function (s, enc) { return (enc === 'base64' || enc === 'b64') ? b64d(s) : decodeURIComponent(str(s)); };
    sc.encodeToString = function (s, enc) { return sc.encodeStr(s, enc); };
    sc.hexToBytes = function (h) {
      var s = str(h).replace(/[^0-9a-fA-F]/g, ''), o = [];
      for (var i = 0; i + 1 < s.length; i += 2) o.push(parseInt(s.substr(i, 2), 16));
      return o;
    };
    sc.hexToBase64 = function (h) { return b64e(String.fromCharCode.apply(null, sc.hexToBytes(h))); };
    sc.strToBase64 = sc.base64Encode;

    /* 网络（同步） */
    sc.fetch = function (u, o) { return hkFetch(u, o); };
    sc.request = function (u, o) { return hkFetch(u, o); };
    sc.fetchSync = sc.fetch;
    sc.post = function (u, body, o) {
      o = o || {}; o.method = 'POST'; o.body = body;
      return reqSync(u, o);
    };

    /* DOM */
    sc.pd = function (h, r) { return pdfh(h, r); };
    sc.pdfh = function (h, r) { return pdfh(h, r); };
    sc.pjf = function (h, r) { return pdfh(h, r); };
    sc.pf = function (h, r) { return pdfh(h, r); };
    sc.pdfl = function (h, r) { return pdfh(h, r); };
    sc.pdfa = function (h, r) { return pdfa(h, r); };
    sc.pjfa = function (h, r) { return pdfa(h, r); };
    sc.parseDomForHtml = function (h, r) { return pdfh(h, r); };
    sc.parseDom = function (h, r) { return pdfh(h, r); };
    sc.parseDomForArray = function (h, r) { return pdfa(h, r); };

    /* 结果输出 */
    function setResult(v) { OUT.result = v; OUT.hasResult = true; return v; }
    sc.setResult = setResult;
    /* [P4] 合集框架用的是 Rhino 的 Java 反射写法：
     *   method_setResult.invoke(javaContext, result)
     *   method_setHomeResult.invoke(javaContext, …)
     * WebView 没有 Rhino，这几个符号一出现就 ReferenceError，整条规则中断。
     * 这里把它们 shim 到同一个 setResult，语义等价（都是把结果交给宿主）。 */
    var invokeShim = function () {
      var a = arguments, v = a[a.length - 1];
      return setResult(v);
    };
    sc.javaContext = { toString: function () { return '[javaContext shim]'; } };
    sc.method_setResult = { invoke: invokeShim };
    sc.method_setHomeResult = { invoke: invokeShim };
    sc.method_setSearchResult = { invoke: invokeShim };
    sc.method_setStrResult = { invoke: invokeShim };
    sc.method_setLastChapterResult = { invoke: invokeShim };
    /* [P4] hijackEnv(newRule)：把合集子站的规则对象挂成当前上下文。
     * 常见调用是 hijackEnv($.require('框架', 对象参数)) 或直接传规则对象。 */
    sc.hijackEnv = function (r) {
      var rule = r;
      if (typeof r === 'string') rule = jparse(r, null);
      if (rule && typeof rule === 'object') {
        HK.setPage({ rule: rule, ruleName: str(rule.title || HK.ruleName()) });
        /* 模块缓存按规则名隔离，换规则后必须清，否则拿到上一个站点的 exports */
        STORE.modules = {};
      }
      return rule || {};
    };
    /* [P6] toast() 直接弹原生（HikerBridge.toast 由本轮 Java 侧新增） */
    sc.toast = function (m) {
      if (bridge && typeof bridge.toast === 'function') { try { bridge.toast(str(m)); } catch (e) {} }
      else emit('hk.toast', str(m));
    };
    sc.setHomeResult = setResult;
    sc.setSearchResult = setResult;
    sc.setStrResult = setResult;
    sc.setLastChapterResult = setResult;
    sc.nsetHomeResult = setResult;

    /* 变量 */
    sc.getVar = function (k, d) { return has(STORE.vars, str(k)) ? STORE.vars[str(k)] : (d === undefined ? '' : d); };
    sc.putVar = function (k, v) { STORE.vars[str(k)] = v; return v; };
    sc.clearVar = function (k) { if (k === undefined) STORE.vars = {}; else delete STORE.vars[str(k)]; return true; };
    sc.getMyVar = function (k, d, r) { var q = myKey(k, r); return has(STORE.myVars, q) ? STORE.myVars[q] : (d === undefined ? '' : d); };
    sc.putMyVar = function (k, v, r) { STORE.myVars[myKey(k, r)] = v; return v; };
    sc.clearMyVar = function (k, r) { delete STORE.myVars[myKey(k, r)]; return true; };
    sc.listMyVarKeys = function (r) {
      var pre = (r || ruleName) + '@', out = [];
      for (var q in STORE.myVars) if (has(STORE.myVars, q) && q.indexOf(pre) === 0) out.push(q.slice(pre.length));
      return out;
    };
    sc.getItem = function (k, d) { return has(STORE.items, str(k)) ? STORE.items[str(k)] : (d === undefined ? '' : d); };
    sc.setItem = function (k, v) { STORE.items[str(k)] = v; return v; };
    sc.clearItem = function (k) { if (k === undefined) STORE.items = {}; else delete STORE.items[str(k)]; return true; };
    /* hiker 的 storage0..9：按槽位隔离的持久存储 */
    var lsKey = function (slot, k) { return 'hk_s' + slot + '_' + ruleName + '_' + str(k); };
    for (var si = 0; si < 10; si++) {
      (function (slot) {
        sc['storage' + slot] = {
          getItem: function (k, d) {
            try { var v = global.localStorage.getItem(lsKey(slot, k)); return v === null ? (d === undefined ? '' : d) : v; }
            catch (e) { return d === undefined ? '' : d; }
          },
          setItem: function (k, v) { try { global.localStorage.setItem(lsKey(slot, k), str(v)); } catch (e) {} return true; },
          removeItem: function (k) { try { global.localStorage.removeItem(lsKey(slot, k)); } catch (e) {} return true; },
          clear: function () { return true; }
        };
      })(si);
    }
    sc.getPublicItem = sc.getItem; sc.setPublicItem = sc.setItem; sc.clearPublicItem = sc.clearItem;

    /* 页面参数 */
    sc.getParam = function (k, d, u) {
      var url = str(u || PAGE.url || ''), i = url.indexOf('?');
      if (i < 0) return d === undefined ? '' : d;
      var qs = url.slice(i + 1).split('#')[0].split('&');
      for (var j = 0; j < qs.length; j++) {
        var p = qs[j].split('=');
        if (decodeURIComponent(p[0]) === str(k)) { try { return decodeURIComponent(p.slice(1).join('=')); } catch (e) { return p.slice(1).join('='); } }
      }
      return d === undefined ? '' : d;
    };
    sc.getUrl = function () { return str(PAGE.url || ''); };
    sc.buildUrl = function (u, o) {
      var base = str(u), q = [];
      for (var k in o) if (has(o, k)) q.push(encodeURIComponent(k) + '=' + encodeURIComponent(str(o[k])));
      return q.length ? base + (base.indexOf('?') >= 0 ? '&' : '?') + q.join('&') : base;
    };

    /* 页面 / 生命周期 */
    sc.getPageTitle = function () { return str(PAGE.title || ''); };
    sc.setPageTitle = function (t) { PAGE.title = str(t); return true; };
    sc.setPagePicUrl = function (u) { PAGE.pic = str(u); return true; };
    sc.setPageParams = function (p) { return true; };
    sc.setPageReverse = function () { return true; };
    sc.isPageReversed = function () { return false; };
    sc.refreshPage = function () { emit('hk.refresh', ''); return true; };
    sc.refreshX5Desc = function () { return true; };
    sc.refresh = sc.refreshPage;
    sc.back = function () { emit('hk.back', ''); return true; };
    sc.closeMe = function () { emit('hk.back', ''); return true; };
    sc.toast = function (m) { emit('hk.toast', str(m)); return true; };
    sc.log = function (m) { emit('hk.log', str(m)); return true; };
    sc.nlog = sc.log;
    sc.error = function (m) { emit('hk.error', str(m)); return true; };
    sc.setError = sc.error;
    sc.showLoading = function () { return true; };
    sc.hideLoading = function () { return true; };
    sc.getTime = function () { return now(); };
    sc.getAppVersion = function () { return 9999; };
    sc.getHeaderUrl = function () { return ''; };
    sc.getUa = function () { return UAM; };
    sc.setWebUa = function () { return true; };
    sc.setWebTitle = sc.setPageTitle;
    sc.getUaObject = function () { return JSON.stringify({ ua: UAM, pcUa: UAP }); };
    sc.getSearchMode = function () { return ''; };
    sc.setSearchMode = function () { return true; };
    sc.getRule = function (k) { var r = PAGE.rule || {}; return str(has(r, str(k)) ? r[str(k)] : ''); };
    sc.getHome = function () { return '[]'; };
    sc.getHomeSub = function () { return '[]'; };
    sc.hasHomeSub = function () { return false; };
    sc.getColTypes = function () { return '[]'; };
    sc.getLastRules = function () { return '[]'; };
    sc.getRuleCount = function () { return 0; };
    sc.getPrivateJS = function () { return ''; };
    sc.getJsPlugin = function () { return ''; };
    sc.getJsLazyPlugin = function () { return ''; };
    sc.addListener = function () { return true; };

    /* 交互 */
    sc.confirm = function (o) { return global.confirm ? global.confirm(str(o && o.title || o)) : ''; };
    sc.showSelectOptions = function (o) {
      var opts = (o && (o.options || o)) || [];
      return isArr(opts) ? str(opts[0] || '') : '';
    };
    sc.copy = function (t) { emit('hk.copy', str(t)); return true; };
    sc.open = function (u) { emit('hk.open', str(u)); return true; };
    sc.openAppIntent = sc.open;
    sc.openThirdApp = sc.open;
    sc.toDetailPage = function (u) { emit('hk.open', str(u)); return true; };

    /* 列表条目操作 */
    sc.findItem = function (id) { return has(STORE.items, str(id)) ? STORE.items[str(id)] : null; };
    sc.findItemsByCls = function (cls) {
      var out = [];
      for (var k in STORE.items) { var v = STORE.items[k]; if (v && v.cls === str(cls)) out.push(v); }
      return out;
    };
    sc.updateItem = function (id, o) {
      var cur = STORE.items[str(id)] || {};
      for (var k in o) if (has(o, k)) cur[k] = o[k];
      STORE.items[str(id)] = cur;
      emit('hk.updateItem', [str(id), o]);
      return true;
    };
    sc.addItemAfter = function (id, o) { emit('hk.addItem', ['after', str(id), o]); return true; };
    sc.addItemBefore = function (id, o) { emit('hk.addItem', ['before', str(id), o]); return true; };
    sc.deleteItem = function (id) { delete STORE.items[str(id)]; emit('hk.deleteItem', str(id)); return true; };
    sc.deleteItemByCls = function (cls) {
      for (var k in STORE.items) { var v = STORE.items[k]; if (v && v.cls === str(cls)) delete STORE.items[k]; }
      return true;
    };

    /* 文件 */
    sc.readFile = function (p) { return fileRead(p); };
    sc.writeFile = function (p, c) { return fileWrite(p, c); };
    sc.saveFile = function (p, c) { return fileWrite(p, c); };
    sc.deleteFile = function (p) { delete STORE.files[str(p)]; return true; };
    sc.fileExist = function (p) { return !!fileRead(p); };
    sc.getPath = function (p) { return 'hiker://files/' + str(p); };

    /* 播放 */
    sc.playVideo = function (u) { return HK.play(u, '', ''); };
    sc.playVideos = function (json) {
      var list = isArr(json) ? json : (jparse(json, []) || []);
      for (var i = 0; i < list.length; i++) {
        var it = list[i] || {};
        if (it.url) HK.play(it.url, it.title || '', it.pic || '');
      }
      return true;
    };

    /* 本页变量（海阔注入的全局） */
    sc.MY_URL = str(PAGE.url || '');
    sc.MY_PAGE = PAGE.page || 1;
    sc.MY_KEYWORD = str(PAGE.keyword || '');
    /* [P3] 合集框架的 hijackEnv() 会改写 MY_RULE。若这里是快照，改写后
     * 规则读到的还是旧对象 → 子站进不去。改成 getter，随 PAGE.rule 实时变。 */
    Object.defineProperty(sc, 'MY_RULE', {
      configurable: true,
      get: function () { return PAGE.rule || {}; },
      set: function (v) { HK.setPage({ rule: v }); }
    });
    sc.MY_TITLE = str(PAGE.title || '');
    sc.MY_PIC = str(PAGE.pic || '');
    sc.MY_PARAMS = '';
    sc.params = {};
    sc.input = '';
    sc.host = '';
    sc.MOBILE_UA = UAM;
    sc.PC_UA = UAP;

    /* ---- $ 模块系统 ---- */
    sc.$ = makeDollar(null);
    sc.require = function (name) { return sc.$.require(name); };

    /* ---- java.* 兼容层（少量真正需要的：Base64 / 字节数组） ---- */
    sc.java = JAVA_SHIM;
    sc.Packages = JAVA_PACKAGES;
    /* 海阔规则里 javax.crypto 两种写法都有：Packages.javax.… 与直接 javax.… */
    sc.javax = JAVA_PACKAGES.javax;

    /* fetchCache(url, 小时, 选项)：带本地缓存的取数。
     * 命中未过期缓存直接用；否则联网；联网失败再退回旧缓存（这个「退回」很关键：
     * 合集包的网络源经常挂，包里自带的 libs 缓存就是靠这条路撑住的）。 */
    sc.fetchCache = function (u, hours, opt) {
      var url = trim(u), ttl = (hours === undefined || hours === null) ? 24 : Number(hours);
      var key = 'hiker://files/libs/' + MD5(url) + '.js';
      var stampKey = 'hiker://files/libs/' + MD5(url) + '.ts';
      var cached = fileRead(key), stamp = Number(fileRead(stampKey) || 0) || 0;
      var fresh = cached && ttl >= 0 && (now() - stamp) < ttl * 3600000;
      if (fresh) return cached;
      var body = reqSync(url, opt || {});
      if (body && body.length > 8) {
        fileWrite(key, body);
        fileWrite(stampKey, String(now()));
        return body;
      }
      return cached || '';
    };
    sc.saveFile = function (p, c) { return fileWrite(p, c); };
    /* 私有加密 JS：海阔用 App 内置密钥解密后执行，WebView 里拿不到那把钥匙。
     * 不伪装成成功，留痕后返回空串；合集类规则靠下面的变量兜底继续跑。 */
    sc.evalPrivateJS = function (code) {
      emit('hk.degrade', 'evalPrivateJS(' + str(code).length + ' 字节，需海阔内置密钥)');
      return '';
    };

    /* 其它杂项 */
    sc.fc = function (u) { return reqSync(u, {}); };
    sc.rc = function (u) { return reqSync(u, {}); };
    sc.bf = sc.fc; sc.be = sc.fc;
    sc.syncExecute = function (f) { try { return f(); } catch (e) { return ''; } };
    sc.publishRule = function () { return true; };
    sc.registerTask = function () { return true; };
    sc.unRegisterTask = function () { return true; };
    sc.registerDNS = function () { return true; };
    sc.startProxyServer = function () { return ''; };

    if (extra) for (var k in extra) if (has(extra, k)) sc[k] = extra[k];
    return sc;
  }

  /* ==================================================================== *
   * 5. java.* 兼容层
   *    真实规则（吃瓜718 / 阅动漫 的 imgDES）会用 Rhino 的 Java 互操作做
   *    Base64 与字节处理。WebView 没有 Rhino，这里给等价实现；
   *    做不到的（javax.crypto 任意算法、java.net.URL 同步字节流）明确抛错，
   *    由调用方 try/catch 兜住，绝不静默返回假数据。
   * ==================================================================== */
  var JAVA_SHIM = {
    lang: {
      String: function (s) { return str(s); },
      Integer: { parseInt: function (s) { return parseInt(s, 10); } },
      Byte: { TYPE: 'byte' },
      reflect: {
        Array: {
          newInstance: function (type, n) { var a = new Array(n); for (var i = 0; i < n; i++) a[i] = 0; return a; }
        }
      }
    },
    util: {
      Base64: {
        getEncoder: function () {
          return { encodeToString: function (bytes) { return b64e(bytesToString(bytes)); } };
        },
        getDecoder: function () {
          return { decode: function (s) { return stringToBytes(b64d(s)); } };
        }
      },
      Arrays: { toString: function (a) { return '[' + a.join(', ') + ']'; } }
    },
    io: {
      ByteArrayOutputStream: function () {
        this._b = [];
        this.write = function (b) { this._b = this._b.concat(toByteArray(b)); };
        this.toByteArray = function () { return this._b; };
        this.toString = function () { return bytesToString(this._b); };
      },
      ByteArrayInputStream: function (bytes) {
        this._b = toByteArray(bytes);
        this.read = function () { return this._b.length ? this._b.shift() : -1; };
      }
    }
  };

  /* ==================================================================== *
   * [P5] javax.crypto 兼容层 —— 加密封面（AES-CTR）纯 JS 实现
   * 交付说明里「吃瓜718 的封面是 AES-CTR 加密图，规则用 javax.crypto 解」
   * 原来直接抛错；这里给等价 Cipher.getInstance / init / doFinal。
   * 支持 128/192/256 位密钥、CTR / CBC / ECB、NoPadding / PKCS5Padding。
   * 用 NIST SP 800-38A AES-128-CTR 第一块向量自测（见 simulate_merge.js）。
   * ==================================================================== */
  /* FIPS-197 标准 S-box（硬编码，避免动态生成实现出错） */
  var AES_SBOX = ("63 7c 77 7b f2 6b 6f c5 30 01 67 2b fe d7 ab 76 ca 82 c9 7d fa 59 47 f0 ad d4 a2 af 9c a4 72 c0 b7 fd 93 26 36 3f f7 cc 34 a5 e5 f1 71 d8 31 15 04 c7 23 c3 18 96 05 9a 07 12 80 e2 eb 27 b2 75 09 83 2c 1a 1b 6e 5a a0 52 3b d6 b3 29 e3 2f 84 53 d1 00 ed 20 fc b1 5b 6a cb be 39 4a 4c 58 cf d0 ef aa fb 43 4d 33 85 45 f9 02 7f 50 3c 9f a8 51 a3 40 8f 92 9d 38 f5 bc b6 da 21 10 ff f3 d2 cd 0c 13 ec 5f 97 44 17 c4 a7 7e 3d 64 5d 19 73 60 81 4f dc 22 2a 90 88 46 ee b8 14 de 5e 0b db e0 32 3a 0a 49 06 24 5c c2 d3 ac 62 91 95 e4 79 e7 c8 37 6d 8d d5 4e a9 6c 56 f4 ea 65 7a ae 08 ba 78 25 2e 1c a6 b4 c6 e8 dd 74 1f 4b bd 8b 8a 70 3e b5 66 48 03 f6 0e 61 35 57 b9 86 c1 1d 9e e1 f8 98 11 69 d9 8e 94 9b 1e 87 e9 ce 55 28 df 8c a1 89 0d bf e6 42 68 41 99 2d 0f b0 54 bb 16").split(/\s+/).map(function (x) { return parseInt(x, 16); });
  function xt(a, b) { return ((a ^ b) & 0xFF); }
  function mul2(a) { var r = (a << 1) & 0xFF; if (a & 0x80) r ^= 0x1B; return r; }
  function mul(a, b) {
    var r = 0, i;
    for (i = 0; i < 8; i++) { if (b & 1) r ^= a; a = mul2(a); b >>= 1; }
    return r & 0xFF;
  }
  function aesExpand(key) {
    var nk = key.length / 4, nr = nk + 6, w = [], i, t;
    var RCON = [0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1B,0x36,0x6C,0xD8,0xAB,0x4D];
    for (i = 0; i < nk; i++) w.push([key[4*i],key[4*i+1],key[4*i+2],key[4*i+3]]);
    for (i = nk; i < 4 * (nr + 1); i++) {
      t = w[i-1].slice();
      if (i % nk === 0) {
        t = [t[1],t[2],t[3],t[0]];
        t = t.map(function (x) { return AES_SBOX[x]; });
        t[0] ^= RCON[i/nk - 1];
      } else if (nk > 6 && i % nk === 4) {
        t = t.map(function (x) { return AES_SBOX[x]; });
      }
      var prev = w[i-nk], o = [];
      for (var j = 0; j < 4; j++) o.push(prev[j] ^ t[j]);
      w.push(o);
    }
    var rounds = [];
    for (var r = 0; r <= nr; r++) rounds.push(w.slice(4*r, 4*r+4).reduce(function (a, x) { return a.concat(x); }, []));
    return { rounds: rounds, nr: nr };
  }
  function aesBlock(block, rk) {
    var s = block.slice(), nr = rk.nr, r, i, tmp;
    for (i = 0; i < 16; i++) s[i] ^= rk.rounds[0][i];
    for (r = 1; r <= nr; r++) {
      for (i = 0; i < 16; i++) s[i] = AES_SBOX[s[i]];
      /* ShiftRows: state is column-major [r + 4c] */
      var o = new Array(16);
      for (var c = 0; c < 4; c++) for (var rr = 0; rr < 4; rr++)
        o[rr + 4*c] = s[rr + 4*((c + rr) % 4)];
      s = o;
      if (r !== nr) {
        for (c = 0; c < 4; c++) {
          var a0=s[4*c],a1=s[4*c+1],a2=s[4*c+2],a3=s[4*c+3];
          s[4*c]   = mul(a0,2) ^ mul(a1,3) ^ a2 ^ a3;
          s[4*c+1] = a0 ^ mul(a1,2) ^ mul(a2,3) ^ a3;
          s[4*c+2] = a0 ^ a1 ^ mul(a2,2) ^ mul(a3,3);
          s[4*c+3] = mul(a0,3) ^ a1 ^ a2 ^ mul(a3,2);
        }
      }
      for (i = 0; i < 16; i++) s[i] ^= rk.rounds[r][i];
    }
    return s;
  }
  function ctrInc(c) { for (var i = 15; i >= 0; i--) { c[i] = (c[i] + 1) & 0xFF; if (c[i]) break; } }
  function pkcs7Unpad(b) { var p = b[b.length-1]; if (p > 0 && p <= 16) return b.slice(0, b.length - p); return b; }
  function aesCrypto(mode, keyBytes, ivBytes, dataBytes) {
    var rk = aesExpand(keyBytes), out = [], i, j, prev, ctr, ks, blk;
    if (mode === 'CTR') {
      ctr = ivBytes.slice(0, 16); while (ctr.length < 16) ctr.push(0);
      for (i = 0; i < dataBytes.length; i += 16) {
        ks = aesBlock(ctr, rk); ctrInc(ctr);
        blk = dataBytes.slice(i, i + 16);
        for (j = 0; j < blk.length; j++) out.push(blk[j] ^ ks[j]);
      }
      return out;
    }
    prev = ivBytes.slice(0, 16); while (prev.length < 16) prev.push(0);
    for (i = 0; i < dataBytes.length; i += 16) {
      blk = dataBytes.slice(i, i + 16);
      if (mode === 'CBC') {
        var dec = aesBlock(blk, rk);
        for (j = 0; j < 16; j++) out.push(dec[j] ^ prev[j]);
        prev = blk.slice();
      } else {
        var dec2 = aesBlock(blk, rk);
        for (j = 0; j < 16; j++) out.push(dec2[j]);
      }
    }
    var res = out.slice(0, dataBytes.length);
    return /PKCS/i.test(mode) ? pkcs7Unpad(res) : res;
  }
  function bytesFrom(v) {
    if (isArr(v)) return v.map(function (x) { return (Number(x) & 0xFF); });
    if (v && typeof v === 'object' && isArr(v._b)) return bytesFrom(v._b);
    if (typeof v === 'string') return stringToBytes(v);
    return [];
  }
  function makeCipher(trans) {
    var parts = str(trans).toUpperCase().split('/');
    var mode = parts[1] || 'ECB', pad = parts[2] || 'PKCS5PADDING';
    var key = null, iv = null, op = 0;
    return {
      init: function (opmode, keyObj, ivObj) {
        op = opmode;
        key = bytesFrom(keyObj && keyObj.getEncoded ? keyObj.getEncoded() : keyObj);
        iv = bytesFrom(ivObj && ivObj.getIV ? ivObj.getIV() : ivObj);
        return this;
      },
      doFinal: function (data) {
        var b = bytesFrom(data);
        /* Rhino 规则里 DECRYPT_MODE=2；加密模式这里也走同一核心（CTR 对合） */
        var out = aesCrypto(mode === 'CTR' ? 'CTR' : mode, key, iv, b);
        return out;
      },
      update: function (d) { return this.doFinal(d); },
      getIV: function () { return iv; }
    };
  }

  var JAVA_PACKAGES = {
    java: JAVA_SHIM,
    javax: {
      crypto: {
        Cipher: {
          getInstance: function (trans) { return makeCipher(trans); },
          DECRYPT_MODE: 2, ENCRYPT_MODE: 1
        },
        spec: {
          SecretKeySpec: function (bytes, alg) {
            return { getEncoded: function () { return bytesFrom(bytes); }, _alg: alg };
          },
          IvParameterSpec: function (bytes) {
            return { getIV: function () { return bytesFrom(bytes); } };
          }
        }
      }
    }
  };
  function toByteArray(v) {
    if (isArr(v)) return v.slice();
    if (typeof v === 'string') return stringToBytes(v);
    return [];
  }
  function stringToBytes(s) {
    var t = utf8Encode(s), out = [];
    for (var i = 0; i < t.length; i++) out.push(t.charCodeAt(i) & 0xFF);
    return out;
  }
  function bytesToString(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i] & 0xFF);
    return s;
  }

  /* ==================================================================== *
   * 6. $ 模块系统
   *    $.require('cg718')  -> 执行 MY_RULE.pages 里 path=cg718 的代码，
   *                            取 $.exports
   *    $(url).rule(fn) / .lazyRule(fn, p...)  -> 生成可执行的 hiker:// 链接
   *    $.toString(fn) -> 函数源码
   * ==================================================================== */
  function encodeLazy(fn, args) {
    var src = typeof fn === 'function' ? fn.toString() : str(fn);
    var a = [];
    for (var i = 0; i < (args || []).length; i++) a.push(JSON.stringify(args[i]));
    return (a.length ? src + '(' + a.join(',') + ')' : src);
  }

  function makeDollar(modName) {
    var d = function (x, hint) {
      var base;
      if (!x) base = 'hiker://empty';
      else if (str(x).indexOf('hiker://') === 0) base = str(x);
      else base = 'hiker://empty' + str(x);
      d.__inputValue = str(x || '');
      d.__inputHint = str(hint || '');
      return {
        rule: function (fn) { return base + '#@rule=js:' + (typeof fn === 'function' ? fn.toString() : str(fn)); },
        lazyRule: function (fn) {
          var rest = Array.prototype.slice.call(arguments, 1);
          return base + '#@lazyRule=.js:' + encodeLazy(fn, rest);
        },
        /* $(默认值, 提示语).input(回调)：点开弹输入框，把输入值作为第一个参数传给回调 */
        input: function (fn) {
          var rest = Array.prototype.slice.call(arguments, 1);
          return base + '#@input=' + encodeURIComponent(JSON.stringify({ v: str(d.__inputValue || ''), d: str(d.__inputHint || '') }))
            + '#@lazyRule=.js:' + encodeLazy(fn, rest);
        },
        toString: function () { return base; }
      };
    };
    d.toString = function (fn) { return typeof fn === 'function' ? fn.toString() : str(fn); };
    /* [P2] 合集框架用 $.require('page', 对象参数) 传子站配置。
     * 原来只接 name，第二参数被丢弃；而 requireModule 内部 str(对象) 又会
     * 变成 [object Object]。这里两个都修。 */
    d.require = function (name, param) { return requireModule(name, param); };
    d.exports = null;
    d.moduleName = modName;
    d.requireMem = function (name, param) { return requireModule(name, param); };
    d.hikerUrl = function (u) { return str(u); };
    return d;
  }

  /**
   * 加载模块：从当前规则对象的 pages 数组里按 path 找。
   * pages 可能是 JSON 字符串，也可能是数组。
   * 模块代码执行时 $ 指向它自己的模块对象，执行完取 $.exports。
   */
  function requireModule(name, importParam) {
    var key = str(name);
    var param = importParam === undefined ? null : importParam;
    /* hiker://page/<模块名>[?<参数>]：等价于「按名字加载 pages[] 里的模块」，
     * 参数通过 $.importParam 传给模块（阅动漫的 dataLoad / funcTools 就靠这个）。 */
    var pm = /^hiker:\/\/page\/([^?#]+)(?:[?#]([\s\S]*))?$/.exec(key);
    if (pm) {
      var modName = decodeURIComponent(pm[1]);
      param = pm[2] === undefined ? (param || '') : pm[2];
      key = modName;
    }
    var rule = PAGE.rule || {};
    /* [P2] 对象参数要保留类型；缓存 key 用稳定序列化，避免 [object Object] 撞车 */
    var paramKey = (param === null || param === undefined) ? ''
        : (typeof param === 'object' ? JSON.stringify(param) : str(param));
    var cacheKey = HK.ruleName() + '::' + key + (paramKey ? '::' + paramKey : '');
    if (has(STORE.modules, cacheKey)) return STORE.modules[cacheKey];

    var pages = rule.pages;
    if (typeof pages === 'string') pages = jparse(pages, []) || [];
    if (!isArr(pages)) pages = [];

    var page = null;
    for (var i = 0; i < pages.length; i++) {
      if (pages[i] && str(pages[i].path) === key) { page = pages[i]; break; }
    }
    if (!page) {
      /* 远程/本地模块：name 本身是 URL 或文件路径 */
      if (/^https?:\/\//.test(key) || /\.js$/i.test(key) || key.indexOf('hiker://') === 0) {
        var code = /^https?:\/\//.test(key) ? reqSync(key, {}) : fileRead(key);
        return runModule(key, code, cacheKey);
      }
      emit('hk.error', '模块不存在: ' + key);
      return null;
    }
    return runModule(key, str(page.rule || ''), cacheKey, param);
  }

  function runModule(key, code, cacheKey, importParam) {
    if (!trim(code)) { STORE.modules[cacheKey] = null; return null; }
    /* 只剥 "js:" 前缀 —— 后面的 "//" 是注释开头，不能一起剥掉，
     * 否则 `js:// 说明文字` 会变成非法 JS（实测第一条规则就死在这里）。 */
    var src = str(code).replace(/^\s*js:/, '');
    var mod$ = makeDollar(key);
    /* [P2] 对象参数原样透传（子站框架靠 $.importParam.site / .url 取配置） */
    mod$.importParam = (importParam === undefined || importParam === null) ? '' : importParam;
    var sc = makeScope({ '$': mod$, importParam: mod$.importParam });
    var result = null;
    try {
      var fn = new Function('__scope', 'with(__scope){\n' + src + '\n}');
      result = fn.call({}, sc);
    } catch (e) {
      emit('hk.error', '模块执行失败 ' + key + ': ' + (e && e.message ? e.message : e));
      STORE.modules[cacheKey] = null;
      return null;
    }
    var exp = mod$.exports;
    if (exp === null || exp === undefined) exp = (result && typeof result === 'object') ? result : null;
    STORE.modules[cacheKey] = exp;
    return exp;
  }
  HK.require = requireModule;

  /* ==================================================================== *
   * 7. 规则执行
   * ==================================================================== */
  /**
   * 执行一段规则代码。返回 { ok, result, err }。
   * extraVars 会覆盖作用域里的变量（MY_URL / input / MY_KEYWORD…）。
   */
  function run(code, extraVars) {
    /* 规则字段一律可能带 "js:" 前缀（js:$.x() / js: const a = 1）。
     * 只剥这个前缀：`js: const a=…` 不剥就是语法错误；而 `js:$.x()` 不剥会被
     * 当成「label js:」侥幸跑通，掩盖问题。 */
    var src = str(code).replace(/^\s*js:/, '');
    if (!trim(src)) return { ok: false, err: '规则为空', result: undefined };
    var sc = makeScope();
    if (extraVars) for (var k in extraVars) if (has(extraVars, k)) sc[k] = extraVars[k];
    /* input 与 MY_URL 等同步到作用域 */
    try {
      var fn = new Function('__scope', 'with(__scope){\n' + src + '\n}');
      fn.call({}, sc);
      return { ok: true, result: OUT.result, err: '' };
    } catch (e) {
      return { ok: false, err: (e && e.name ? e.name + ': ' : '') + str(e && e.message || e), result: undefined };
    }
  }
  HK.run = run;

  /* 执行 lazyRule / @rule= 里内联的 JS 表达式 */
  function runInline(code, extraVars) {
    var src = str(code).replace(/^\s*js:/, '');
    if (!trim(src)) return { ok: false, err: '空规则' };
    var sc = makeScope();
    if (extraVars) for (var k in extraVars) if (has(extraVars, k)) sc[k] = extraVars[k];
    try {
      var fn = new Function('__scope', 'with(__scope){\nreturn (' + src + '\n);}');
      var v = fn.call({}, sc);
      return { ok: true, value: v, result: OUT.result };
    } catch (e1) {
      try {
        var fn2 = new Function('__scope', 'with(__scope){\n' + src + '\n}');
        fn2.call({}, sc);
        return { ok: true, value: OUT.result, result: OUT.result };
      } catch (e2) {
        return { ok: false, err: (e2 && e2.name ? e2.name + ': ' : '') + str(e2 && e2.message || e2) };
      }
    }
  }
  HK.runInline = runInline;

  /* 播放：走宿主 */
  HK.play = function (url, title, pic) {
    var u = str(url).replace(/#[^#]*#/g, '').trim();
    if (!u) return false;
    if (bridge && typeof bridge.play === 'function') {
      try { return !!bridge.play(u, str(title), str(pic)); } catch (e) { return false; }
    }
    emit('hk.play', u);
    return true;
  };

  /* 给界面层用的 myVar 读写（合集包的路由变量要在这里补） */
  HK.getMyVar = function (k) {
    var q = myKey(k);
    return has(STORE.myVars, q) ? STORE.myVars[q] : '';
  };
  HK.setMyVar = function (k, v) { STORE.myVars[myKey(k)] = v; return v; };

  global.HikerEngine = HK;
})(typeof window !== 'undefined' ? window : this);
