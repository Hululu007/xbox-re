/*!
 * hiker_import.js —— 口令解析与站点生成
 *
 * 支持的形态（与海阔官方导入行为对齐）：
 *   · 云口令        云6oooole/xxxxxx/<id>（可带 (@密码) 或空格后跟密码）
 *   · 云1/2/5/7/9/10 各通道
 *   · 分享包装      小程序￥home_rule_v2￥base64://@标题@BASE64
 *   · 纯 base64     base64://@标题@BASE64
 *   · 裸规则 JSON   单对象 / 数组 / {sites:[...]} 配置
 *   · 订阅地址      http(s) 指向的 json
 *
 * 每一处失败都带「哪一步 / 原始片段」，不静默吞掉。
 */
(function (global) {
  'use strict';

  var HK = global.HikerEngine;
  var str = HK ? function (v) { return (v === null || v === undefined) ? '' : String(v); } : String;
  var trim = function (v) { return str(v).replace(/^[\s\uFEFF]+|[\s\uFEFF]+$/g, ''); };
  var b64d = HK.b64d, b64e = HK.b64e;

  var IMP = { version: '3.0.0' };

  /* 云口令通道表 */
  var CLOUD_FIND = /云([1-9]|10)oooole(\/[^\s]*)/;
  var CLOUD_FULL = /^云([1-9]|10)oooole(\/.*)$/;

  function cloudUrl(raw, pwd) {
    var m = CLOUD_FIND.exec(str(raw));
    if (!m) return { ok: false, error: '不是云口令（需云1~云10+oooole/…）' };
    var ver = m[1], path = m[2] || '';
    /* 口令后空格跟密码 */
    var tail = str(raw).slice(m.index + m[0].length).replace(/^\s+/, '');
    var p = pwd || '';
    if (!p && tail && tail.indexOf('小程序') !== 0 && tail.indexOf('http') !== 0) p = tail.split(/\s+/)[0];

    if (path.indexOf('/xxxxxx/') === 0) {
      var rest = path.slice(8).split(/\s/)[0];
      if (!rest) return { ok: false, error: '云6 口令缺少便签 ID' };
      if (p && rest.indexOf('(@') < 0) rest += '(@' + p + ')';
      return {
        ok: true, url: 'https://pasteme.tyrantg.com/api/getContent/' + rest,
        channel: '云6/pasteme', envelope: 'pasteme',
        headers: { referer: 'https://pasteme.tyrantg.com/', 'User-Agent': 'Mozilla/5.0' },
        password: p
      };
    }
    if (path.indexOf('/apidb/') === 0) {
      var key = path.slice(7).split(/[\s/]/)[0];
      return { ok: true, url: 'https://textdb.online/' + key, channel: '云2/textdb', outerB64: true, password: p };
    }
    if (ver === '1') {
      var id1 = path.indexOf('/raw/') === 0 ? path.slice(5) : path.slice(1);
      id1 = id1.split(/[\s/]/)[0];
      return { ok: true, url: 'https://pastebin.com/raw/' + id1, channel: '云1/pastebin', password: p };
    }
    if (ver === '5') return { ok: true, url: 'https://cmd.im/' + path.slice(1).split(/[\s/]/)[0], channel: '云5/cmd.im', password: p };
    if (ver === '7') return { ok: true, url: 'https://note.ms/' + path.slice(1).split(/[\s/]/)[0], channel: '云7/note.ms', textarea: true, password: p };
    if (ver === '9') return { ok: true, url: 'https://txtpad.cn/' + path.slice(1).split(/[\s/]/)[0], channel: '云9/txtpad', password: p };
    if (ver === '10') {
      var segs = path.split('/');
      var k10 = segs[segs.length - 1].split(/\s/)[0];
      return { ok: true, url: 'https://hassdtebin.com/raw/' + k10, fallback: 'https://hastebin.com/raw/' + k10, channel: '云10/hastebin', password: p };
    }
    return { ok: false, error: '暂不支持该云口令通道（云' + ver + '）' };
  }

  /* HTML 实体还原（云端便签偶尔把引号转义） */
  function htmlUnescape(v) {
    return str(v).replace(/&quot;/g, '"').replace(/&#39;/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  }

  /* pasteme 信封：{"return_code":0,"data":"…"} */
  function pastemeEnvelope(body) {
    if (body === null || body === undefined) return null;
    var t = str(body).replace(/^\uFEFF/, '').replace(/^\s+|\s+$/g, '');
    if (!t) return null;
    var o = null;
    try { o = JSON.parse(t); } catch (e) { o = null; }
    if (!o || typeof o !== 'object' || Object.prototype.toString.call(o) === '[object Array]') {
      return htmlUnescape(t);
    }
    var rc = 0;
    if (o.return_code !== undefined) rc = o.return_code;
    else if (o.code !== undefined) rc = o.code;
    else if (o.status !== undefined) rc = o.status;
    else if (o.success !== undefined) rc = o.success ? 0 : 1;
    if (Number(rc) !== 0) return null;
    var keys = ['data', 'content', 'text', 'value', 'result'], d = null;
    for (var i = 0; i < keys.length; i++) {
      if (o[keys[i]] !== undefined && o[keys[i]] !== null) { d = o[keys[i]]; break; }
    }
    if (d === null) return t;
    var out = (typeof d === 'string') ? d : JSON.stringify(d);
    out = htmlUnescape(out.replace(/^\uFEFF/, '')).replace(/^\s+|\s+$/g, '');
    return out || null;
  }

  /**
   * 还原分享包装，返回规则文本。
   * ￥home_rule_v2￥base64://@标题@BASE64  —— 这里必须继续往里剥 base64，
   * 这是早前版本最致命的一处：外层 ￥ 剥到 base64:// 就停了，随后 JSON.parse 失败，
   * 整条口令被判「无法识别」，表现就是「输入口令拿不到站点」。
   */
  function unwrap(text) {
    var t = trim(text);
    if (!t) return { ok: false, error: '内容为空' };
    var YEN = '\uFFE5';

    /* 1) 找出 base64:// 段（不论外面套了几层） */
    var bi = t.indexOf('base64://');
    if (bi >= 0) {
      var seg = t.slice(bi + 9);
      var parts = seg.split('@');
      var payload = (parts.length >= 3) ? parts.slice(2).join('@') : (parts.length === 2 ? parts[1] : parts[0]);
      var dec = b64d(payload);
      var dtrim = trim(dec);
      if (dtrim && (dtrim.charAt(0) === '{' || dtrim.charAt(0) === '[')) {
        return { ok: true, text: dtrim, kind: 'base64', title: parts.length >= 3 ? parts[1] : '' };
      }
      /* 可能是二次包装（base64 里还是分享口令） */
      if (dtrim && dtrim.indexOf(YEN) >= 0) {
        var inner = unwrap(dtrim);
        if (inner.ok) return inner;
      }
      if (dtrim && /^https?:\/\//.test(dtrim)) return { ok: true, text: dtrim, kind: 'url' };
    }

    /* 2) ￥home_rule￥ 后直接跟 JSON */
    if (t.indexOf(YEN) >= 0) {
      var ys = t.split(YEN);
      if (ys.length >= 3) {
        var type = trim(ys[1]);
        if (type === 'home_rule' || type === 'home_rule_v2') {
          var json = trim(ys.slice(2).join(YEN));
          if (json.charAt(0) === '{' || json.charAt(0) === '[') return { ok: true, text: json, kind: 'share', shareType: type };
        }
      }
    }

    /* 3) 裸 JSON / URL */
    if (t.charAt(0) === '{' || t.charAt(0) === '[') {
      try { JSON.parse(t); return { ok: true, text: t, kind: 'json' }; } catch (e) { /* 继续尝试 */ }
    }
    if (/^https?:\/\//i.test(t) || t.indexOf('hiker://') === 0) return { ok: true, text: t, kind: 'url' };

    return { ok: false, error: '无法识别的口令格式', snippet: t.slice(0, 120) };
  }

  function classify(raw) {
    var t = trim(raw);
    if (!t) return 'empty';
    if (CLOUD_FIND.test(t)) return 'cloud';
    if (t.indexOf('base64://') >= 0) return 'base64';
    if (t.indexOf('\uFFE5home_rule') >= 0) return 'share';
    if (t.charAt(0) === '{' || t.charAt(0) === '[') return 'json';
    if (/^https?:\/\//i.test(t) || t.indexOf('hiker://') === 0) return 'url';
    return 'token';
  }

  /* 多口令：换行/分号都能拆（结构化内容整行保留） */
  function splitMulti(text) {
    var lines = str(text).replace(/\r/g, '').split(/\n+/), out = [], i, j;
    for (i = 0; i < lines.length; i++) {
      var L = trim(lines[i]);
      if (!L) continue;
      var k = classify(L);
      if (k === 'json' || k === 'share' || k === 'base64' || k === 'cloud' || k === 'url') out.push(L);
      else {
        var segs = L.split(/[;；]/);
        for (j = 0; j < segs.length; j++) if (trim(segs[j])) out.push(trim(segs[j]));
      }
    }
    return out;
  }

  /* 取一条口令的内容 */
  function resolve(entry, opts) {
    opts = opts || {};
    var out = { ok: false, text: '', channel: '', error: '' };
    var kind = entry.kind || classify(entry.raw);

    if (kind === 'cloud') {
      var cu = cloudUrl(entry.raw, entry.password);
      if (!cu.ok) { out.error = cu.error; return out; }
      out.channel = cu.channel;
      var body = HK.req(cu.url, { headers: cu.headers || {}, timeout: 25000 });
      if (!body && cu.fallback) body = HK.req(cu.fallback, { timeout: 25000 });
      if (!body) { out.error = '云端没有返回内容（便签不存在 / 密码错 / 网络不通）'; return out; }
      if (cu.envelope === 'pasteme') {
        var env = pastemeEnvelope(body);
        /* [知识库] 云6 取回失败且口令带密码时，按海阔 PastemeImporter 的
         * rest(@pwd) 语义再取一次；案例 xingchen HkRuleManager.fetchCloudJson
         * 同款重试。首次失败常是「密码没拼进 URL」而不是便签不存在。 */
        if (env === null && cu.password && cu.url.indexOf('(@') < 0) {
          var retry = HK.req(cu.url + '(@' + cu.password + ')',
                             { headers: cu.headers || {}, timeout: 25000 });
          if (retry) env = pastemeEnvelope(retry);
        }
        if (env === null) {
          out.error = cu.password
            ? '便签密码错误或内容已删除（return_code≠0）'
            : '便签不存在或需要访问密码（请切「含密码」模式重填，return_code≠0）';
          return out;
        }
        body = env;
      }
      if (cu.outerB64) body = b64d(body);
      if (cu.textarea) {
        var m = /<textarea[^>]*>([\s\S]*?)<\/textarea>/i.exec(body);
        if (m) body = htmlUnescape(m[1]);
      }
      var uw = unwrap(body);
      if (!uw.ok) { out.error = uw.error + '（原文片段：' + str(uw.snippet || body).slice(0, 80) + '）'; return out; }
      out.ok = true; out.text = uw.text; out.title = uw.title || '';
      return out;
    }

    if (kind === 'url') {
      var u = str(entry.raw);
      var body2 = HK.req(u, { timeout: 25000 });
      if (!body2) { out.error = '地址没有返回内容'; return out; }
      var uw2 = unwrap(body2);
      out.ok = true; out.text = uw2.ok ? uw2.text : body2; out.channel = u;
      return out;
    }

    var u3 = unwrap(entry.raw);
    if (!u3.ok) { out.error = u3.error; return out; }
    out.ok = true; out.text = u3.text; out.channel = '本地解析'; out.title = u3.title || '';
    return out;
  }

  /* 一条规则对象 → 站点对象 */
  function normalizeSite(o, i, src) {
    if (!o || typeof o !== 'object') return null;
    var pages = o.pages;
    if (typeof pages === 'string') { try { pages = JSON.parse(pages); } catch (e) { pages = []; } }
    return {
      key: str(o.key || o.title || o.name || ('site' + i)),
      name: str(o.name || o.title || o.key || ('站点' + (i + 1))),
      type: str(o.type || 'video'),
      group: str(o.group || src || ''),
      pic: str(o.icon || o.pic || ''),
      author: str(o.author || ''),
      version: o.version || 0,
      ua: str(o.ua || 'mobile'),
      homePage: str(o.url || o.homePage || 'hiker://empty'),
      raw: o,
      pages: pages || [],
      rules: {
        home: str(o.find_rule || o.home_rule || ''),
        search: str(o.searchFind || o.search_rule || ''),
        detail: str(o.detail_find_rule || ''),
        sdetail: str(o.sdetail_find_rule || ''),
        play: str(o.last_chapter_rule || ''),
        pre: str(o.preRule || '')
      }
    };
  }

  /* 内容文本 → 站点数组 */
  function buildSites(text, meta) {
    meta = meta || {};
    var t = trim(text), d = null, arr = null, out = [], i;
    try { d = JSON.parse(t); } catch (e) { d = null; }

    if (d && Object.prototype.toString.call(d) === '[object Array]') arr = d;
    else if (d && typeof d === 'object') {
      var keys = ['sites', 'urls', 'data', 'list', 'items', 'depot'];
      for (i = 0; i < keys.length; i++) {
        if (Object.prototype.toString.call(d[keys[i]]) === '[object Array]') { arr = d[keys[i]]; break; }
      }
      if (!arr) arr = [d];
    }
    if (arr) {
      for (i = 0; i < arr.length; i++) {
        var site = normalizeSite(arr[i], i, meta.source || meta.title || '');
        if (site) { if (meta.title && !site.title) site.title = meta.title; out.push(site); }
      }
    }
    return out;
  }

  /**
   * 入口：口令文本 → 站点数组
   * opts: { password, mode }
   */
  function importText(text, opts) {
    opts = opts || {};
    var parts = splitMulti(text);
    var sites = [], notes = [], i, res, got;
    if (!parts.length) return { ok: false, sites: [], notes: ['没有解析到口令'] };

    for (i = 0; i < parts.length; i++) {
      var entry = { raw: parts[i], kind: classify(parts[i]), password: trim(opts.password || '') };
      res = resolve(entry, opts);
      if (!res.ok) { notes.push('口令#' + (i + 1) + ' [' + (res.channel || entry.kind) + ']：' + res.error); continue; }
      got = buildSites(res.text, { source: res.channel, title: res.title });
      for (var j = 0; j < got.length; j++) { got[j].channel = res.channel; }
      sites = sites.concat(got);
      notes.push('口令#' + (i + 1) + ' [' + res.channel + '] → ' + got.length + ' 个站点');
    }
    /* 去重 */
    var seen = {}, uniq = [];
    for (i = 0; i < sites.length; i++) {
      var kk = sites[i].key + '|' + sites[i].version;
      if (!seen[kk]) { seen[kk] = 1; uniq.push(sites[i]); }
    }
    return { ok: uniq.length > 0, sites: uniq, notes: notes };
  }

  IMP.cloudUrl = cloudUrl;
  IMP.unwrap = unwrap;
  IMP.classify = classify;
  IMP.splitMulti = splitMulti;
  IMP.resolve = resolve;
  IMP.buildSites = buildSites;
  IMP.importText = importText;
  global.HikerImport = IMP;
})(typeof window !== 'undefined' ? window : this);
