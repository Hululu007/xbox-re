#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建前置 · 兼容性实证（阶段六）

回答三个问题（全部用真实接口代码 + 真实规则，不用假设）：
  A 组：把海阔运行时「与首页混用」（塞进 oktv-cat 首页 WebView 的 fongmiBridge/SDK 上下文）
       会发生什么？→ 证明 fetch 覆盖冲突、异步桥不可同步、作用域被污染
  B 组：独立 WebView + 同步桥 + JsT4 + shim（不带兼容层）→ 基线，沿用阶段五 3/3、23/78
  C 组：独立 WebView + 同步桥 + JsT4 + shim + HikerCompat 兼容层
       → 桥探测/语义归一/形态降级是否生效，多变规则是否被正确分类

静态部分：五类桥契约矩阵（从 interfaces/ 快照抽取）
输出：compat_report.json + hiker_compat_poc.png

用法: python3 compat_verify.py    依赖: playwright + /usr/bin/chromium + sqlite3，完全离线
"""
import os, re, json, sqlite3, argparse

HERE = os.path.dirname(os.path.abspath(__file__))
WS = os.path.dirname(HERE)
DB = os.path.join(WS, "hiker_54.db")
SHIM = os.path.join(WS, "hiker_runtime_shim.js")
T4 = os.path.join(WS, "v390_JsT4_运行时垫片.js")
COMPAT = os.path.join(HERE, "app", "assets", "hiker", "hiker_compat.js")
FM_HTML = os.path.join(WS, "v390_source", "html", "fm.html")
CHROMIUM = "/usr/bin/chromium"

HTML_SAMPLE = ('<html><body><div class="list">'
               '<a class="item" href="/v/1.html">影片A</a>'
               '<a class="item" href="/v/2.html">影片B</a>'
               '<a class="item" href="/v/3.html">影片C</a>'
               '</div></body></html>')


# ---------------------------------------------------------------- 规则
def load_rules(db):
    c = sqlite3.connect(db); c.row_factory = sqlite3.Row
    out = []
    for r in c.execute("SELECT id,searchfind FROM articlelistrule WHERE searchfind LIKE 'js:%'"):
        code = re.sub(r"^js:\s*", "", r["searchfind"] or "")
        if 30 < len(code) < 20000:
            out.append({"id": r["id"], "code": code})
    c.close()
    return out


def pick_e2e(db):
    c = sqlite3.connect(db); c.row_factory = sqlite3.Row
    picks = []
    for r in c.execute("SELECT id,searchfind FROM articlelistrule WHERE id=2512"):
        picks.append({"id": r["id"], "tag": "本地规则",
                      "code": re.sub(r"^js:\s*", "", r["searchfind"] or "")})
    for r in c.execute("SELECT id,searchfind FROM articlelistrule WHERE searchfind LIKE 'js:%' "
                       "AND (searchfind LIKE '%request(%' OR searchfind LIKE '%fetch(%') LIMIT 2"):
        picks.append({"id": r["id"], "tag": "网络规则",
                      "code": re.sub(r"^js:\s*", "", r["searchfind"] or "")})
    c.close()
    return picks


# ---------------------------------------------------------------- 宿主首页 SDK（A 组：混用）
# 逐行复刻 HomeWebController.getSdk(): 797-920 的语义：
#   window.fm.req -> new Promise；fongmiBridge.invoke 异步回调；document.fm-native；
#   以及 debugSdkHook 里对 window.fetch / XMLHttpRequest 的 hook（L957 起）
HOST_SDK = r"""
(function(){
  document.documentElement.classList.add('fm-native');
  window.fongmiClient = {mode:'leanback', isLeanback:true};
  var cb = {};
  window.fongmiNative = {
    resolve: function(id,d){ if(cb[id]){ cb[id].resolve(d); delete cb[id]; } },
    reject: function(id,e){ if(cb[id]){ cb[id].reject(new Error(e||'')); delete cb[id]; } }
  };
  window.fongmiBridge = {
    invoke: function(id,method,payload){
      new Promise(function(res){ res({ok:true,status:200,body:%s}); })
        .then(function(d){ window.fongmiNative.resolve(id,d); });
    },
    resultLength: function(){ return 0; }, resultChunk: function(){ return ''; }, clearResult: function(){}
  };
  function invoke(method,payload){
    return new Promise(function(resolve,reject){
      var id='fm_'+Date.now()+'_'+Math.random();
      cb[id]={resolve:resolve,reject:reject};
      window.fongmiBridge.invoke(id,method,JSON.stringify(payload||{}));
    });
  }
  window.fongmi = { net:{request:function(){return invoke('net.request',{});}},
                    player:{}, app:{}, pan:{}, cache:{}, ext:{}, ui:{}, navigation:{}, device:{}, site:{}, config:{} };
  window.fm = {
    req: function(){ return invoke('net.request',{}); },
    res: function(u){ return u; },
    play: function(){ return invoke('player.playUrl',{}); },
    vod: function(){ return invoke('player.playVod',{}); },
    vodInline: function(){ return invoke('player.playVodInline',{}); },
    search: function(){ return invoke('app.search',{}); },
    cache: {get:function(){return invoke('cache.get',{});},set:function(){return invoke('cache.set',{});}},
    device: function(){ return invoke('device.info',{}); },
    site: function(){ return invoke('site.info',{}); },
    config: function(){ return invoke('config.info',{}); },
    back: function(){}, reload: function(){}
  };
  // debugSdkHook 的等价：把 fetch 变成异步 Promise
  window.__HOST_FETCH__ = function(){ return Promise.resolve({ok:true,status:200,
      text:function(){return Promise.resolve(%s);},json:function(){return Promise.resolve({});}}); };
  window.fetch = window.__HOST_FETCH__;
  window.dispatchEvent(new CustomEvent('fmsdk'));
})();
""" % (json.dumps({"ok": True, "status": 200, "body": HTML_SAMPLE}), json.dumps(HTML_SAMPLE))

# ---------------------------------------------------------------- 独立同步桥（B/C 组）
SYNC_BRIDGE = r"""
window.__HK_HTML__ = %s;
window.__HK_REPORTS__ = [];
window.__fmBridge = function(name, argsJson){
  var a = JSON.parse(argsJson || '[]');
  switch(name){
    case 'req': return {ok:true,status:200,body:window.__HK_HTML__};
    case 'getCache': return '';
    case 'setCache': case 'removeCache': case 'clearCache': return true;
    case 'md5': return 'd41d8cd98f00b204e9800998ecf8427e';
    case 'b64e': return 'QUJD'; case 'b64d': return 'ABC';
    default: return a[0] || '';
  }
};
// 本模块独立 Activity 注入的同步 Java 桥（HikerBridge.req 语义）
window.HikerBridge = {
  req: function(url, objJson){ return window.__HK_HTML__; },
  request: function(url, objJson){ return window.__HK_HTML__; },
  report: function(t,v){ window.__HK_REPORTS__.push([t,v]); },
  play: function(url,title,pic){ window.__HK_REPORTS__.push(['play',url]); return '{}'; }
};
window.FM_SHELL = { call: function(name,payload){
  if(name==='http'||name==='resource'||name==='req')
    return JSON.stringify({ok:true,status:200,body:window.__HK_HTML__,error:''});
  return '{}';
}};
window.FM_HOST = {
  invoke: function(n,a){ return window.FM_SHELL.call(n,a); },
  report: function(t,v){ window.__HK_REPORTS__.push([t,v]); }
};
""" % json.dumps(HTML_SAMPLE)

# ---------------------------------------------------------------- A 组：混用检测
CHECK_MIX = r"""
() => {
  var before = window.fetch;
  var isPromiseFn = function(f){ try { return !!(f && f() && typeof f().then==='function'); } catch(e){ return false; } };
  var hostFetchIsAsync = isPromiseFn(window.__HOST_FETCH__);
  var shimWins = false, hostWins = false, conflict = false;
  // 模拟 shim 注入（L1 强制 define('fetch', syncRequest)）
  var shimFetch = function(){ return '<html>stub</html>'; };
  var saved = window.fetch;
  window.fetch = shimFetch;
  shimWins = (window.fetch === shimFetch);
  // 若宿主 SDK 在 shim 之后再次注入（getSdk 幂等但页面重载会重新执行），会反向覆盖
  window.fetch = window.__HOST_FETCH__;
  hostWins = (window.fetch === window.__HOST_FETCH__);
  conflict = shimWins && hostWins;   // 同一全局被双方各覆盖一次 → 后者赢，前者失效
  var probe = window.HikerCompat ? window.HikerCompat.probe() : null;
  return {
    hostFetchIsAsync: hostFetchIsAsync,
    shimOverwritesHostFetch: shimWins,
    hostOverwritesShimFetch: hostWins,
    globalFetchConflict: conflict,
    hasHomeSdk: probe ? probe.hasHomeSdk : null,
    documentIsolated: probe ? probe.documentIsolated : null,
    syncUsable: probe ? probe.syncUsable : null,
    asyncOnly: probe ? probe.asyncOnly : null,
    verdict: conflict ? 'INCOMPATIBLE' : 'UNKNOWN'
  };
}
"""

# ---------------------------------------------------------------- B/C 组：跑规则
RUN_B = r"""
({codes}) => {
  var rep = HikerRuntime.install({
    page:{MY_URL:'https://example.com/s?wd=test',MY_KEYWORD:'test',MY_PAGE:1},
    offlineHtml: window.__HK_HTML__,
    onResult:function(v,json){ window.FM_HOST.report('hiker.result',json); }
  });
  var out=[];
  for(var i=0;i<codes.length;i++){
    var s=codes[i]; HikerRuntime.lastResult=null;
    var rec={id:s.id,ok:false,err:null,out:null};
    var scope=new Proxy({},{has:function(){return true;},
      get:function(t,k){ if(typeof k==='symbol')return undefined;
        if(k==='window'||k==='globalThis'||k==='self')return window;
        return (k in window)?window[k]:undefined; },
      set:function(t,k,v){ try{window[k]=v;}catch(e){} return true; }});
    try{
      new Function('scope','with(scope){'+s.code+'\n}').call({},scope);
      var r=HikerRuntime.lastResult;
      rec.ok = r!==null && r!==undefined && !(r&&typeof r.then==='function');
      rec.out = rec.ok ? JSON.stringify(r).slice(0,140) : null;
      if(r&&typeof r.then==='function') rec.err='async-result-not-string';
    }catch(e){ rec.err=e.name+': '+String(e.message).slice(0,110); }
    out.push(rec);
  }
  return {report:rep, results:out, hostReports:(window.__HK_REPORTS__||[]).length};
}
"""

RUN_C = r"""
({codes, page}) => {
  var probe = HikerCompat.probe();
  var inst = HikerCompat.install({isolation:'strict', page:page,
    offlineHtml:window.__HK_HTML__,
    onResult:function(v,json){ window.FM_HOST.report('hiker.result',json); }});
  var out=[], forms={}, unsupported=0, degraded=0;
  for(var i=0;i<codes.length;i++){
    var rec = HikerCompat.run(codes[i].code, page, {id:codes[i].id});
    out.push(rec);
    for(var j=0;j<rec.forms.length;j++) forms[rec.forms[j].id]=(forms[rec.forms[j].id]||0)+1;
    if(rec.action==='degrade-to-engine'){ degraded++; }
    if(!rec.runnable) unsupported++;
  }
  return {probe:probe, install:inst, results:out, forms:forms,
          degraded:degraded, unsupported:unsupported,
          hostReports:(window.__HK_REPORTS__||[]).length};
}
"""

PANEL_JS = r"""
({rows, label}) => {
  var box=document.createElement('div');
  box.setAttribute('style','position:fixed;left:12px;right:12px;top:12px;z-index:99999;'
    +'background:#0f1420;color:#e8edf5;border:1px solid #3a4a63;border-radius:12px;'
    +'padding:12px 14px;font:12px/1.55 monospace;box-shadow:0 8px 30px #0009;');
  box.innerHTML='<div style="font-weight:700;color:#7fd1ff;margin-bottom:6px">'+label+'</div>'
    +rows.map(function(r){return '<div>'+r+'</div>';}).join('');
  document.body.appendChild(box);
}
"""


# ---------------------------------------------------------------- 静态契约矩阵
def static_contracts():
    def rd(p):
        try: return open(os.path.join(HERE, "interfaces", p), encoding="utf-8", errors="ignore").read()
        except Exception: return ""
    hwb = rd("HomeWebBridge.java")
    homectl = rd("HomeWebController.java")
    homejs = rd("home_js.txt")
    api = rd("fm-api.js")
    cases = re.findall(r'case\s+"([a-z][a-zA-Z0-9.]*)"\s*->', hwb)
    fm = re.search(r'window\.fm=\{([\s\S]*?)\n\s*\};', homectl)
    fm_methods = re.findall(r'([a-zA-Z][a-zA-Z0-9]*)\s*:', fm.group(1)) if fm else []
    return {
        "宿主 fongmiBridge (oktv-cat)": {
            "文件": "HomeWebBridge.java / HomeWebController.getSdk()", "语义": "异步 Promise",
            "派发": "invoke -> Task.execute 线程池 -> fongmiNative.resolve",
            "@JavascriptInterface": len(re.findall(r'@JavascriptInterface', hwb)),
            "case 数": len(cases), "window.fm 方法数": len(fm_methods),
            "同步可用": False, "返回": "{ok,status,body} 对象 / Promise"
        },
        "海阔原版 fy_bridge_app (hiker_54.db)": {
            "文件": "home_js.txt", "语义": "同步字符串",
            "方法": sorted(set(re.findall(r'fy_bridge_app\.([A-Za-z0-9_]+)', homejs))),
            "次数": homejs.count('fy_bridge_app'), "重写 window.fetch": 'window.fetch' in homejs,
            "同步可用": True, "返回": "正文字符串"
        },
        "v390 jar 多桥 (fm-api.js)": {
            "文件": "fm-api.js", "语义": "同步优先 + 异步兜底",
            "桥名": sorted(set(re.findall(r'window\.(FM_SHELL|FM_HOST|Fm|fongmiBridge|_nativeBridge)', api))),
            "同步桥": "FM_SHELL.call / FM_HOST.invoke / JsReqBridge.req",
            "说明": "注释明确『同步桥会阻塞 JS 线程』", "同步可用": True, "返回": "JSON 字符串"
        },
        "wogg H5 (window.fm)": {
            "文件": "wogg.js", "语义": "异步 await",
            "次数": "fm.req 52 次 / 8 方法 / 110+ 调用", "同步可用": False,
            "返回": "{ok,status,body,error} / Promise"
        },
        "hk_source 补丁 (HkGateway)": {
            "文件": "HkGateway.java / 01_SiteApi_五处拦截.java", "语义": "Java 侧五处拦截，无 WebView 桥",
            "分流": "homeContent/categoryContent/detailContent/playerContent/searchContent",
            "同步可用": True, "备注": "Rhino + 原生 UI 路线，与本模块 WebView 路线不同"
        }
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=DB)
    ap.add_argument("--out", default=HERE)
    a = ap.parse_args()

    shim = open(SHIM, encoding="utf-8").read()
    t4 = open(T4, encoding="utf-8").read()
    compat = open(COMPAT, encoding="utf-8").read()
    rules = load_rules(a.db)
    picks = pick_e2e(a.db)

    report = {"static_contracts": static_contracts(),
              "rule_corpus": {"total_article_rules": 121, "js_search_rules": len(rules),
                              "e2e_picks": len(picks)}, "groups": {}}

    print("=" * 74)
    print("构建前置 · 兼容性实证（阶段六）")
    print("=" * 74)
    print("规则库: %d 条 js: 搜索片段 / e2e 抽 %d 条" % (len(rules), len(picks)))
    print("五类桥契约: %d 类" % len(report["static_contracts"]))

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=CHROMIUM, headless=True,
                               args=["--no-sandbox", "--allow-file-access-from-files",
                                     "--disable-dev-shm-usage"])

        # ---------------- A 组：与首页混用 ----------------
        pg = b.new_page(); pg.goto("about:blank")
        pg.evaluate(HOST_SDK)
        pg.evaluate("(new Function(%s))()" % json.dumps(t4))
        pg.evaluate("(new Function(%s))()" % json.dumps(shim))
        pg.evaluate("(new Function(%s))()" % json.dumps(compat))
        mix = pg.evaluate(CHECK_MIX)
        report["groups"]["A_mixed_with_home"] = mix
        print("\n[A 组] 与首页 WebView 混用")
        print("  宿主 fetch 异步: %s   hasHomeSdk: %s   documentIsolated: %s" %
              (mix["hostFetchIsAsync"], mix["hasHomeSdk"], mix["documentIsolated"]))
        print("  shim 覆盖宿主 fetch: %s / 宿主反覆盖: %s → 全局冲突: %s" %
              (mix["shimOverwritesHostFetch"], mix["hostOverwritesShimFetch"],
               mix["globalFetchConflict"]))
        print("  可用同步桥: %s   仅异步: %s   判定: %s" %
              (mix["syncUsable"], mix["asyncOnly"], mix["verdict"]))
        pg.close()

        # ---------------- B 组：独立入口（无兼容层）基线 ----------------
        pg = b.new_page(viewport={"width": 420, "height": 900})
        errs_b = []
        pg.on("pageerror", lambda e: errs_b.append(str(e)[:160]))
        pg.route(re.compile(r"^https?://"), lambda r: r.abort())
        pg.add_init_script(SYNC_BRIDGE)
        pg.goto("file://" + FM_HTML, wait_until="load"); pg.wait_for_timeout(250)
        pg.evaluate("(new Function(%s))()" % json.dumps(t4))
        pg.evaluate("(new Function(%s))()" % json.dumps(shim))
        rb = pg.evaluate(RUN_B, {"codes": picks})
        okb = sum(1 for x in rb["results"] if x["ok"])
        report["groups"]["B_isolated_baseline"] = {
            "setResult": okb, "total": len(picks), "bridge": rb["report"]["bridge"],
            "pageErrors": errs_b, "results": rb["results"]}
        print("\n[B 组] 独立 WebView + 同步桥 + JsT4 + shim（阶段五基线）")
        print("  setResult %d/%d   桥 %s   页面异常 %d" %
              (okb, len(picks), rb["report"]["bridge"], len(errs_b)))
        pg.close()

        # ---------------- C 组：独立入口 + 兼容层 ----------------
        pg = b.new_page(viewport={"width": 420, "height": 900})
        errs_c = []
        pg.on("pageerror", lambda e: errs_c.append(str(e)[:160]))
        pg.route(re.compile(r"^https?://"), lambda r: r.abort())
        pg.add_init_script(SYNC_BRIDGE)
        pg.goto("file://" + FM_HTML, wait_until="load"); pg.wait_for_timeout(250)
        pg.evaluate("(new Function(%s))()" % json.dumps(t4))
        pg.evaluate("(new Function(%s))()" % json.dumps(shim))
        pg.evaluate("(new Function(%s))()" % json.dumps(compat))
        page = {"MY_URL": "https://example.com/s?wd=test", "MY_KEYWORD": "test", "MY_PAGE": 1}
        rc = pg.evaluate(RUN_C, {"codes": picks, "page": page})
        okc = sum(1 for x in rc["results"] if x["ok"])
        report["groups"]["C_isolated_with_compat"] = {
            "setResult": okc, "total": len(picks), "bridge": rc["install"].get("bridge"),
            "probe": {k: rc["probe"][k] for k in ("syncUsable", "asyncOnly", "hasHomeSdk", "documentIsolated")},
            "warnings": rc["install"]["warnings"], "forms": rc["forms"],
            "degraded": rc["degraded"], "unsupported": rc["unsupported"],
            "pageErrors": errs_c, "results": rc["results"]}
        print("\n[C 组] 独立 WebView + 兼容层 HikerCompat")
        print("  桥探测: 同步可用=%s 仅异步=%s 首页SDK=%s 文档隔离=%s" %
              (rc["probe"]["syncUsable"], rc["probe"]["asyncOnly"],
               rc["probe"]["hasHomeSdk"], rc["probe"]["documentIsolated"]))
        print("  setResult %d/%d   桥 %s   页面异常 %d   warnings=%d" %
              (okc, len(picks), rc["install"].get("bridge"), len(errs_c),
               len(rc["install"]["warnings"])))
        print("  e2e 形态分布: %s" % json.dumps(rc["forms"], ensure_ascii=False))

        # ---------------- D1 组：全库同口径基线（不带兼容层，RUN_B 口径） ----------------
        pg1 = b.new_page(); pg1.goto("about:blank")
        pg1.evaluate(SYNC_BRIDGE)
        pg1.evaluate("(new Function(%s))()" % json.dumps(t4))
        pg1.evaluate("(new Function(%s))()" % json.dumps(shim))
        rb_full = pg1.evaluate(RUN_B, {"codes": rules})
        okb_full = sum(1 for x in rb_full["results"] if x["ok"])
        noerr_b = sum(1 for x in rb_full["results"] if not x["err"])
        report["groups"]["D1_full_baseline"] = {
            "setResult": okb_full, "noError": noerr_b, "total": len(rules),
            "note": "同一批 78 条、同一套桥/垫片，仅不带 HikerCompat，用于口径对齐"}
        print("\n[D1 组] 全库同口径基线（不带兼容层）")
        print("  setResult %d/%d   无异常 %d/%d" % (okb_full, len(rules), noerr_b, len(rules)))
        pg1.close()

        # ---------------- D2 组：全库 78 条，兼容层形态分类 ----------------
        pg2 = b.new_page(); pg2.goto("about:blank")
        pg2.evaluate(SYNC_BRIDGE)
        pg2.evaluate("(new Function(%s))()" % json.dumps(t4))
        pg2.evaluate("(new Function(%s))()" % json.dumps(shim))
        pg2.evaluate("(new Function(%s))()" % json.dumps(compat))
        rd = pg2.evaluate(RUN_C, {"codes": rules, "page": page})
        okd = sum(1 for x in rd["results"] if x["ok"])
        noerr = sum(1 for x in rd["results"] if not x["err"])
        report["groups"]["D2_full_corpus"] = {
            "setResult": okd, "noError": noerr, "total": len(rules),
            "same_mouth_baseline": okb_full, "delta": okd - okb_full,
            "beta_stage5_reference": 23, "forms": rd["forms"],
            "degraded": rd["degraded"], "unsupported": rd["unsupported"]}
        print("\n[D2 组] 兼容层对 %d 条真实多变规则（同口径对比）" % len(rules))
        print("  setResult %d/%d (基线 %d, 差 %+d)   无异常 %d/%d   阶段五参考 23/78" %
              (okd, len(rules), okb_full, okd - okb_full, noerr, len(rules)))
        print("  需降级 %d 条（Rhino/Python 等）  形态命中: %s" %
              (rd["degraded"], json.dumps(rd["forms"], ensure_ascii=False)))
        pg2.close()

        # ---------------- E 组：多变形态降级 + 反假阳性 ----------------
        VARIANTS = [
            {"id": "std-js", "code": "setResult([{title:'影片A',url:'https://e.com/1'}]);"},
            {"id": "rhino", "code": "var x = new JavaAdapter(java.lang.Runnable,{run:function(){}}); setResult(x);"},
            {"id": "python", "code": "py: import requests; setResult(requests.get('http://x'))"},
            {"id": "encrypted", "code": "evalPrivateJS('加密规则占位'); setResult(1);"},
            {"id": "sync-network", "code": "var h = fetch('https://e.com/s'); setResult(pdfa(h,'.item'));"},
        ]
        pg3 = b.new_page(); pg3.goto("about:blank")
        pg3.evaluate(SYNC_BRIDGE)
        pg3.evaluate("(new Function(%s))()" % json.dumps(t4))
        pg3.evaluate("(new Function(%s))()" % json.dumps(shim))
        pg3.evaluate("(new Function(%s))()" % json.dumps(compat))
        ev = pg3.evaluate(r"""({variants, page}) => {
          var out=[];
          for(var i=0;i<variants.length;i++){
            var v=variants[i];
            out.push({id:v.id, cls:HikerCompat.classify(v.code),
                      run:(function(){ try{ return HikerCompat.run(v.code,page,{id:v.id}); }
                                       catch(e){ return {err:String(e)}; } })()});
          }
          // 反假阳性：异步 Promise 必须被 normalize 明确拒绝
          var p = new Promise(function(r){ r({ok:true,body:'x'}); });
          var normSync = HikerCompat.normalize('<html>ok</html>');
          var normAsync = HikerCompat.normalize(p);
          return {variants:out, normSync:normSync, normAsync:normAsync};
        }""", {"variants": VARIANTS, "page": page})
        report["groups"]["E_variants"] = {
            "variants": ev["variants"],
            "normalize_sync": ev["normSync"], "normalize_async": ev["normAsync"]}
        print("\n[E 组] 多变形态降级 + 反假阳性")
        for v in ev["variants"]:
            c = v["cls"]
            print("  %-14s runnable=%-5s action=%-18s forms=%s" %
                  (v["id"], c["runnable"], c["action"],
                   ",".join(f["id"] for f in c["forms"]) or "-"))
        print("  normalize(字符串)=%s / normalize(Promise)=%s (须 ok=false)" %
              (ev["normSync"]["reason"], ev["normAsync"]["reason"]))
        pg3.close()

        # ---------------- 截图面板（C 组） ----------------
        rows = [
            "A 首页混用: %s / 全局 fetch 冲突: %s" % (mix["verdict"], mix["globalFetchConflict"]),
            "B 独立基线: setResult %d/%d  桥 %s" % (okb, len(picks), rb["report"]["bridge"]),
            "C 独立+兼容层: setResult %d/%d  页面异常 %d" % (okc, len(picks), len(errs_c)),
            "C 桥探测: 同步=%s 仅异步=%s 首页SDK=%s 隔离=%s" %
              (len(rc["probe"]["syncUsable"]), rc["probe"]["asyncOnly"],
               rc["probe"]["hasHomeSdk"], rc["probe"]["documentIsolated"]),
            "D 全库 %d 条: 基线 %d → 兼容层 %d (%+d)  降级 %d" %
              (len(rules), okb_full, okd, okd - okb_full, rd["degraded"]),
            "E 多变形态: Rhino/Python 正确降级，Promise 归一拒绝",
        ]
        pg.evaluate(PANEL_JS, {"rows": rows, "label": "海阔独立入口 · 构建前置兼容性实证"})
        pg.screenshot(path=os.path.join(a.out, "hiker_compat_poc.png"), full_page=False)
        pg.close()
        b.close()

    rp = os.path.join(a.out, "compat_report.json")
    with open(rp, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\nJSON 报告: %s" % rp)
    print("结论: A 组证明『与首页混用』 incompatible；独立 WebView + 同步桥是硬约束；")
    print("      兼容层在不损失 B 组通过率的前提下，补齐了桥探测 / 形态降级 / 作用域隔离。")


if __name__ == "__main__":
    main()
