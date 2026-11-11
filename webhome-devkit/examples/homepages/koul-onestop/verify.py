#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
口令一条龙 · WebHome 端到端离线验证器

加载 file://index.html（无 App 时页面自动挂载离线演示桥），逐段断言：
  ① 入口 ② 口令管理 ③ 获取口令数据 ④ 生成站点+注入 ⑤ 站点运行 ⑥ 桥接播放
另做 JS 语法基线扫描（旧 WebView 兼容红线）。

用法: python3 verify.py
依赖: playwright + /usr/bin/chromium；完全离线，不需要 App、不需要网络。
"""
import os
import re
import json

ROOT = os.path.dirname(os.path.abspath(__file__))
HTML = os.path.join(ROOT, "index.html")
CHROMIUM = "/usr/bin/chromium"


def syntax_baseline(html_path):
    """剔除字符串字面量后扫描旧 WebView 禁用语法。"""
    src = open(html_path, encoding="utf-8").read()
    blocks = re.findall(r"<script[^>]*>([\s\S]*?)</script>", src)
    js = blocks[-1] if blocks else ""
    code = re.sub(r"'(?:\\.|[^'\\])*'", "''", js)
    code = re.sub(r'"(?:\\.|[^"\\])*"', '""', code)
    banned = {
        "可选链 ?.": r"\?\.",
        "空值合并 ??": r"\?\?",
        "箭头函数 =>": r"=>",
        "模板字符串": r"`",
        "let/const/class/async/await": r"\b(let|const|class|async|await)\b",
        "String.replaceAll": r"replaceAll",
        "Object.fromEntries": r"fromEntries",
        "Array.flat/flatMap": r"\.flat(Map)?\(",
        "globalThis": r"globalThis",
        "可选 catch 绑定": r"catch\s*\{",
        "指数 **": r"\*\*",
    }
    hits = {}
    for name, pattern in banned.items():
        n = len(re.findall(pattern, code))
        if n:
            hits[name] = n
    return {"script_lines": js.count("\n") + 1, "banned_hits": hits,
            "passed": len(hits) == 0}


def main():
    report = {"steps": [], "assertions": {}, "page_errors": [], "passed": False}

    print("=" * 72)
    print("口令一条龙 · WebHome 端到端离线验证")
    print("=" * 72)

    sy = syntax_baseline(HTML)
    report["syntax"] = sy
    print("\n[0] JS 语法基线")
    print("  主脚本 %d 行  禁用语法命中: %s  → %s" %
          (sy["script_lines"], sy["banned_hits"] or "无", "通过" if sy["passed"] else "未通过"))

    from playwright.sync_api import sync_playwright
    with sync_playwright() as pw:
        b = pw.chromium.launch(executable_path=CHROMIUM, headless=True,
                               args=["--no-sandbox", "--allow-file-access-from-files",
                                     "--disable-dev-shm-usage"])
        pg = b.new_page(viewport={"width": 412, "height": 900})
        errors = []
        pg.on("pageerror", lambda e: errors.append(str(e)[:220]))
        pg.route(re.compile(r"^https?://"), lambda r: r.abort())
        pg.goto("file://" + HTML, wait_until="load")
        pg.wait_for_function("window.__KOUL__ && window.__KOUL__.state()")
        pg.wait_for_timeout(350)

        def step(name, fn):
            r = fn()
            report["steps"].append({"name": name, "result": r})
            print("  %-34s %s" % (name, "OK" if r else "FAIL"))
            return r

        print("\n[1] ① 入口")
        tabs = pg.eval_on_selector_all("#tabs .tab", "els => els.length")
        status = pg.inner_text("#status")
        is_native = pg.evaluate("() => __KOUL__.isNative()")
        report["assertions"]["tabs"] = tabs
        report["assertions"]["status"] = status
        report["assertions"]["native_sdk"] = is_native
        step("六个入口 Tab 存在", lambda: tabs == 6)
        step("离线演示桥自动挂载", lambda: (not is_native) and ("离线演示桥" in status))

        print("\n[2] ② 口令管理")
        pg.evaluate("() => __KOUL__.show('tokens')")
        pg.click("#btnDemoToken")
        pg.click("#tkSave")
        pg.wait_for_timeout(120)
        st = pg.evaluate("() => __KOUL__.state()")
        report["assertions"]["tokens"] = len(st["tokens"])
        step("保存演示口令 → tokens=1", lambda: len(st["tokens"]) == 1)
        step("端点模板含 {code}", lambda: "{code}" in st["tokens"][0]["endpoint"])

        print("\n[3] ③ 获取口令数据")
        pg.evaluate("() => __KOUL__.show('datasets')")
        pg.evaluate('() => __KOUL__.fetchDemo("影片")')
        pg.wait_for_timeout(120)
        st = pg.evaluate("() => __KOUL__.state()")
        dsid = st["datasets"][0]["id"] if st["datasets"] else ""
        report["assertions"]["datasets"] = len(st["datasets"])
        report["assertions"]["dataset_site_count"] = st["datasets"][0]["siteCount"] if st["datasets"] else 0
        step("拉取演示样本 → datasets=1", lambda: len(st["datasets"]) == 1)
        step("数据可解析出 4 个站点", lambda: st["datasets"][0]["siteCount"] == 4)

        print("\n[4] ④ 生成站点 + 注入 App")
        pg.evaluate("() => __KOUL__.show('sites')")
        n = pg.evaluate("(id) => __KOUL__.generate(id)", dsid)
        pg.wait_for_timeout(120)
        st = pg.evaluate("() => __KOUL__.state()")
        registry = pg.evaluate("() => __KOUL__.buildRegistry()")
        report["assertions"]["sites"] = len(st["sites"])
        report["assertions"]["registry_sites"] = len(registry["sites"])
        step("生成 4 个站点", lambda: len(st["sites"]) == 4)
        step("registry 含 4 条已启用站点", lambda: len(registry["sites"]) == 4)
        pg.evaluate("() => __KOUL__.inject()")
        pg.wait_for_timeout(120)
        calls = pg.evaluate("() => __KOUL__.calls()")
        inject_calls = [c for c in calls
                        if c["m"] == "req" and "/manage/csp" in str(c["data"].get("url", ""))]
        report["assertions"]["inject_calls"] = len(inject_calls)
        step("POST /manage/csp 站点注入", lambda: len(inject_calls) == 1)

        print("\n[5] ⑤ 站点运行")
        pg.evaluate("() => __KOUL__.show('run')")
        sid = st["sites"][0]["id"]
        vods = pg.evaluate("(id) => __KOUL__.run(id, '影片')", sid)
        pg.wait_for_timeout(120)
        calls = pg.evaluate("() => __KOUL__.calls()")   # 运行后重新取，含本次 fm.req
        report["assertions"]["vods"] = len(vods)
        report["assertions"]["vod_urls"] = [v["url"] for v in vods]
        step("页内运行产出 3 条可播放条目", lambda: len(vods) == 3)
        step("运行走 fm.req + DOMParser", lambda: any(
            c["m"] == "req" and "home.html" in str(c["data"].get("url", "")) for c in calls))

        print("\n[6] ⑥ 桥接 App 播放器播放")
        pg.evaluate("() => __KOUL__.show('play')")
        for i in range(len(vods)):
            pg.evaluate("(i) => __KOUL__.play(__KOUL__.vods()[i])", i)
        pg.wait_for_timeout(200)
        calls = pg.evaluate("() => __KOUL__.calls()")
        bridges = {}
        for c in calls:
            if c["m"] in ("play", "pan.play", "vodInline"):
                bridges[c["m"]] = bridges.get(c["m"], 0) + 1
        st = pg.evaluate("() => __KOUL__.state()")
        report["assertions"]["play_bridges"] = bridges
        report["assertions"]["play_log"] = len(st["playLog"])
        step("fm.play 直链桥被调用", lambda: bridges.get("play", 0) >= 1)
        step("fm.pan.play 网盘桥被调用", lambda: bridges.get("pan.play", 0) >= 1)
        step("fm.vodInline 多集桥被调用", lambda: bridges.get("vodInline", 0) >= 1)
        step("播放记录落库 3 条", lambda: len(st["playLog"]) == 3)

        print("\n[7] 页面异常 / 日志")
        report["page_errors"] = errors
        step("页面 JS 异常为 0", lambda: len(errors) == 0)
        log_lines = pg.evaluate("() => __KOUL__.logs().length")
        report["assertions"]["log_lines"] = log_lines
        print("  运行日志 %d 条" % log_lines)

        # 截图：回到入口总览
        pg.evaluate("() => __KOUL__.show('home')")
        pg.wait_for_timeout(150)
        shot = os.path.join(ROOT, "koul_onestop_poc.png")
        pg.screenshot(path=shot, full_page=False)
        print("  截图:", shot)
        pg.close()
        b.close()

    checks = [s["result"] for s in report["steps"]]
    report["passed"] = sy["passed"] and all(checks)
    report["summary"] = "%d/%d 断言通过" % (sum(1 for c in checks if c), len(checks))

    out = os.path.join(ROOT, "verify_report.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 72)
    print("结果: %s  语法基线 %s" % (report["summary"], "通过" if sy["passed"] else "未通过"))
    print("JSON 报告:", out)
    print("=" * 72)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
