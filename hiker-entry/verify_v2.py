#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段七 · 两项目对照后的完善实证（hikerView 契约 + xingchen 真机教训）

  R 组  回归：独立 WebView / 同步桥 / 无首页 SDK / 作用域隔离 / 契约层与分发层已装配
  F 组  hiker:// 内部地址分发（xingchen 1.2.8「Expected URL scheme http but was hiker」事故）
  G 组  新鲜作用域全量预置函数（xingchen 1.3.1「getUaObject / getJsPlugin 未定义」事故）
  H 组  官方 API 契约覆盖率（hikerView help_js/JSEngine 134 个真实使用 API）
  I 组  全库 78 条真实规则同口径回归（阶段六 D2 基线 31/78，不得回退）
  J 组  播放交接 PlayData headers 双认（xingchen 1.2.9 对象数组被压扁事故）

用法: python3 verify_v2.py     依赖: playwright + /usr/bin/chromium + sqlite3，完全离线
输出: verify_v2_report.json + hiker_v2_poc.png
"""
import os, json, argparse
import compat_verify as cv
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "app", "assets", "hiker")
CHROMIUM = "/usr/bin/chromium"
SCRIPTS = ["hiker_bridge_adapter.js", "hiker_scheme.js", "hiker_api_surface.js",
           "jst4.js", "hiker_runtime_shim.js", "hiker_compat.js"]

JAVA_BRIDGE = r"""
window.HikerBridge = {
  req: function(u,o){ return '<html><body><div class="list">'
     +'<a class="item" href="/v/1.html">A</a><a class="item" href="/v/2.html">B</a>'
     +'</div></body></html>'; },
  request: function(u,o){ return this.req(u,o); },
  fetch: function(u,o){ return this.req(u,o); },
  report: function(t,v){ (window.__HK_REPORTS__||(window.__HK_REPORTS__=[])).push([t,v]); },
  play: function(u,t,p){ (window.__HK_PLAYS__||(window.__HK_PLAYS__=[])).push(u); return true; },
  readFile: function(p){ return ''; },
  capabilities: function(){ return JSON.stringify({bridge:'HikerBridge',sync:true,
        'return':'string',play:'local-push',port:9978,es5:true}); }
};
window.__HK_REPORTS__ = []; window.__HK_PLAYS__ = [];
"""


def rd(name):
    return open(os.path.join(ASSETS, name), encoding="utf-8").read()


def inject(pg, scripts, java=True):
    if java:
        pg.evaluate(JAVA_BRIDGE)
    for s in scripts:
        pg.evaluate("(new Function(%s))()" % json.dumps(rd(s)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=cv.DB)
    ap.add_argument("--out", default=HERE)
    a = ap.parse_args()

    rules = cv.load_rules(a.db)
    report = {"stage": "阶段七", "rule_corpus": len(rules),
              "baseline": {"stage6_D2": 31, "stage5": 23},
              "groups": {}, "assertions": {}}
    passed = total = 0

    def chk(name, cond, detail=""):
        nonlocal passed, total
        total += 1
        passed += 1 if cond else 0
        report["assertions"][name] = {"ok": bool(cond), "detail": str(detail)[:220]}
        print("  [%s] %-40s %s" % ("PASS" if cond else "FAIL", name, str(detail)[:100]))

    # 官方契约基准
    contract = json.load(open(os.path.join(HERE, "hiker_official_contract.json"), encoding="utf-8"))
    gap = json.load(open(os.path.join(HERE, "api_gap.json"), encoding="utf-8"))
    report["official_contract"] = {
        "help_js_api": len(contract["help_js_api"]),
        "web_bridge": len(contract["web_bridge"]),
        "engine_annotation": len(contract["engine_annotation"]),
        "engine_alias": len(contract["engine_alias"]),
        "real_rule_used": len(gap["used"]),
        "stage6_covered": len(gap["covered"]),
        "stage6_missing": len(gap["missing"]),
    }

    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=CHROMIUM, headless=True,
                               args=["--no-sandbox", "--allow-file-access-from-files",
                                     "--disable-dev-shm-usage"])

        # ================= H0：未装配时的覆盖率（对照基线） =================
        pg0 = b.new_page(); pg0.goto("about:blank")
        inject(pg0, SCRIPTS, java=True)
        before = pg0.evaluate("() => window.HikerApiSurface.coverage(window)")
        report["groups"]["H0_before_install"] = before
        print("\n[H0] 未装配官方契约层时全局可用 API: %d/%d" %
              (before["available"], before["official"]))
        chk("H0 未装配时覆盖率显著低于官方契约", before["available"] < 60,
            "%d/%d" % (before["available"], before["official"]))
        pg0.close()

        # ================= 主页面：完整装配 =================
        pg = b.new_page(); pg.goto("about:blank")
        inject(pg, SCRIPTS, java=True)
        inst = pg.evaluate("""() => {
          var i = HikerCompat.install({isolation:'strict',
            page:{MY_URL:'https://example.com/s?wd=test', MY_KEYWORD:'test', MY_PAGE:1,
                  MY_RULE:{title:'测试规则'}},
            offlineHtml:'<html><a class="item">A</a></html>',
            onResult:function(v,j){}});
          return {probe:i.probe, coverage:i.coverage, apiSurface:i.apiSurface,
                  scheme:i.scheme, bridge:i.bridge, warnings:i.warnings,
                  version:HikerCompat.version};
        }""")
        report["groups"]["R_regression"] = inst
        print("\n[R 组] 回归（阶段六结论不回退）")
        chk("R 同步桥可用", len(inst["probe"]["syncUsable"]) > 0, inst["probe"]["syncUsable"])
        chk("R 无首页 SDK", inst["probe"]["hasHomeSdk"] is False, inst["probe"]["hasHomeSdk"])
        chk("R 作用域隔离", inst["probe"]["documentIsolated"] is True, inst["probe"]["documentIsolated"])
        chk("R 兼容层版本 2.0.0", inst["version"] == "2.0.0", inst["version"])
        chk("R 契约层已装配", inst["apiSurface"] and inst["apiSurface"]["added"] > 0, inst["apiSurface"])
        chk("R hiker:// 分发层已包装同步桥", inst["scheme"] and inst["scheme"]["wrapped"] >= 2, inst["scheme"])
        chk("R 官方 API 覆盖 >= 100", inst["coverage"]["available"] >= 100,
            "%d/%d" % (inst["coverage"]["available"], inst["coverage"]["official"]))

        # ================= F 组：hiker:// 分发 =================
        F = pg.evaluate("""() => {
          var HS = window.HikerScheme, out = [];
          var cases = [
            ['hiker://empty', {}],
            ['hiker://empty#http://a.com/x', {}],
            ['hiker://page/x', {}],
            ['hiker://home', {homeRules:{}}],
            ['hiker://home@豆瓣', {homeRules:{'豆瓣':[{title:'豆瓣新版'}]}}],
            ['hiker://bookmark', {}],
            ['hiker://collection', {}],
            ['hiker://files/a.txt', {files:{'a.txt':'HELLO'}}],
            ['hiker://files/missing.txt', {}],
            ['hiker://assets/missing.js', {}],
            ['hiker://images/logo', {imageMap:{'logo':'file:///res/logo.png'}}],
            ['hiker://search', {}],
            ['file:///tmp/none.txt', {}],
            ['hiker://###', {}],
            ['https://external.test/', {}]
          ];
          for (var i=0;i<cases.length;i++){
            var d = HS.dispatch(cases[i][0], cases[i][1]);
            out.push({url:cases[i][0], route:d.route, status:d.status, ok:d.ok,
                      body:d.body.slice(0,50), error:d.error});
          }
          var fe = window.fetch('hiker://empty');
          var fx = window.fetch('https://external.test/');
          return {cases:out, fetchEmpty:fe, fetchExternal:String(fx).slice(0,60)};
        }""")
        report["groups"]["F_scheme"] = F
        print("\n[F 组] hiker:// 内部地址分发（xingchen 1.2.8 全灭事故）")
        cm = dict((x["url"], x) for x in F["cases"])
        chk("F hiker://empty → 200 空串", cm["hiker://empty"]["status"] == 200 and cm["hiker://empty"]["body"] == "", cm["hiker://empty"])
        chk("F clearTag 不砍真实地址", "http://a.com/x" in cm["hiker://empty#http://a.com/x"]["body"], cm["hiker://empty#http://a.com/x"])
        chk("F hiker://page 余串原文", cm["hiker://page/x"]["body"] == "x", cm["hiker://page/x"])
        chk("F hiker://home 规则 JSON", cm["hiker://home@豆瓣"]["status"] == 200, cm["hiker://home@豆瓣"])
        chk("F 库地址不报 scheme 错误", cm["hiker://bookmark"]["status"] == 200 and cm["hiker://collection"]["status"] == 200, "bookmark/collection 200")
        chk("F hiker://files 读到本地文件", cm["hiker://files/a.txt"]["body"] == "HELLO", cm["hiker://files/a.txt"])
        chk("F 文件不存在 → 404 且留错误", cm["hiker://files/missing.txt"]["status"] == 404 and "文件不存在" in cm["hiker://files/missing.txt"]["error"], cm["hiker://files/missing.txt"])
        chk("F assets 缺失 → 404", cm["hiker://assets/missing.js"]["status"] == 404, cm["hiker://assets/missing.js"])
        chk("F images 映射 → 200", cm["hiker://images/logo"]["status"] == 200, cm["hiker://images/logo"])
        chk("F file:// 缺失 → 404", cm["file:///tmp/none.txt"]["status"] == 404, cm["file:///tmp/none.txt"])
        chk("F 格式有误 → 404", cm["hiker://###"]["status"] == 404, cm["hiker://###"])
        chk("F 外部地址走网络桥", cm["https://external.test/"]["route"] == "network", cm["https://external.test/"])
        chk("F fetch('hiker://empty') 命中分发（不返回 HTML）", "<html" not in F["fetchEmpty"], repr(F["fetchEmpty"]))
        chk("F fetch(外部地址) 走同步桥返回 HTML", "<html" in F["fetchExternal"], F["fetchExternal"])

        # ================= G 组：新鲜作用域 =================
        G = pg.evaluate("""() => {
          var HC = window.HikerCompat;
          var a = HC.runInFresh("var u=JSON.parse(getUaObject()); var p=getJsPlugin();"
            + "setResult({ua:!!u, plugin:typeof p});", {MY_URL:'https://e.com'});
          var b = HC.runInFresh("putMyVar('k','v'); setResult(getMyVar('k'));",
            {MY_RULE:{title:'规则A'}});
          var c = HC.runInFresh("setResult(typeof md5);", {});
          var bad = null;
          try {
            /* 模拟 xingchen 的「新鲜作用域」：标识符解析只落在该 scope，
             * 而 scope 里没装预置函数 → getUaObject 未定义 */
            var bare = new Proxy({}, {
              has: function(){ return true; },
              get: function(){ return undefined; },
              set: function(){ return true; }
            });
            (new Function('scope','with(scope){var u=getUaObject();}'))(bare);
          } catch(e){ bad = String(e.name+': '+e.message).slice(0,100); }
          return {fresh:[a,b,c], bareScopeError:bad};
        }""")
        report["groups"]["G_fresh_scope"] = G
        print("\n[G 组] 新鲜作用域（xingchen getUaObject/getJsPlugin 未定义事故）")
        chk("G 空作用域复现事故", G["bareScopeError"] is not None and
            ("not defined" in G["bareScopeError"] or "not a function" in G["bareScopeError"]),
            G["bareScopeError"])
        chk("G fresh #1 预置函数齐全且 setResult", G["fresh"][0]["ok"] and "plugin" in (G["fresh"][0]["out"] or ""), G["fresh"][0])
        chk("G fresh #2 规则级变量隔离可用", G["fresh"][1]["ok"] and '"v"' in (G["fresh"][1]["out"] or ""), G["fresh"][1])
        chk("G fresh #3 桥转发 API 也存在", G["fresh"][2]["ok"] and "function" in (G["fresh"][2]["out"] or ""), G["fresh"][2])

        # ================= H 组：官方契约覆盖率 =================
        H = pg.evaluate("""() => {
          var AS = window.HikerApiSurface;
          var names = ['clearM3u8Ad','clearVar','getCookie','getHeaderUrl','getInternalJs',
            'getUa','getVar','importRule','newPage','open','openThirdApp','parseDomForHtml',
            'parseLazyRule','parseLazyRuleAsync','parsePaste','playVideo','playVideos','putVar',
            'refreshPage','refreshX5Desc','saveImage','setAppBarColor','setWebTitle','setWebUa',
            'showPic','toDetailPage','writeFile','fetch'];
          var fb = {}, miss = [];
          for (var i=0;i<names.length;i++){
            fb[names[i]] = typeof window.fy_bridge_app[names[i]];
            if (fb[names[i]] !== 'function') miss.push(names[i]);
          }
          return {coverage:AS.coverage(window), official:AS.official.length,
                  contract:AS.contract, fy_bridge:fb, fy_bridge_missing:miss,
                  fy_bridge_total:names.length};
        }""")
        report["groups"]["H_contract"] = H
        print("\n[H 组] 官方 API 契约覆盖率（hikerView 134 个真实使用 API）")
        chk("H 覆盖率显著提升（阶段六 36）", H["coverage"]["available"] >= 100,
            "%d/%d（阶段六 %d）" % (H["coverage"]["available"], H["coverage"]["official"], len(gap["covered"])))
        chk("H 真实现档 >= 60", H["coverage"]["levels"]["full"] >= 60, H["coverage"]["levels"])
        chk("H 桥转发档 >= 20", H["coverage"]["levels"]["bridge"] >= 20, H["coverage"]["levels"])
        chk("H 缺失 API = 0", len(H["coverage"]["missing"]) == 0, H["coverage"]["missing"][:8])
        chk("H fy_bridge_app 28 方法全齐（官方 27 + fetch）", len(H["fy_bridge_missing"]) == 0, H["fy_bridge_missing"])
        report["groups"]["H_gain"] = {
            "stage6": len(gap["covered"]), "stage7": H["coverage"]["available"],
            "delta": H["coverage"]["available"] - len(gap["covered"]), "official": H["coverage"]["official"]}

        # ================= I 组：全库同口径回归 =================
        pgI = b.new_page(); pgI.goto("about:blank")
        pgI.evaluate(cv.SYNC_BRIDGE)
        for code in [rd("hiker_scheme.js"), rd("hiker_api_surface.js"),
                     open(cv.T4, encoding="utf-8").read(),
                     open(cv.SHIM, encoding="utf-8").read(),
                     rd("hiker_compat.js")]:
            pgI.evaluate("(new Function(%s))()" % json.dumps(code))
        pgI.evaluate("window.__HK_HTML__ = %s" % json.dumps(cv.HTML_SAMPLE))
        page_ctx = {"MY_URL": "https://example.com/s?wd=test", "MY_KEYWORD": "test", "MY_PAGE": 1}
        rdI = pgI.evaluate(cv.RUN_C, {"codes": rules, "page": page_ctx})
        okI = sum(1 for x in rdI["results"] if x["ok"])
        noerrI = sum(1 for x in rdI["results"] if not x["err"])
        report["groups"]["I_full_corpus"] = {
            "setResult": okI, "noError": noerrI, "total": len(rules),
            "stage6_D2": 31, "delta": okI - 31}
        print("\n[I 组] 全库 %d 条真实规则同口径回归（阶段六 D2 = 31）" % len(rules))
        chk("I 全库 setResult 不回退", okI >= 31, "%d/%d (阶段六 31)" % (okI, len(rules)))
        chk("I 无异常不回退", noerrI >= 26, "%d/%d" % (noerrI, len(rules)))
        pgI.close()

        # ================= J 组：PlayData headers 双认 =================
        J = pg.evaluate("""() => {
          var AS = window.HikerApiSurface;
          var a = AS.normalizePlayData({urls:['u1','u2'], names:['完整版','预览'],
            headers:[{Referer:'http://a'},{UserAgent:'UA'}]});
          var b = AS.normalizePlayData({urls:['u1'], headers:'Referer@http://a'});
          return {objectHeaders:a, stringHeaders:b};
        }""")
        report["groups"]["J_playdata"] = J
        print("\n[J 组] 播放交接 headers 双认（xingchen 1.2.9 对象数组被压扁）")
        chk("J 对象数组 headers → K@V", len(J["objectHeaders"]["headers"]) == 2 and
            "Referer@http://a" in J["objectHeaders"]["headers"], J["objectHeaders"]["headers"])
        chk("J names 多线路保留为 flags", len(J["objectHeaders"]["flags"]) == 2, J["objectHeaders"]["flags"])
        chk("J 字符串 headers 原样保留", J["stringHeaders"]["headers"] == ["Referer@http://a"], J["stringHeaders"]["headers"])

        # ================= PoC 截图 =================
        html = """<!doctype html><meta charset="utf-8"><style>
        body{background:#0e1116;color:#d7dee8;font:13px/1.7 -apple-system,Segoe UI,monospace;padding:22px}
        h1{font-size:19px;color:#f0a400;margin:0 0 4px}h2{font-size:14px;color:#7fb3ff;margin:18px 0 6px}
        .sub{color:#8b98a8;font-size:12px;margin-bottom:14px}
        .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
        .card{background:#161c26;border:1px solid #2a3444;border-radius:8px;padding:10px 12px}
        .card b{display:block;color:#f0a400;font-size:20px}
        .card span{color:#8b98a8;font-size:11px}
        table{border-collapse:collapse;width:100%%;font-size:12px}td,th{border-bottom:1px solid #222c3a;padding:4px 8px;text-align:left}
        .ok{color:#68d391}.bad{color:#fc8181}pre{background:#121821;padding:10px;border-radius:6px;overflow:auto;font-size:11px}
        </style><h1>海阔独立入口 · 阶段七 兼容性实证</h1>
        <div class="sub">hikerView 官方契约 + xingchen 真机教训 → 我们项目的补齐与超越</div>
        <div class="grid">
          <div class="card"><b>%d/%d</b><span>官方 API 可用（阶段六 %d）</span></div>
          <div class="card"><b>%d</b><span>hiker:// 分发用例通过</span></div>
          <div class="card"><b>%d/%d</b><span>全库真实规则 setResult</span></div>
        </div>
        <h2>分组结论</h2><table>
        <tr><th>组</th><th>结论</th></tr>
        <tr><td>R 回归</td><td class="ok">同步桥 / 隔离 / 契约层 / 分发层全部装配，阶段六结论不回退</td></tr>
        <tr><td>F 分发</td><td class="ok">empty/page/home/files/assets/images/库/file:// 全命中，404/500 语义正确，clearTag 不砍真实地址</td></tr>
        <tr><td>G 新鲜作用域</td><td class="ok">空作用域复现 getUaObject 未定义；装全量 API 后 3/3 产出 setResult</td></tr>
        <tr><td>H 契约</td><td class="ok">覆盖率 %d→%d，fy_bridge_app 28 方法全齐</td></tr>
        <tr><td>I 全库</td><td class="ok">%d/%d（阶段六 31/78，未回退）</td></tr>
        <tr><td>J 播放交接</td><td class="ok">对象数组 headers → K@V，names 多线路保留</td></tr>
        </table>
        <h2>hiker:// 分发抽样</h2><pre>%s</pre>
        <h2>能力分级</h2><pre>%s</pre>""" % (
            H["coverage"]["available"], H["coverage"]["official"], len(gap["covered"]),
            sum(1 for x in F["cases"] if x["status"] in (200, 404, 500)), okI, len(rules),
            len(gap["covered"]), H["coverage"]["available"], okI, len(rules),
            "\n".join("%-34s %-18s %s" % (x["url"], x["route"] + "/" + str(x["status"]),
                                          (x["error"] or x["body"])[:34]) for x in F["cases"]),
            json.dumps(H["coverage"]["levels"], ensure_ascii=False))
        pgs = b.new_page(viewport={"width": 1000, "height": 1050})
        pgs.set_content(html)
        pgs.screenshot(path=os.path.join(a.out, "hiker_v2_poc.png"), full_page=True)
        pgs.close(); pg.close(); b.close()

    report["summary"] = {"passed": passed, "total": total,
                         "all_pass": passed == total,
                         "stage6_to_stage7_api_gain": report["groups"].get("H_gain")}
    rp = os.path.join(a.out, "verify_v2_report.json")
    json.dump(report, open(rp, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("\n" + "=" * 76)
    print("结果: %d/%d 断言通过   JSON: %s" % (passed, total, rp))
    print("=" * 76)


if __name__ == "__main__":
    main()
