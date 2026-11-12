/*!
 * HikerApp —— 海阔用户前端「应用逻辑层」（口令 → 站点 → 播放）
 *
 * 定位：这一层才是用户说的「海阔程序前端」。它不展示任何桥能力表/覆盖率/兼容性/
 * registry/日志（那些是开发者自测面板，属于项目后端数据可视化，本层一律不提供）。
 * 它只做终端用户会做的事：
 *
 *   1) 录入口令：单口令 / 多口令 / 含密码 / 导入（粘贴、文件、剪贴板）
 *   2) 口令解析 → 生成站点（一个或多个）
 *   3) 点击站点 → 走站点的播放逻辑：
 *        home(首页/分类) → cate(分类) → search(搜索) → detail(详情/选集) → play(播放器)
 *
 * 口令/站点/规则的真实格式取自 xingchen（com.fongmi.android.tv.api.hk.* 逆向结果）：
 *   - 云口令：云1~云10 oooole/{path}，可带 (@pwd) 或口令后空格跟密码
 *   - 明文分享口令：小程序￥home_rule￥{json} / ￥home_rule_v2￥{json}
 *   - base64 壳：base64://@标题@BASE64
 *   - 裸规则 JSON（含 col_type/find_rule）或规则数组
 *   - 配置 URL：http(s) → {sites:[...]}（TVBox 配置）或规则 JSON
 *   - 站点：{key,name,type,api,ext,homePage,searchable,categories,rules}
 *   - 规则链：HkRouter.home()/search()/detail()/play()
 *
 * 运行时复用（不重复造轮子，也不修改）：
 *   HikerHost.req/play（同步桥）、HikerApiSurface.installFresh（134 API 新鲜作用域）、
 *   HikerCompat.classify（Rhino/Python/加密降级识别）、HikerScheme（hiker:// 分发）。
 *
 * 诚实边界：真实规则里 js: 片段可在 WebView 同步执行；选择器型/Rhino/Python/
 * 私有加密/X5 只能明确降级，绝不静默假成功。云口令需真实网络，离线时明确提示。
 *
 * 语法：ES5（Chromium 50~70），无 let/const/箭头/模板串/async/Promise 依赖。
 * 形态：UMD，便于 node 下离线验证（无 window/document 依赖）。
 */
(function (global) {
  'use strict';

  var App = { version: '1.0.0' };

  /* =====================================================================
   * 0) 运行时装配与小工具
   * ===================================================================== */
  var RT = {
    req: null, play: null, isOffline: true,
    installFresh: null, classify: null, scheme: null, report: null
  };

  function wire(g) {
    g = g || global;
    var host = g.HikerHost;
    RT.req = (host && typeof host.req === 'function') ? function (u, o) { return host.req(u, o); } : null;
    RT.play = (host && typeof host.play === 'function') ? function (u, t, p) { return host.play(u, t, p); }
            : function (u) { (g.__HK_PLAYS__ || (g.__HK_PLAYS__ = [])).push(u); return true; };
    RT.isOffline = host ? !!host.isOffline : true;
    RT.report = (host && typeof host.report === 'function') ? function (t, v) { host.report(t, v); } : function () {};
    var AS = g.HikerApiSurface;
    RT.installFresh = (AS && typeof AS.installFresh === 'function') ? function (p, o) { return AS.installFresh(p, o); } : null;
    var HC = g.HikerCompat;
    RT.classify = (HC && typeof HC.classify === 'function') ? function (c) { return HC.classify(c); } : null;
    var HS = g.HikerScheme;
    RT.scheme = (HS && typeof HS.dispatch === 'function') ? function (u, o) { return HS.dispatch(u, o); } : null;
    return RT;
  }
  App.wire = wire;

  /* 同步取数：优先 hiker:// 分发，再退到 Java 同步桥；离线返回 ''（不伪造内容） */
  function net(url, opts) {
    var u = String(url || ''), o = opts || {};
    if (u.indexOf('hiker://') === 0 && RT.scheme) {
      try { return String(RT.scheme(u, { request: null, options: o }).body || ''); } catch (e) { return ''; }
    }
    if (!RT.req) return '';
    try { return String(RT.req(u, JSON.stringify(o)) || ''); } catch (e) { return ''; }
  }
  App.net = net;

  function jparse(s, d) { try { return JSON.parse(s); } catch (e) { return d; } }
  function s(v) { return (v === null || v === undefined) ? '' : String(v); }
  function trim(v) { return s(v).replace(/^\s+|\s+$/g, ''); }
  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

  /* UTF-8 安全的 base64 解码（兼容标准 / URL-safe / 无 atob 环境） */
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function utf8(bytes) {
    var out = '', i = 0, b, cp;
    while (i < bytes.length) {
      b = bytes[i++] & 0xff;
      if (b < 0x80) out += String.fromCharCode(b);
      else if (b < 0xE0) out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i++] & 0x3f));
      else if (b < 0xF0) out += String.fromCharCode(((b & 0xf) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f));
      else {
        cp = ((b & 7) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
        cp -= 0x10000;
        out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3ff));
      }
    }
    return out;
  }
  function b64d(src) {
    var t = s(src).replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/'), out = [], i, c, buf = 0, bits = 0;
    if (global.atob) {
      try {
        var raw = global.atob(t), arr = [];
        for (i = 0; i < raw.length; i++) arr.push(raw.charCodeAt(i) & 0xff);
        return utf8(arr);
      } catch (e) { /* 落到自实现 */ }
    }
    for (i = 0; i < t.length; i++) {
      c = B64.indexOf(t.charAt(i));
      if (c < 0) continue;
      buf = (buf << 6) | c; bits += 6;
      if (bits >= 8) { bits -= 8; out.push((buf >> bits) & 0xff); }
    }
    return utf8(out);
  }
  App.b64d = b64d;

  /* =====================================================================
   * 1) 口令解析 —— 单口令 / 多口令 / 含密码 / 导入
   * ===================================================================== */

  /* 云口令 → 云端 URL（通道映射逐条对齐 xingchen HkRuleManager.fetchCloudJson） */
  var CLOUD_FIND = /云([1-9]|10)oooole\/\S+/;
  var CLOUD_FULL = /^云([1-9]|10)oooole(\/.*)$/;
  App.CLOUD_FIND = CLOUD_FIND;

  function cloudUrl(raw, pwd) {
    var m = CLOUD_FIND.exec(raw);
    if (!m) return { ok: false, error: '不是有效的云口令（需云1~云10 开头）' };
    var code = m[0], mm = CLOUD_FULL.exec(code);
    if (!mm) return { ok: false, error: '云口令格式有误' };
    var ver = mm[1], path = mm[2], rest, id, key, segs, bases, i;

    /* 口令后空格跟的一段当密码（海阔 PastemeImporter 同款）；说明性后缀不算 */
    var tail = raw.slice(m.index + code.length).replace(/^\s+/, '');
    var p = pwd || '';
    if (!p && tail && tail.indexOf('小程序') !== 0 && tail.indexOf('http') !== 0) p = tail.split(/\s+/)[0];

    if (path.indexOf('/p/') === 0) {
      return { ok: false, error: '该口令走 netcut 通道，官方已下线接口；请让分享者重新导出或索取规则文件' };
    }
    if (path.indexOf('/apidb/') === 0) {
      key = path.slice(7).split(/[\s/]/)[0];
      if (!key) return { ok: false, error: '云2 口令缺少 apidb key' };
      return { ok: true, url: 'https://textdb.online/' + key, channel: '云2/apidb(textdb)', outerB64: true, password: p };
    }
    if (path.indexOf('/xxxxxx/') === 0) {
      rest = path.slice(8).split(/\s/)[0];
      if (!rest) return { ok: false, error: '云6 口令缺少便签ID' };
      if (p && rest.indexOf('(@') < 0) rest += '(@' + p + ')';
      return { ok: true, url: 'https://pasteme.tyrantg.com/api/getContent/' + rest,
               channel: '云6/pasteme', headers: { referer: 'https://pasteme.tyrantg.com/', 'User-Agent': 'Mozilla/5.0' }, password: p };
    }
    if (ver === '1') {
      id = path.indexOf('/raw/') === 0 ? path.slice(5) : path.slice(1);
      id = id.split(/[\s/]/)[0];
      if (!id) return { ok: false, error: '云1 口令缺少 pasteId' };
      return { ok: true, url: 'https://pastebin.com/raw/' + id, channel: '云1/pastebin', password: p };
    }
    if (ver === '5') {
      id = path.slice(1).split(/[\s/]/)[0];
      if (!id) return { ok: false, error: '云5 口令缺少便签ID' };
      return { ok: true, url: 'https://cmd.im/' + id, channel: '云5/cmd.im', password: p };
    }
    if (ver === '7') {
      id = path.slice(1).split(/[\s/]/)[0];
      if (!id) return { ok: false, error: '云7 口令缺少便签ID' };
      return { ok: true, url: 'https://note.ms/' + id, channel: '云7/note.ms', textarea: true, password: p };
    }
    if (ver === '9') {
      id = path.slice(1).split(/[\s/]/)[0];
      if (!id) return { ok: false, error: '云9 口令缺少便签ID' };
      return { ok: true, url: 'https://txtpad.cn/' + id, channel: '云9/txtpad', password: p };
    }
    if (ver === '10') {
      segs = path.split('/'); key = segs[segs.length - 1].split(/\s/)[0];
      if (!key) return { ok: false, error: '云10 口令缺少 key' };
      bases = ['https://hassdtebin.com/raw/', 'https://hastebin.com/raw/'];
      return { ok: true, url: bases[0] + key, fallback: bases[1] + key, channel: '云10/hastebin', password: p };
    }
    if (ver === '3' || ver === '4' || ver === '8') {
      return { ok: false, error: '云' + ver + ' 通道在 8.83 中无实现，不支持' };
    }
    return { ok: false, error: '暂不支持该云口令通道（支持：云1/云2/云5/云6/云7/云9/云10）' };
  }
  App.cloudUrl = cloudUrl;

  /* 还原分享包装：￥home_rule￥{json} / ￥home_rule_v2￥{json} / base64://@标题@BASE64 / 裸 JSON */
  function unwrap(text) {
    var t = trim(text);
    if (!t) return { ok: false, error: '内容为空' };
    var YEN = '\uFFE5';
    if (t.indexOf(YEN) >= 0) {
      var parts = t.split(YEN);
      if (parts.length >= 3) {
        var type = trim(parts[1]);
        if (type === 'home_rule' || type === 'home_rule_v2') {
          var json = parts.slice(2).join(YEN).replace(/^\s+|\s+$/g, '');
          if (json.charAt(0) === '{' || json.charAt(0) === '[') return { ok: true, text: json, kind: 'share', shareType: type };
        }
      }
    }
    var idx = t.indexOf('base64://');
    if (idx >= 0) {
      var segs = t.slice(idx + 9).split('@');
      if (segs.length >= 3) {
        var dec = b64d(segs[2]);
        if (dec && dec.indexOf('{') >= 0) return { ok: true, text: dec, kind: 'base64' };
      }
    }
    if (t.charAt(0) === '{' || t.charAt(0) === '[') return { ok: true, text: t, kind: 'json' };
    if (/^https?:\/\//i.test(t) || t.indexOf('hiker://') === 0 ||
        t.indexOf('file://') === 0 || t.indexOf('assets://') === 0) {
      return { ok: true, text: t, kind: 'url' };
    }
    return { ok: false, error: '无法识别的口令/内容格式', kind: 'unknown' };
  }
  App.unwrap = unwrap;

  /* 单条口令分类 */
  function classifyCode(raw) {
    var t = trim(raw);
    if (!t) return { kind: 'empty', raw: t };
    if (CLOUD_FIND.test(t)) return { kind: 'cloud', raw: t };
    if (t.indexOf('\uFFE5home_rule') >= 0) return { kind: 'share', raw: t };
    if (t.indexOf('base64://') >= 0) return { kind: 'base64', raw: t };
    if (t.charAt(0) === '{' || t.charAt(0) === '[') return { kind: 'json', raw: t };
    if (/^https?:\/\//i.test(t) || t.indexOf('hiker://') === 0) return { kind: 'url', raw: t };
    if (t.indexOf(',') >= 0 || t.indexOf('$') >= 0) return { kind: 'line', raw: t };
    return { kind: 'token', raw: t };
  }
  App.classifyCode = classifyCode;

  /* 多口令拆分：以换行为主；分号只用于「纯文本行」的分隔。
   * 关键：JSON 配置、￥分享包装、base64 壳里普遍含分号与 ￥，
   * 若一律按分号切会把一条规则切碎（实测：分享口令被切成 2 段、配置变 0 站点）。 */
  function splitMulti(text) {
    var t = s(text).replace(/\r/g, '');
    var lines = t.split(/\n+/), out = [], i, j, L, c, seg, s2;
    for (i = 0; i < lines.length; i++) {
      L = trim(lines[i]);
      if (!L) continue;
      c = classifyCode(L).kind;
      if (c === 'json' || c === 'share' || c === 'base64' || c === 'cloud' || c === 'url') {
        out.push(L);                         /* 结构化内容整行保留 */
      } else {
        seg = L.split(/;|；/);                /* 纯文本行才用分号分隔 */
        for (j = 0; j < seg.length; j++) { s2 = trim(seg[j]); if (s2) out.push(s2); }
      }
    }
    return out;
  }
  App.splitMulti = splitMulti;

  /**
   * 录入口令 → 解析成待解析条目。
   * mode: 'single' | 'multi' | 'password' | 'import'
   * 返回 { mode, entries:[{raw,kind,name,password}], passwordApplied, warnings }
   */
  function parseInput(text, opts) {
    opts = opts || {};
    var mode = opts.mode || 'single';
    var pwd = trim(opts.password || '');
    var raw = s(text), parts, entries = [], warnings = [], i, c;

    if (mode === 'multi') parts = splitMulti(raw);
    else if (mode === 'import') {
      /* 导入：整段是合法 JSON（配置/规则数组，可能格式化多行）就整体解析，
       * 否则一律按行拆成多条口令。不能用 classifyCode(整段) 的 kind 判断——
       * 一段以云口令开头的多行文本会被误判成「单条云口令」吞掉后续口令。 */
      var t = trim(raw);
      parts = ((t.charAt(0) === '{' || t.charAt(0) === '[') && jparse(t, null) !== null)
              ? [t] : splitMulti(raw);
    } else parts = [trim(raw)];   /* single / password 都是一条 */

    for (i = 0; i < parts.length; i++) {
      var line = parts[i];
      if (!line) continue;
      c = classifyCode(line);
      var name = '';
      /* 「名称,url」「名称$url」把名称提出来 */
      var mm = /^([^,$,，\s]{1,20})[, $，](https?:\/\/\S+|云[0-9]|.*)$/.exec(line);
      if (mm) { name = trim(mm[1]); }
      entries.push({
        raw: line, kind: c.kind, name: name,
        password: (mode === 'password' || pwd) ? pwd : ''
      });
    }
    if (!entries.length) warnings.push('没有解析到任何口令');
    if (mode === 'password' && !pwd) warnings.push('选择了「含密码」但未填写密码；若口令自带 (@pwd) 仍可解析');
    return {
      mode: mode, entries: entries, passwordApplied: !!pwd, warnings: warnings,
      count: entries.length
    };
  }
  App.parseInput = parseInput;

  /* =====================================================================
   * 2) 口令取内容 + 站点生成
   * ===================================================================== */


  /* [完善] 云6 pasteme 信封剥壳 —— 移植自 xingchen HkRuleManager.extractRuleJsonFromPasteme。
   * 官方响应形如 {"return_code":0,"data":"...rule.json / 分享口令 / 配置..."}。
   * 未剥壳时整个信封会被当裸 JSON，buildSites 生成一个无规则的假站点。
   */
  function pastemeEnvelope(body) {
    try {
      var o = JSON.parse(String(body).trim());
      if (!o || typeof o !== 'object' || o.return_code === undefined) return null;
      if (Number(o.return_code) !== 0) return null;              /* 需密码 / 密码错 / 不存在 */
      if (o.data === null || o.data === undefined) return null;
      var d = String(o.data).trim();
      return d ? d : null;
    } catch (e) { return null; }
  }
  App.pastemeEnvelope = pastemeEnvelope;

  /* 取一条口令的原始内容文本（云口令走真实网络；本地形态直接用） */
  function resolveEntry(entry, opts) {
    opts = opts || {};
    var kind = entry.kind, raw = entry.raw, out = { ok: false, text: '', channel: '', entry: entry, notes: [] };

    if (kind === 'cloud') {
      var cu = cloudUrl(raw, entry.password);
      if (!cu.ok) { out.error = cu.error; return out; }
      if (RT.isOffline && !opts.forceNetwork) { out.error = '云口令需联网取回内容（当前离线）'; out.channel = cu.channel; return out; }
      var body = net(cu.url, cu.headers ? { headers: cu.headers } : {});
      if (!body && cu.fallback) body = net(cu.fallback, {});
      if (!body) { out.error = '云端未返回内容（便签不存在或密码错误）'; out.channel = cu.channel; return out; }
      out.channel = cu.channel;
      /* [完善] 云6 pasteme：先剥 {"return_code":0,"data":"..."} 信封，再走分享包装还原 */
      if (cu.channel === '云6/pasteme') {
        var env = pastemeEnvelope(body);
        if (env === null) { out.error = '云6 便签不存在/需密码/密码错误（return_code≠0 或 data 为空）'; return out; }
        body = env;
      }
      if (cu.outerB64) {                    /* 云2 textdb：外层 base64 → 剥壳 */
        var dec = b64d(body);
        if (!dec) { out.error = '云端内容不是有效 base64'; return out; }
        body = dec;
      }
      if (cu.textarea) {                    /* 云7 note.ms：取 textarea 文本 */
        var mm = /<textarea[^>]*>([\s\S]*?)<\/textarea>/i.exec(body);
        if (mm) body = mm[1].replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
      }
      var uw = unwrap(body);
      if (!uw.ok) { out.error = uw.error + '（口令可能已失效或云端内容已更换）'; return out; }
      out.ok = true; out.text = uw.text; out.wrap = uw.kind;
      return out;
    }

    if (kind === 'url' || kind === 'line') {
      var url = raw;
      if (kind === 'line') {
        var m = /(https?:\/\/\S+)/.exec(raw);
        if (!m) { out.error = '文本行里没有可取的 URL'; return out; }
        url = m[1];
      }
      if (RT.isOffline && !opts.forceNetwork && url.indexOf('hiker://') !== 0) {
        out.error = '配置/规则地址需联网取回（当前离线）'; return out;
      }
      var b = net(url, {});
      if (!b) { out.error = '地址未返回内容'; return out; }
      var u2 = unwrap(b);
      out.ok = u2.ok; out.text = u2.ok ? u2.text : b; out.channel = url; out.wrap = u2.kind || 'raw';
      return out;
    }

    /* share / base64 / json / token：本地还原 */
    var u = unwrap(raw);
    if (!u.ok) { out.error = u.error; return out; }
    out.ok = true; out.text = u.text; out.channel = '本地解析'; out.wrap = u.kind;
    return out;
  }
  App.resolveEntry = resolveEntry;

  /* 归一化单个站点对象 */
  function oneSite(o, i, src) {
    if (!o || typeof o !== 'object') return null;
    var key = s(o.key || o.id || o.name || o.title || ('site' + i));
    var name = s(o.name || o.title || o.key || ('站点' + (i + 1)));
    return {
      key: key, name: name,
      type: Number(o.type || 3),
      api: s(o.api || o.url || o.searchUrl || ''),
      ext: s(o.ext || o.homePage || o.home || ''),
      homePage: s(o.homePage || o.ext || ''),
      searchable: (o.searchable === undefined ? 1 : Number(o.searchable)),
      changeable: (o.changeable === undefined ? 0 : Number(o.changeable)),
      categories: (o.categories && o.categories.length) ? o.categories.slice() : [],
      pic: s(o.pic || o.icon || ''),
      group: s(o.group || src || ''),
      source: s(src || ''),
      rules: normalizeRules(o),
      raw: o
    };
  }

  /* 把一个 rule.json / 站点对象里的播放逻辑归一成 {home,cate,search,detail,play} */
  function normalizeRules(o) {
    var R = { home: '', cate: '', search: '', detail: '', play: '', lazy: '' };
    if (!o || typeof o !== 'object') return R;
    /* 站点内显式 rules 对象（演示站点/自包含规则）优先 */
    var r = o.rules || o.rule || null;
    if (r && typeof r === 'object') {
      R.home = s(r.home || r.home_rule || r.find_rule || '');
      R.cate = s(r.cate || r.cate_rule || r.home || '');
      R.search = s(r.search || r.searchFind || r.search_rule || '');
      R.detail = s(r.detail || r.detail_find_rule || r.detail_rule || '');
      R.play = s(r.play || r.last_chapter_rule || r.play_rule || '');
      R.lazy = s(r.lazy || r.lazyRule || '');
      return R;
    }
    /* rule.json 原生字段 */
    R.home = s(o.find_rule || o.home_rule || '');
    R.cate = s(o.cate_rule || o.find_rule || '');
    R.search = s(o.searchFind || o.search_rule || '');
    R.detail = s(o.detail_find_rule || o.sdetail_find_rule || '');
    R.play = s(o.last_chapter_rule || o.play_rule || '');
    R.preRule = s(o.preRule || '');
    return R;
  }

  /**
   * 内容文本 → 站点数组（按 key 去重）。
   * 兼容：{sites:[]} / {urls:[]} / {data|list|items|depot:[]} / 顶层数组 /
   *       单站点对象 / 单规则对象（rule.json）/ 纯文本行。
   */
  function buildSites(text, meta) {
    meta = meta || {};
    var src = s(meta.source || ''), out = [], seen = {}, d = jparse(trim(text), null), arr = null, i, o, site;
    if (d && Object.prototype.toString.call(d) === '[object Array]') arr = d;
    else if (d && typeof d === 'object') {
      var keys = ['sites', 'urls', 'data', 'list', 'items', 'depot'];
      for (i = 0; i < keys.length; i++) if (Object.prototype.toString.call(d[keys[i]]) === '[object Array]') { arr = d[keys[i]]; break; }
      if (!arr) arr = [d];    /* 单站点 / 单规则 */
      if (d.spider) src = src || 'spider';
    }
    if (arr) {
      for (i = 0; i < arr.length; i++) {
        o = arr[i];
        if (typeof o === 'string') {
          var m = /^(https?:\/\/\S+)/.exec(o);
          if (m) o = { key: 's' + i, name: (o.split(',')[0] || ('站点' + i)).slice(0, 24), api: m[1], type: 3 };
          else continue;
        }
        site = oneSite(o, i, src);
        if (site && !seen[site.key]) { seen[site.key] = 1; out.push(site); }
      }
    } else {
      var lines = s(text).split(/\r?\n/);
      for (i = 0; i < lines.length; i++) {
        var L = trim(lines[i]);
        if (!L) continue;
        var mu = /(https?:\/\/\S+)/.exec(L);
        if (!mu) continue;
        site = oneSite({ key: 's' + i, name: (L.split(',')[0] || L).slice(0, 24), api: mu[1], type: 3 }, i, src);
        if (site && !seen[site.key]) { seen[site.key] = 1; out.push(site); }
      }
    }
    /* 规则对象（rule.json）没有 key 时用 title 兜底，且标记为规则站点 */
    for (i = 0; i < out.length; i++) {
      if (out[i].raw && (out[i].raw.find_rule || out[i].raw.col_type) && !out[i].raw.key) {
        out[i].siteKind = 'rule';
        out[i].name = s(out[i].raw.title || out[i].name);
      } else out[i].siteKind = out[i].siteKind || 'config';
    }
    return out;
  }
  App.buildSites = buildSites;

  /** 一键：录入 → 解析 → 取内容 → 生成站点（用户点「生成站点」） */
  function generate(text, opts) {
    opts = opts || {};
    var parsed = parseInput(text, opts);
    var sites = [], notes = [], i, res, got;
    for (i = 0; i < parsed.entries.length; i++) {
      res = resolveEntry(parsed.entries[i], opts);
      if (!res.ok) { notes.push('口令#' + (i + 1) + '：' + (res.error || '解析失败')); continue; }
      got = buildSites(res.text, { source: res.channel });
      for (var j = 0; j < got.length; j++) {
        got[j].entryIndex = i;
        got[j].channel = res.channel;
      }
      sites = sites.concat(got);
      notes.push('口令#' + (i + 1) + ' [' + res.channel + '] → ' + got.length + ' 个站点');
    }
    /* 按 key 再去一次重（多口令可能重叠） */
    var seen = {}, uniq = [];
    for (i = 0; i < sites.length; i++) if (!seen[sites[i].key]) { seen[sites[i].key] = 1; uniq.push(sites[i]); }
    return {
      mode: parsed.mode, entries: parsed.entries, passwordApplied: parsed.passwordApplied,
      sites: uniq, notes: notes, warnings: parsed.warnings, count: uniq.length
    };
  }
  App.generate = generate;

  /* =====================================================================
   * 3) 规则执行 —— home / cate / search / detail / play
   * ===================================================================== */

  /* js: 前缀剥离；选择器型/Rhino/Python/加密 → 明确降级 */
  function prep(code) {
    var c = s(code);
    c = c.replace(/^\s*js\s*:\s*/i, '');
    return c;
  }
  function isJs(code) {
    var c = prep(code);
    if (!c) return false;
    /* 纯选择器规则（@css: / .class&&Text / pdfa 选择器串）不是可执行 JS */
    if (/^\s*@?(css|xp|xpath|json|regex)\s*:/i.test(c)) return false;
    if (/^[\w.\-#\[\]@ ]+&&/.test(c) && c.indexOf('setResult') < 0 && c.indexOf('var ') < 0) return false;
    return true;
  }

  /**
   * 在新鲜作用域里同步执行规则片段，捕获 setResult 的「完整」值（不截断）。
   * 返回 { ok, action, value, err, degrade }
   */
  function execRule(code, page, opts) {
    opts = opts || {};
    var c = prep(code);
    var rec = { ok: false, action: 'run-in-webview', value: null, err: null, degrade: null };
    if (!trim(c)) { rec.action = 'no-rule'; rec.err = '站点未配置此段规则'; return rec; }
    if (!isJs(c)) {
      rec.action = 'degrade'; rec.err = '选择器型规则需要 DOM/CSP 引擎，本 WebView 前端不支持';
      rec.degrade = 'selector'; return rec;
    }
    if (RT.classify) {
      var cl = RT.classify(c);
      if (cl && cl.unsupported && cl.unsupported.length) {
        rec.action = 'degrade'; rec.err = 'unsupported-form: ' + cl.unsupported.join(',');
        rec.degrade = cl.unsupported.join(','); return rec;
      }
    } else if (/\bJavaAdapter\b|\bPackages\.|importPackage|importClass|\bpy\s*:/.test(c)) {
      rec.action = 'degrade'; rec.err = 'unsupported-form: rhino/python'; rec.degrade = 'rhino/python'; return rec;
    }

    var scope = {};
    if (RT.installFresh) { try { scope = RT.installFresh(page || {}, opts.ruleOpts || {}) || {}; } catch (e) { scope = {}; } }
    var defs = { MY_URL: '', MY_HOME: '', MY_PAGE: 1, MY_KEYWORD: '', MY_RULE: {}, MY_PARAMS: '',
                 input: '', params: {}, d: [], host: '', MY_TITLE: '', MY_PIC: '' };
    Object.keys(defs).forEach(function (k) { if (typeof scope[k] === 'undefined') scope[k] = defs[k]; });
    var p = page || {};
    Object.keys(p).forEach(function (k) { scope[k] = p[k]; });

    var captured = null, hasCap = false;
    function setRes(v) { captured = v; hasCap = true; }
    scope.setResult = setRes; scope.setHomeResult = setRes;
    scope.setSearchResult = setRes; scope.setStrResult = setRes;
    scope.setLastChapterResult = setRes; scope.nsetHomeResult = setRes;
    /* 同步网络：规则里 fetch/request 一律走同步桥，不落浏览器原生 Promise fetch */
    if (typeof scope.fetch !== 'function') scope.fetch = function (u, o) { return net(u, o); };
    if (typeof scope.request !== 'function') scope.request = function (u, o) { return net(u, o); };

    try {
      /* eslint-disable no-new-func */
      (new Function('scope', 'with(scope){' + c + '\n}')).call({}, scope);
    } catch (e) {
      rec.action = 'error'; rec.err = (e && e.name ? e.name + ': ' : '') + s(e && e.message || e).slice(0, 180);
      return rec;
    }
    if (!hasCap) { rec.action = 'error'; rec.err = 'no-setResult（规则没有回传结果）'; return rec; }
    if (captured && typeof captured.then === 'function') { rec.action = 'error'; rec.err = 'async-result-not-string'; return rec; }
    rec.ok = true; rec.value = captured;
    return rec;
  }
  App.execRule = execRule;

  /* 结果归一化：把 setResult 的各种形态收敛为 { classes:[], vods:[] } */
  function normList(v) {
    var out = { classes: [], vods: [] }, arr = null, i, o;
    if (v === null || v === undefined) return out;
    if (typeof v === 'string') {
      var d = jparse(v, null);
      if (d && typeof d === 'object') v = d;
      else return out;    /* 纯字符串结果交给上层当播放地址/文本 */
    }
    if (Object.prototype.toString.call(v) === '[object Array]') arr = v;
    else if (typeof v === 'object') {
      if (v.classes || v.cls || v.categories || v.class || v.tabs) {
        var cls = v.classes || v.cls || v.categories || v.class || v.tabs;
        if (Object.prototype.toString.call(cls) === '[object Array]') {
          for (i = 0; i < cls.length; i++) out.classes.push(s(typeof cls[i] === 'object' ? (cls[i].name || cls[i].title || cls[i].type_name) : cls[i]));
        }
      }
      for (var k in v) if (has(v, k)) {
        if (Object.prototype.toString.call(v[k]) === '[object Array]' &&
            ['list', 'vods', 'items', 'data', 'result', 'movies', 'videos'].indexOf(k) >= 0) { arr = v[k]; break; }
      }
    }
    if (!arr) return out;
    for (i = 0; i < arr.length; i++) {
      o = arr[i];
      if (typeof o === 'string') { out.vods.push({ title: o, url: '', pic: '', desc: '' }); continue; }
      if (!o || typeof o !== 'object') continue;
      out.vods.push({
        title: s(o.title || o.name || o.vod_name || o.text || ('影片' + (i + 1))),
        url: s(o.url || o.link || o.vod_id || o.id || o.href || o.playUrl || ''),
        pic: s(o.pic || o.img || o.cover || o.vod_pic || o.image || ''),
        desc: s(o.desc || o.remark || o.vod_remarks || o.info || o.subtitle || '')
      });
    }
    return out;
  }
  App.normList = normList;

  /** 点站点 → 首页（分类 + 推荐） */
  function home(site, opts) {
    opts = opts || {};
    var page = opts.page || 1;
    var vars = { MY_URL: site.homePage || site.ext || site.api, MY_PAGE: page, MY_RULE: site.raw || {},
                 MY_KEYWORD: '', input: '', MY_PARAMS: '', params: {}, MY_TITLE: site.name };
    var rec = execRule(site.rules.home, vars, opts);
    var out = { site: site, page: page, classes: [], vods: [], action: rec.action, err: rec.err, degrade: rec.degrade };
    if (!rec.ok) return out;
    var n = normList(rec.value);
    out.classes = n.classes.length ? n.classes : (site.categories.length ? site.categories : []);
    out.vods = n.vods;
    return out;
  }
  App.home = home;

  /** 分类 → 列表 */
  function cate(site, cls, opts) {
    opts = opts || {};
    var page = opts.page || 1;
    var vars = { MY_URL: site.homePage || site.ext || site.api, MY_PAGE: page, MY_RULE: site.raw || {},
                 MY_KEYWORD: '', input: s(cls), params: { cls: s(cls) }, MY_PARAMS: s(cls) };
    var code = site.rules.cate || site.rules.home;
    var rec = execRule(code, vars, opts);
    var out = { site: site, cls: s(cls), page: page, vods: [], action: rec.action, err: rec.err, degrade: rec.degrade };
    if (rec.ok) out.vods = normList(rec.value).vods;
    return out;
  }
  App.cate = cate;

  /** 搜索 */
  function search(site, kw, opts) {
    opts = opts || {};
    var vars = { MY_URL: site.api || site.homePage, MY_PAGE: opts.page || 1, MY_RULE: site.raw || {},
                 MY_KEYWORD: s(kw), input: s(kw), params: { wd: s(kw) } };
    var code = site.rules.search || site.rules.home;
    var rec = execRule(code, vars, opts);
    var out = { site: site, keyword: s(kw), vods: [], action: rec.action, err: rec.err, degrade: rec.degrade };
    if (rec.ok) out.vods = normList(rec.value).vods;
    return out;
  }
  App.search = search;

  /** 点影片 → 详情 + 选集 */
  function detail(site, vod, opts) {
    opts = opts || {};
    var vars = { MY_URL: vod.url || '', MY_PAGE: 1, MY_RULE: site.raw || {}, MY_TITLE: vod.title,
                 MY_PIC: vod.pic, input: vod.url || '', params: vod };
    var rec = execRule(site.rules.detail, vars, opts);
    var out = { site: site, vod: vod, desc: vod.desc, pic: vod.pic, episodes: [],
                action: rec.action, err: rec.err, degrade: rec.degrade };
    if (!rec.ok) return out;
    var v = rec.value, eps = null, i, o;
    if (typeof v === 'string') v = jparse(v, v);
    if (Object.prototype.toString.call(v) === '[object Array]') eps = v;
    else if (v && typeof v === 'object') {
      out.desc = s(v.desc || v.info || v.content || out.desc);
      out.pic = s(v.pic || v.cover || out.pic);
      for (var k in v) if (has(v, k) && Object.prototype.toString.call(v[k]) === '[object Array]') { eps = v[k]; break; }
    }
    if (!eps) return out;
    for (i = 0; i < eps.length; i++) {
      o = eps[i];
      if (typeof o === 'string') { out.episodes.push({ name: o, url: '' }); continue; }
      if (!o || typeof o !== 'object') continue;
      out.episodes.push({ name: s(o.name || o.title || o.episode || ('第' + (i + 1) + '集')),
                          url: s(o.url || o.link || o.playUrl || '') });
    }
    return out;
  }
  App.detail = detail;

  /** 点选集 → 解析播放地址 → 交给 App 播放器 */
  function playEpisode(site, vod, ep, opts) {
    opts = opts || {};
    var target = ep.url || vod.url || '';
    var rec = null;
    if (site.rules.play && trim(site.rules.play)) {
      var vars = { MY_URL: target, MY_PAGE: 1, MY_RULE: site.raw || {}, MY_TITLE: vod.title,
                   input: target, params: { url: target } };
      rec = execRule(site.rules.play, vars, opts);
      if (rec.ok) target = extractUrl(rec.value) || target;
      /* [完善] 规则降级/报错时明确失败，不把 vod.url 兜底值伪装成可播放地址 */
      else if (rec.action === 'degrade' || rec.action === 'error') {
        return { site: site, vod: vod, episode: ep, url: '', title: s(vod.title),
                 playAction: rec.action, err: rec.err || '播放规则无法执行', ok: false, channel: '' };
      }
    }
    var out = { site: site, vod: vod, episode: ep, url: target, title: s(vod.title),
                playAction: rec ? rec.action : 'direct', err: rec ? rec.err : null, ok: false, channel: '' };
    if (!target) { out.err = out.err || '没有可播放地址'; return out; }
    var ok = false;
    try { ok = RT.play(target, vod.title, vod.pic); } catch (e) { ok = false; }
    out.ok = !!ok;
    out.channel = RT.isOffline ? 'offline-mock' : 'local-push(/action?do=push)';
    if (ok) { try { RT.report('hiker.play', target); } catch (e) {} }
    return out;
  }
  App.playEpisode = playEpisode;

  function extractUrl(v) {
    if (typeof v === 'string') return trim(v);
    if (v && typeof v === 'object') {
      var u = v.url || v.playUrl || v.urls;
      if (Object.prototype.toString.call(u) === '[object Array]') return s(u[0]);
      return s(u);
    }
    return '';
  }
  App.extractUrl = extractUrl;

  /* =====================================================================
   * 4) 离线演示站点 —— 保证无网络/无 Java 桥时全链路仍可真实跑通
   *    规则是真实 js: 片段（用 MY_PAGE/input/MY_KEYWORD 产出结果），
   *    不是硬编码结果：验证的是「规则执行 → 归一化 → 播放」这条链。
   * ===================================================================== */
  var DEMO_DB = "var db=[" +
    "{title:'海阔演示·影片A',url:'hiker://page/vod_a',pic:'',desc:'演示A',cat:'动作'}," +
    "{title:'海阔演示·影片B',url:'hiker://page/vod_b',pic:'',desc:'演示B',cat:'喜剧'}," +
    "{title:'海阔演示·影片C',url:'hiker://page/vod_c',pic:'',desc:'演示C',cat:'动作'}," +
    "{title:'海阔演示·影片D',url:'hiker://page/vod_d',pic:'',desc:'演示D',cat:'科幻'}];" +
    "var cls=['全部','动作','喜剧','科幻'];";

  var DEMO = {
    home: DEMO_DB +
      "var r=[],i;if(MY_KEYWORD){for(i=0;i<db.length;i++)if(db[i].title.indexOf(MY_KEYWORD)>=0)r.push(db[i]);}" +
      "else if(input&&input!=='全部'){for(i=0;i<db.length;i++)if(db[i].cat===input)r.push(db[i]);}" +
      "else{r=db;}setResult({classes:cls,list:r});",
    cate: DEMO_DB +
      "var r=[],i;for(i=0;i<db.length;i++)if(!input||input==='全部'||db[i].cat===input)r.push(db[i]);" +
      "setResult({classes:cls,list:r});",
    search: DEMO_DB +
      "var r=[],i,k=MY_KEYWORD||'';for(i=0;i<db.length;i++)if(k===''||db[i].title.indexOf(k)>=0)r.push(db[i]);" +
      "setResult({classes:cls,list:r});",
    detail:
      "var base=input||'hiker://page/vod';\n" +
      "setResult({desc:'这是「'+MY_TITLE+'」的详情页，选集由 detail 规则产出。',\n" +
      "  episodes:[{name:'第1集',url:base+'/1.m3u8'},{name:'第2集',url:base+'/2.m3u8'},\n" +
      "            {name:'第3集',url:base+'/3.m3u8'},{name:'第4集',url:base+'/4.m3u8'}]});",
    play:
      "setResult(input);"
  };

  function demoSites() {
    var mk = function (key, name, group, color) {
      return {
        key: key, name: name, type: 3, api: '', ext: '', homePage: 'hiker://home',
        searchable: 1, changeable: 0, categories: ['全部', '动作', '喜剧', '科幻'],
        pic: '', group: group, source: '内置演示', color: color, siteKind: 'rule',
        rules: { home: DEMO.home, cate: DEMO.cate, search: DEMO.search, detail: DEMO.detail, play: DEMO.play, lazy: '' },
        raw: { title: name }
      };
    };
    return [
      mk('hk_demo_1', '演示·海阔规则站', '演示', '#7fd1ff'),
      mk('hk_demo_2', '演示·分类站点', '演示', '#5fd39a'),
      mk('hk_demo_3', '演示·搜索站点', '演示', '#ffb86b')
    ];
  }
  App.demoSites = demoSites;
  App.DEMO = DEMO;

  /**
   * 一份「演示配置口令」：站点自带 rules（js: 可执行片段）。
   * 用于证明真实闭环：录入口令 → unwrap/buildSites → 站点 → home/cate/detail/play
   * 全靠规则执行产出，而不是容器页硬编码结果。
   */
  App.demoConfig = function () {
    var mk = function (key, name, color) {
      return {
        key: key, name: name, type: 3, api: '', homePage: 'hiker://home',
        searchable: 1, categories: ['全部', '动作', '喜剧', '科幻'], color: color,
        rules: { home: DEMO.home, cate: DEMO.cate, search: DEMO.search,
                 detail: DEMO.detail, play: DEMO.play }
      };
    };
    return JSON.stringify({
      sites: [
        mk('demo_rule_1', '演示·规则站点A', '#5db4ff'),
        mk('demo_rule_2', '演示·规则站点B', '#4fd39a')
      ]
    });
  };

  /* =====================================================================
   * 5) 持久化（独立 WebView 的 localStorage，不写首页配置）
   * ===================================================================== */
  App.save = function (key, val) {
    try { if (global.localStorage) global.localStorage.setItem('hiker_app_' + key, JSON.stringify(val)); return true; }
    catch (e) { return false; }
  };
  App.load = function (key, dflt) {
    try { if (global.localStorage) { var v = global.localStorage.getItem('hiker_app_' + key); return v ? JSON.parse(v) : dflt; } }
    catch (e) {}
    return dflt;
  };

  /* 装配一次（页面加载时），并在没有运行时层时也能离线工作 */
  wire();

  global.HikerApp = App;
  if (typeof module !== 'undefined' && module.exports) module.exports = App;
})(typeof window !== 'undefined' ? window : this);
