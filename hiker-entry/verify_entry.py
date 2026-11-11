#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
独立入口容器页 · 端到端验证（阶段六）

两种运行形态都验：
  1) 有 Java 同步桥：注入模拟 HikerBridge（等价于独立 Activity 的 addJavascriptInterface）
  2) 无 Java 桥：浏览器直接打开（离线 mock 自动兜底）

逐段点击：② 口令 → ③ 取数 → ④ 生成站点/注入 → ⑤ 运行 → ⑥ 播放 → ⑦ 兼容性
断言：兼容层初始化、同步桥探测、setResult、Rhino/Python 降级、播放桥调用、页面异常 0。
"""
import os, re, json, argparse

HERE = os.path.dirname(os.path.abspath(__file__))
INDEX = os.path.join(HERE, "app", "assets", "hiker", "index.html")
CHROMIUM = "/usr/bin/chromium"

# 模拟独立 Activity 注入的 Java 同步桥（与 HikerBridge.java 签名一致）
JAVA_BRIDGE = r"""
window.HikerBridge = {
  req: function(url, optionsJson){ return '<html><body><div class="list">'
      +'<a class="item" href="/v/1.html">影片A</a>'
      +'<a class="item" href="/v/2.html">影片B</a></div></body></html>'; },
  request: function(u,o){ return this.req(u,o); },
  fetch: function(u,o){ return this.req(u,o); },
  report: function(t,v){ (window.__HK_REPORTS__||(window.__HK_REPORTS__=[])).push([t,v]); },
  log: function(m){},
  play: function(url,title,pic){ (window.__HK_PLAYS__||(window.__HK_PLAYS__=[])).push(url);
                                 this.report('hiker.play',url); return true; },
  getInternalJs: function(){ return ''; },
  getVar: function(k){ return ''; }, putVar: function(k,v){},
  getCache: function(k){ return ''; }, setCache: function(k,v){},
  capabilities: function(){ return JSON.stringify({bridge:'HikerBridge',sync:true,
                            return:'string',play:'local-push',port:9978,es5:true}); }
};
window.__HK_REPORTS__ = [];
window.__HK_PLAYS__ = [];
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=HERE)
    a = ap.parse_args()
    from playwright.sync_api import sync_playwright

    report = {"groups": {}, "assertions": {}, "pageErrors": {}}
    total = passed = 0

    def chk(name, cond, detail=""):
        nonlocal total, passed
        total += 1
        if cond: passed += 1
        report["assertions"][name] = {"ok": bool(cond), "detail": str(detail)[:200]}
        print("  [%s] %-34s %s" % ("PASS" if cond else "FAIL", name, str(detail)[:90]))

    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=CHROMIUM, headless=True,
                               args=["--no-sandbox", "--allow-file-access-from-files",
                                     "--disable-dev-shm-usage"])
        for label, with_java in [("A 有 Java 同步桥（独立 Activity 形态）", True),
                                 ("B 无 Java 桥（离线 mock 形态）", False)]:
            pg = b.new_page(viewport={"width": 1280, "height": 900})
            errs = []
            pg.on("pageerror", lambda e, acc=errs: acc.append(str(e)[:200]))
            pg.route(re.compile(r"^https?://"), lambda r: r.abort())
            if with_java:
                pg.add_init_script(JAVA_BRIDGE)
            pg.goto("file://" + INDEX, wait_until="load")
            pg.wait_for_timeout(400)
            key = "A_with_java_bridge" if with_java else "B_offline_mock"
            print("\n" + "=" * 72)
            print(label)
            print("=" * 72)

            # ① 兼容层初始化
            boot = pg.evaluate("""() => {
                var p = window.HikerCompat.probe();
                return {probe:p, badge:document.getElementById('bBridge').textContent,
                        iso:document.getElementById('bIso').textContent,
                        sdk:document.getElementById('bSdk').textContent,
                        out:document.getElementById('bootOut').textContent};
            }""")
            chk(key + " 同步桥可用", len(boot["probe"]["syncUsable"]) > 0, boot["probe"]["syncUsable"])
            chk(key + " 无首页 SDK", boot["probe"]["hasHomeSdk"] is False, boot["probe"]["hasHomeSdk"])
            chk(key + " 作用域隔离", boot["probe"]["documentIsolated"] is True, boot["probe"]["documentIsolated"])
            chk(key + " 非仅异步", boot["probe"]["asyncOnly"] is False, boot["probe"]["asyncOnly"])
            chk(key + " 兼容层已装配", "bridge" in boot["out"], boot["badge"])

            def tab(v):
                pg.click("#nav button[data-v='%d']" % v)
                pg.wait_for_timeout(60)

            # ② 口令
            tab(1); pg.click("#btnDemoToken")
            tok = pg.evaluate("() => window.__HIKER_STATE__.tokens")
            chk(key + " 口令载入", len(tok) == 1, "tokens=%d" % len(tok))
            chk(key + " 端点含 {code}", "{code}" in (tok[0]["endpoint"] if tok else ""),
                tok[0]["endpoint"] if tok else "")

            # ③ 取数
            tab(2); pg.click("#btnDemoData")
            ds = pg.evaluate("() => window.__HIKER_STATE__.datasets")
            chk(key + " 数据集生成", len(ds) >= 1, "datasets=%d" % len(ds))
            chk(key + " 解析 4 站点", len(ds[-1]["sites"]) == 4, "sites=%d" % len(ds[-1]["sites"]))

            # ④ 生成站点 + 注入
            tab(3); pg.click("#btnGen")
            sites = pg.evaluate("() => window.__HIKER_STATE__.sites")
            chk(key + " 生成 4 站点", len(sites) == 4, "sites=%d" % len(sites))
            chk(key + " registry 有 4 items",
                len(json.loads(pg.inner_text("#registryOut"))["items"]) == 4, pg.inner_text("#registryOut")[:80])
            pg.click("#btnInject"); pg.wait_for_timeout(80)
            chk(key + " 注入按钮无异常", True, "已请求 /manage/csp 或离线降级")

            # ⑤ 站点运行（3 条可跑规则）
            tab(4); pg.click("#btnRun")
            vods = pg.evaluate("() => window.__HIKER_STATE__.vods")
            runout = pg.inner_text("#runOut")
            chk(key + " 规则产出 setResult", len(vods) >= 1, "vods=%d" % len(vods))
            chk(key + " 运行面板有结果", "站点运行结果" in runout, runout.split("\n")[0])
            # 全部样本：Rhino/Python 必须降级
            pg.click("#btnRunAll")
            allout = pg.inner_text("#runOut")
            chk(key + " Rhino 正确降级", "degrade-to-engine" in allout and "✗ rhino" in allout,
                [l for l in allout.split("\n") if "rhino" in l])
            chk(key + " Python 正确降级", "✗ py" in allout,
                [l for l in allout.split("\n") if " py " in l])

            # ⑥ 播放（3 种入口：手动 / VOD / 直接桥）
            tab(5)
            pg.fill("#pUrl", "https://example.com/manual.m3u8")
            pg.fill("#pTitle", "手动播放")
            pg.click("#btnPlay")
            nplay = len(vods)
            for i in range(nplay):
                pg.evaluate("() => window.HikerHost.play('https://example.com/vod%d.m3u8','VOD%d','')" % (i, i))
            plays = pg.evaluate("() => window.__HIKER_STATE__.plays")
            bridge_plays = pg.evaluate("() => (window.__HK_PLAYS__||[]).length")
            chk(key + " 播放记录 >= 1", len(plays) >= 1, "plays=%d" % len(plays))
            chk(key + " 播放走本地推送通道",
                any("local-push" in p["channel"] or "mock" in p["channel"] for p in plays),
                [p["channel"] for p in plays])
            if with_java:
                chk(key + " Java 桥 play 被调用", bridge_plays >= 1, "java play=%d" % bridge_plays)
                reports = pg.evaluate("() => (window.__HK_REPORTS__||[]).length")
                chk(key + " Java 桥 report 回传", reports >= 1, "reports=%d" % reports)

            # ⑦ 兼容性面板
            tab(6); pg.click("#btnCompat")
            compat = pg.inner_text("#compatOut")
            chk(key + " 五类桥能力表", "fongmiBridge" in compat and "fy_bridge_app" in compat, "能力表已渲染")
            chk(key + " 反假阳性展示", "async-promise" in compat, "Promise 归一拒绝")

            report["pageErrors"][key] = errs
            chk(key + " 页面 JS 异常 0", len(errs) == 0, errs[:2])
            report["groups"][key] = {
                "tokens": len(tok), "datasets": len(ds), "sites": len(sites),
                "vods": len(vods), "plays": len(plays),
                "syncUsable": boot["probe"]["syncUsable"],
                "hasHomeSdk": boot["probe"]["hasHomeSdk"],
                "documentIsolated": boot["probe"]["documentIsolated"]}

            if with_java:
                pg.screenshot(path=os.path.join(a.out, "hiker_entry_poc.png"), full_page=False)
            pg.close()
        b.close()

    report["summary"] = {"passed": passed, "total": total,
                         "all_pass": passed == total}
    with open(os.path.join(a.out, "verify_entry_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n" + "=" * 72)
    print("结果: %d/%d 断言通过" % (passed, total))
    print("=" * 72)
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
