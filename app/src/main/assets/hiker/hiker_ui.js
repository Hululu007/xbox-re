/*!
 * hiker_ui.js —— 小程序界面层（站点管理 / 首页 / 分类 / 搜索 / 详情 / 播放）
 *
 * 页面模型和海阔一致：一条规则产出「条目数组」，每个条目有 col_type，
 * 由这里按 col_type 渲染成行（海报网格 / 侧栏按钮 / 输入框 / 富文本 …）。
 * 点条目的行为也按海阔语义分发：
 *   @lazyRule / @rule  -> 执行内联 JS -> 有结果就换页，没结果就当刷新
 *   hiker://page/...   -> 进详情
 *   http(s) + isVideo  -> 交给宿主播放器
 *   http(s) 其它       -> 打开网页（用 WebView）
 */
(function (global) {
  'use strict';

  var HK = global.HikerEngine;
  var IMP = global.HikerImport;
  var str = function (v) { return (v === null || v === undefined) ? '' : String(v); };

  var UI = { version: '3.0.0' };
  var $ = function (id) { return document.getElementById(id); };

  /* ------------------------------------------------------------------ 状态 */
  var STATE = {
    sites: [],          /* 已导入站点 */
    site: null,         /* 当前站点 */
    stack: [],          /* 页面栈 [{title, items, url}] */
    busy: false
  };
  UI.STATE = STATE;

  var LS_SITES = 'hiker_sites_v3';

  function loadSites() {
    try {
      var v = localStorage.getItem(LS_SITES);
      STATE.sites = v ? (JSON.parse(v) || []) : [];
    } catch (e) { STATE.sites = []; }
  }
  function saveSites() {
    try { localStorage.setItem(LS_SITES, JSON.stringify(STATE.sites)); } catch (e) {}
  }
  UI.saveSites = saveSites;

  /* ------------------------------------------------------------------ 提示 */
  var toastTimer = null;
  function toast(msg) {
    var el = $('toast');
    if (!el) return;
    el.textContent = str(msg);
    el.classList.add('show');
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2200);
  }
  UI.toast = toast;

  function setBusy(b, text) {
    STATE.busy = b;
    var m = $('busy');
    if (!m) return;
    if (b) { m.textContent = text || '加载中…'; m.classList.add('show'); }
    else m.classList.remove('show');
  }

  /* ------------------------------------------------------------------ 站点管理 */
  function renderSites() {
    var box = $('siteList');
    if (!box) return;
    if (!STATE.sites.length) {
      box.innerHTML = '<div class="empty"><span class="big">📺</span>还没有导入小程序<br>'
        + '<span class="dim">把口令粘进来，或点下面的「导入」</span></div>';
      return;
    }
    var html = '';
    for (var i = 0; i < STATE.sites.length; i++) {
      var s = STATE.sites[i];
      html += '<div class="card site" data-idx="' + i + '">'
        + '<div class="site-ic">'
        + esc(str(s.name).slice(0, 1)) + '</div>'
        + '<div class="site-nm">' + esc(s.name) + '</div>'
        + '<div class="site-mt">'
        + '<span class="pill">' + esc(s.type || 'video') + '</span>'
        + (s.group ? '<span class="pill">' + esc(s.group) + '</span>' : '')
        + '<span class="pill">' + (s.pages && s.pages.length ? s.pages.length + ' 模块' : '单页') + '</span>'
        + '</div>'
        + '<button class="del" data-del="' + i + '">移除</button>'
        + '</div>';
    }
    box.innerHTML = html;
  }

  function pickColor(i) {
    var c = ['#5db4ff', '#4fd39a', '#ffb45e', '#c58bff', '#ff8fa3', '#6fe0d0'];
    return c[i % c.length];
  }
  function esc(s) {
    return str(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  UI.esc = esc;

  /* ------------------------------------------------------------------ 导入 */
  function openImport() {
    $('importMask').classList.add('show');
    var ta = $('importText');
    ta.value = '';
    ta.focus();
  }
  function closeImport() { $('importMask').classList.remove('show'); }

  function doImport() {
    var text = $('importText').value;
    if (!text || !text.trim()) { toast('先把口令粘进来'); return; }
    setBusy(true, '正在取回规则…');
    /* 云口令要联网，放一小会儿让 loading 先渲染出来 */
    setTimeout(function () {
      var r;
      try { r = IMP.importText(text, {}); }
      catch (e) { setBusy(false); toast('解析出错：' + (e && e.message)); return; }
      setBusy(false);
      if (!r.ok) {
        toast(r.notes && r.notes[0] ? r.notes[0] : '没有解析到站点');
        log(r.notes);
        return;
      }
      for (var i = 0; i < r.sites.length; i++) {
        var s = r.sites[i], dup = -1;
        for (var j = 0; j < STATE.sites.length; j++) {
          if (STATE.sites[j].key === s.key) { dup = j; break; }
        }
        if (dup >= 0) STATE.sites[dup] = s; else STATE.sites.push(s);
      }
      saveSites();
      renderSites();
      closeImport();
      toast('导入成功：' + r.sites.length + ' 个站点');
      log(r.notes);
    }, 30);
  }

  function log(lines) {
    if (!lines) return;
    try {
      var el = $('diagLog');
      if (el) el.textContent = [].concat(lines).join('\n') + '\n' + el.textContent;
    } catch (e) {}
  }
  UI.log = log;

  /* ------------------------------------------------------------------ 打开站点 */
  function openSite(idx) {
    var s = STATE.sites[idx];
    if (!s) return;
    STATE.site = s;
    STATE.stack = [];
    var rule = s.raw;
    if (typeof rule.pages === 'string') { try { rule.pages = JSON.parse(rule.pages); } catch (e) { rule.pages = []; } }
    HK.setPage({ rule: rule, ruleName: s.name, url: s.homePage, keyword: '', page: 1, title: s.name, pic: '' });
    /* 模块缓存按站点隔离，切站点要清 */
    HK.STORE.modules = {};
    showView('rule');
    $('ruleTitle').textContent = s.name;
    /* preRule 先跑一次（阅动漫用它初始化 myCollection / 检查更新） */
    if (s.rules.pre) {
      try { HK.run(s.rules.pre, {}); } catch (e) {}
    }
    /* 合集类规则的 preRule 常常是 evalPrivateJS（海阔用内置密钥加密的），
     * WebView 里解不开 —— 这时 remoteUrl / myCollection 会是空，dataLoad 直接返回 0 条。
     * 兜底：用合集包自带的信息补上（包里 require.json 的源地址 + 自身标题）。 */
    if (!HK.getMyVar('remoteUrl')) {
      if (s.remoteUrl) HK.setMyVar('remoteUrl', s.remoteUrl);
      else if (s.libUrls && s.libUrls.length) HK.setMyVar('remoteUrl', s.libUrls[0]);
    }
    if (!HK.getMyVar('myCollection')) HK.setMyVar('myCollection', s.name);
    runPage(s.rules.home, { MY_URL: s.homePage, MY_PAGE: 1, input: '', MY_KEYWORD: '' }, s.name);
  }
  UI.openSite = openSite;

  /* 执行一个页面规则并把结果推进页面栈 */
  function runPage(code, vars, title) {
    if (!code) { toast('这个站点没有配置该页规则'); return; }
    STATE.lastRun = { code: code, vars: vars || {}, title: title || '' };
    setBusy(true, title || '加载中…');
    setTimeout(function () {
      HK.resetOut();
      var r = HK.run(code, vars || {});
      setBusy(false);
      if (!r.ok) {
        showError('规则执行失败', r.err);
        log('[' + (title || '') + '] ' + r.err);
        return;
      }
      var items = normalizeResult(r.result);
      if (!items.length) {
        showError('没有拿到内容', '规则执行完成，但返回为空。可以换一个分类或稍后再试。');
        return;
      }
      STATE.stack.push({ title: title || '', items: items, url: HK.page.url });
      renderItems(items, title);
    }, 30);
  }
  UI.runPage = runPage;

  /* setResult 的几种形态统一成条目数组 */
  function normalizeResult(v) {
    if (v === null || v === undefined) return [];
    if (typeof v === 'string') {
      var d = null;
      try { d = JSON.parse(v); } catch (e) { d = null; }
      if (d) return normalizeResult(d);
      return [{ title: v.slice(0, 120), col_type: 'text_1', url: 'hiker://empty' }];
    }
    if (Object.prototype.toString.call(v) === '[object Array]') return v;
    if (typeof v === 'object') {
      var keys = ['list', 'data', 'items', 'result', 'videos'];
      for (var i = 0; i < keys.length; i++) if (Object.prototype.toString.call(v[keys[i]]) === '[object Array]') return v[keys[i]];
    }
    return [];
  }

  /* ------------------------------------------------------------------ 渲染 */
  var POSTER = { 'movie_1': 1, 'movie_2': 1, 'movie_3': 1, 'movie_4': 1, 'movie_1_vertical_pic': 1, 'icon_round_small_4': 1, 'icon_small_4': 1, 'movie_2_vertical_pic': 1 };

  /* 统一的 col_type 归一：显式声明优先；没声明但带图的按海报卡处理（很多合集规则就是这样）。
   * 必须内外层共用同一函数 —— 之前外层用补图后的类型、内层用原始类型，
   * 导致分组循环 i 不前进、字符串拼到爆内存。 */
  function ctOf(it) {
    if (!it) return 'text_1';
    if (it.col_type) return str(it.col_type);
    return (it.pic_url || it.pic || it.img) ? 'movie_3' : 'text_1';
  }

  function renderItems(items, title) {
    var box = $('content');
    if (!box) return;
    /* 记住条目，供 updateItem/findItem 用 */
    for (var i = 0; i < items.length; i++) {
      if (items[i] && items[i].extra && items[i].extra.id) HK.STORE.items[str(items[i].extra.id)] = items[i];
    }
    var html = '', i = 0;
    while (i < items.length) {
      var it = items[i] || {};
      var ct = ctOf(it);
      if (ct === 'movie_1_vertical_pic_blur') { html += renderHero(it); i++; continue; }
      if (POSTER[ct]) {
        var group = [];
        while (i < items.length && POSTER[ctOf(items[i])]) { group.push(items[i]); i++; }
        html += renderPosterGrid(group);
        continue;
      }
      html += renderSingle(it, ct);
      i++;
    }
    box.innerHTML = html || '<div class="empty">没有内容</div>';
    box.scrollTop = 0;
    $('ruleTitle').textContent = title || (STATE.site ? STATE.site.name : '');
    $('backBtn').style.display = 'flex';
  }
  UI.renderItems = renderItems;

  function renderHero(it) {
    var pic = str(it.pic_url || it.pic || it.img || '');
    var ex = it.extra || {};
    var initial = esc(str(it.title).slice(0, 1));
    /* 没有封面（或封面是解不出来的加密图）时给占位块，避免详情页顶部一片空白 */
    var picHtml = '<div class="hero-pic">' + (pic
      ? '<img src="' + esc(pic) + '" referrerpolicy="no-referrer" onerror="this.parentNode.innerHTML=\'' + initial + '\'">'
      : '<span class="hero-ph">' + initial + '</span>') + '</div>';
    return '<div class="hero-card' + (ex.gradient ? ' grad' : '') + '">' + picHtml
      + '<div class="hero-info"><h2>' + esc(it.title) + '</h2>'
      + (it.desc ? '<p>' + esc(it.desc) + '</p>' : '')
      + '</div></div>';
  }

  function renderPosterGrid(group) {
    var cols = 3;
    var html = '<div class="grid" style="grid-template-columns:repeat(' + cols + ',1fr)">';
    for (var i = 0; i < group.length; i++) {
      var it = group[i] || {};
      var pic = str(it.pic_url || it.pic || it.img || '');
      html += '<div class="poster" data-i="' + i + '" data-ref="' + esc(refId(it)) + '">'
        + '<div class="ph">' + (pic
          ? '<img src="' + esc(pic) + '" referrerpolicy="no-referrer" onerror="this.style.display=\'none\'">'
          : esc(str(it.title).slice(0, 4)))
        + '</div>'
        + '<div class="pt"><b>' + esc(it.title) + '</b>'
        + (it.desc ? '<span>' + esc(it.desc) + '</span>' : '')
        + '</div></div>';
    }
    html += '</div>';
    return html;
  }

  function renderSingle(it, ct) {
    var title = str(it.title);
    var bg = (it.extra && it.extra.backgroundColor) ? ' style="background:' + esc(it.extra.backgroundColor) + '"' : '';
    switch (ct) {
      case 'line':
      case 'line_blank':
        return '<div class="hr"></div>';
      case 'text_1':
        return '<div class="sect-t">' + esc(stripTags(title)) + '</div>';
      case 'text_center_1':
        return '<div class="center-line" data-ref="' + esc(refId(it)) + '">' + esc(stripTags(title)) + '</div>';
      case 'text_2':
      case 'text_3':
        return '<button class="row-btn" data-ref="' + esc(refId(it)) + '"' + bg + '>' + esc(stripTags(title)) + '</button>';
      case 'scroll_button':
        return '<button class="chip" data-ref="' + esc(refId(it)) + '"' + bg + '>' + esc(stripTags(title)) + '</button>';
      case 'input':
        return '<div class="input-row"><input class="f" placeholder="' + esc(it.desc || '输入关键词搜索') + '" '
          + 'data-ref="' + esc(refId(it)) + '" value="' + esc(HK.page.keyword || '') + '">'
          + '<button class="btn go" data-ref="' + esc(refId(it)) + '">搜索</button></div>';
      case 'rich_text':
        return '<div class="rich" data-ref="' + esc(refId(it)) + '">' + title + '</div>';
      case 'icon_round_small_4':
      case 'icon_small_4':
        return '<button class="row-btn" data-ref="' + esc(refId(it)) + '">' + esc(stripTags(title)) + '</button>';
      default:
        return '<button class="row-btn" data-ref="' + esc(refId(it)) + '"' + bg + '>' + esc(stripTags(title)) + '</button>';
    }
  }

  function stripTags(s) { return str(s).replace(/<[^>]+>/g, ''); }

  /* 条目在数组里的索引：用 title+url 的短哈希，避免把整条塞进 DOM */
  var REFS = [];
  function refId(it) {
    for (var i = 0; i < REFS.length; i++) if (REFS[i] === it) return 'r' + i;
    REFS.push(it);
    if (REFS.length > 3000) REFS = REFS.slice(-1500);
    return 'r' + (REFS.length - 1);
  }
  function byRef(id) {
    var m = /^r(\d+)$/.exec(str(id));
    if (!m) return null;
    return REFS[parseInt(m[1], 10)] || null;
  }
  UI.byRef = byRef;

  function showError(title, detail) {
    var box = $('content');
    if (!box) return;
    box.innerHTML = '<div class="empty"><span class="big">⚠️</span>' + esc(title)
      + '<br><span class="dim">' + esc(detail || '') + '</span>'
      + '<br><br><button class="btn gh" id="retryBtn">重试</button></div>';
    var b = $('retryBtn');
    if (b) b.onclick = function () { if (STATE.stack.length) renderItems(STATE.stack[STATE.stack.length - 1].items, STATE.stack[STATE.stack.length - 1].title); };
  }
  UI.showError = showError;

  /* ------------------------------------------------------------------ 点击分发 */
  function onContentClick(ev) {
    var t = ev.target;
    /* 输入框的搜索按钮 */
    var go = closest(t, '.btn.go');
    if (go) { doSearchByRef(go.getAttribute('data-ref')); return; }

    var holder = closest(t, '[data-ref]');
    if (!holder) return;
    var ref = holder.getAttribute('data-ref');
    var it = byRef(ref);
    if (!it) return;

    /* input 条目：点空白不做事，由按钮触发 */
    if (str(it.col_type) === 'input') return;
    handleItem(it);
  }

  function doSearchByRef(ref) {
    var holder = document.querySelector('[data-ref="' + ref + '"]');
    if (!holder) return;
    var inp = holder.parentNode ? holder.parentNode.querySelector('input') : null;
    if (!inp) inp = holder;
    var kw = inp.value || '';
    if (!kw.trim()) { toast('请输入关键词'); return; }
    doSearch(kw.trim());
  }

  function doSearch(kw) {
    if (!STATE.site) return;
    HK.setPage({ keyword: kw, url: 'hiker://search?s=' + encodeURIComponent(kw) });
    var rule = STATE.site.rules.search;
    if (!rule) { toast('这个站点没有搜索规则'); return; }
    runPage(rule, {
      MY_KEYWORD: kw, MY_URL: 'hiker://search?s=' + encodeURIComponent(kw),
      input: '', MY_PAGE: 1
    }, '搜索：' + kw);
  }
  UI.doSearch = doSearch;

  function handleItem(it) {
    var ex = it.extra || {};
    var url = str(it.url);
    /* 海阔的合集框架里，卡片的真实动作常放在 extra.url（item.url 只是个占位壳，
     * 比如 hiker://page/frameLoad）。这里优先用带执行语义的 extra.url。 */
    if (ex.url && typeof ex.url === 'string' && /(\.js:|lazyRule|@rule=)/.test(ex.url)) url = ex.url;

    /* input 条目的 onChange */
    if (str(it.col_type) === 'input' && ex.onChange) {
      try { HK.runInline(str(ex.onChange), { input: HK.page.keyword || '' }); } catch (e) {}
      return;
    }

    if (!url || url === 'javascript:;') return;

    var pu = HK.parseUrl(url);

    /* 0) 输入框链接：$(默认值, 提示).input(回调) */
    if (pu.inputArg && pu.lazy) {
      var arg = pu.inputArg;
      var val = window.prompt(str(arg.d || '请输入'), str(arg.v || ''));
      if (val === null) return;
      var inputCode = pu.lazy;
      setBusy(true, '处理中…');
      setTimeout(function () {
        HK.resetOut();
        var rr = HK.runInline(inputCode, { input: val, MY_URL: HK.page.url });
        setBusy(false);
        if (!rr.ok) { toast('执行失败：' + rr.err); return; }
        var its = HK.OUT.hasResult ? normalizeResult(HK.OUT.result) : null;
        if (its && its.length) { STATE.stack.push({ title: it.title, items: its, url: HK.page.url }); renderItems(its, it.title); }
        else refreshCurrent();
      }, 20);
      return;
    }

    /* 1) 内联规则：@lazyRule / @rule=js: */
    if (pu.lazy || pu.rule) {
      var code = pu.lazy || pu.rule;
      setBusy(true, '处理中…');
      setTimeout(function () {
        HK.resetOut();
        var r = HK.runInline(code, { input: '', MY_URL: HK.page.url });
        setBusy(false);
        if (!r.ok) { toast('执行失败：' + r.err); log(r.err); return; }
        var back = r.value;
        var items = HK.OUT.hasResult ? normalizeResult(HK.OUT.result) : null;
        if (items && items.length) { STATE.stack.push({ title: it.title, items: items, url: HK.page.url }); renderItems(items, it.title); return; }
        /* 没有结果就当作刷新当前页 */
        if (typeof back === 'string' && back && back !== 'hiker://empty' && back.indexOf('hiker://empty#') !== 0) {
          navigate(back); return;
        }
        refreshCurrent();
      }, 20);
      return;
    }

    /* 2) 直接是视频地址 */
    if (pu.video || /\.(m3u8|mp4|flv|mkv)(\?|$)/i.test(url)) {
      PLAY_TARGET = it;
      var ok = HK.play(url, it.title, it.pic_url || '');
      toast(ok ? '正在播放：' + it.title : '交给播放器失败');
      return;
    }

    /* 3) 搜索协议 */
    if (pu.search !== null && pu.search !== undefined && pu.search !== '') { doSearch(pu.search); return; }

    /* 4) 站内跳转（hiker://page/…、hiker://empty?…）→ 重新跑详情/列表规则 */
    if (/^hiker:\/\//.test(url)) { navigate(url); return; }

    /* 5) 普通网页 */
    if (/^https?:\/\//.test(url)) {
      if (STATE.site && STATE.site.rules.detail) { navigate(url); return; }
      openWeb(url);
      return;
    }
  }
  UI.handleItem = handleItem;

  var PLAY_TARGET = null;

  /* 站内跳转：把 URL 交给「详情或二级列表」规则 */
  function navigate(url) {
    if (!STATE.site) return;
    HK.setPage({ url: url, title: PLAY_TARGET ? PLAY_TARGET.title : HK.page.title, pic: PLAY_TARGET ? PLAY_TARGET.pic_url : '' });
    var code = STATE.site.rules.detail || STATE.site.rules.sdetail || STATE.site.rules.home;
    runPage(code, { MY_URL: url, MY_TITLE: HK.page.title, MY_PIC: HK.page.pic, input: url, MY_PAGE: 1 },
      PLAY_TARGET ? PLAY_TARGET.title : '详情');
  }

  function refreshCurrent() {
    if (!STATE.site) return;
    HK.setPage({ url: STATE.site.homePage });
    runPage(STATE.site.rules.home, { MY_URL: STATE.site.homePage, MY_PAGE: 1, input: '', MY_KEYWORD: '' }, '首页');
  }

  function back() {
    if (STATE.stack.length > 1) {
      STATE.stack.pop();
      var top = STATE.stack[STATE.stack.length - 1];
      renderItems(top.items, top.title);
    } else {
      showView('sites');
    }
  }
  UI.back = back;

  function openWeb(url) {
    var w = window.open(url, '_blank');
    if (!w) toast('打不开外链：' + url);
  }

  function closest(el, sel) {
    while (el && el !== document) {
      if (el.matches && el.matches(sel)) return el;
      el = el.parentNode;
    }
    return null;
  }

  /* ------------------------------------------------------------------ 视图 */
  function showView(v) {
    var a = $('viewSites'), b = $('viewRule');
    if (!a || !b) return;
    a.style.display = (v === 'sites') ? 'block' : 'none';
    b.style.display = (v === 'rule') ? 'block' : 'none';
    $('backBtn').style.display = (v === 'rule') ? 'flex' : 'none';
  }

  /* ------------------------------------------------------------------ 启动 */
  function boot() {
    loadSites();
    renderSites();

    /* 站点卡片 */
    $('siteList').addEventListener('click', function (ev) {
      var del = closest(ev.target, '[data-del]');
      if (del) {
        var di = parseInt(del.getAttribute('data-del'), 10);
        STATE.sites.splice(di, 1); saveSites(); renderSites();
        return;
      }
      var card = closest(ev.target, '.card.site');
      if (card) { openSite(parseInt(card.getAttribute('data-idx'), 10)); }
    });

    /* 顶部操作（逐个判空：任何一个按钮缺失都不能中断后面的绑定） */
    var on = function (id, fn) { var el = $(id); if (el) el.onclick = fn; };
    on('importBtn', openImport);
    on('importBtn2', openImport);
    on('importCancel', closeImport);
    on('importOk', doImport);
    on('backBtn', back);
    on('clipBtn', function () {
      if (navigator.clipboard && navigator.clipboard.readText) {
        navigator.clipboard.readText().then(function (t) { $('importText').value = t; }, function () { toast('读剪贴板失败，请手动粘贴'); });
      } else { toast('请手动粘贴口令'); }
    });
    $('content').addEventListener('click', onContentClick);

    /* 规则事件 → 界面 */
    var oldEmit = HK.emit;
    HK.emit = function (name, payload) {
      try { oldEmit(name, payload); } catch (e) {}
      if (name === 'hk.toast') toast(payload);
      else if (name === 'hk.error') log('ERROR ' + payload);
      else if (name === 'hk.log') log(str(payload));
      else if (name === 'hk.copy') { try { navigator.clipboard.writeText(str(payload)); } catch (e) {} toast('已复制'); }
      else if (name === 'hk.refresh') {
        /* 规则里 refreshPage() 的语义就是「重跑当前页规则」（分页按钮、分类按钮都靠它） */
        if (STATE.lastRun && !STATE.refreshing) {
          STATE.refreshing = true;
          setTimeout(function () {
            var lr = STATE.lastRun;
            STATE.refreshing = false;
            if (lr) runPage(lr.code, lr.vars, lr.title);
          }, 16);
        }
      }
      else if (name === 'hk.updateItem') {
        /* updateItem(id, patch)：条目在原地改（展开/收起简介、切线路选中态都靠它），不换页 */
        var args = payload;
        if (Object.prototype.toString.call(args) === '[object Array]' && STATE.stack.length) {
          var id = str(args[0]), patch = args[1] || {};
          var cur = HK.STORE.items[id];
          if (cur) { for (var kk in patch) if (Object.prototype.hasOwnProperty.call(patch, kk)) cur[kk] = patch[kk]; }
          var top = STATE.stack[STATE.stack.length - 1];
          renderItems(top.items, top.title);
        }
      }
      else if (name === 'hk.back') { back(); }
    };

    showView('sites');
  }

  UI.boot = boot;
  global.HikerUI = UI;
})(typeof window !== 'undefined' ? window : this);
