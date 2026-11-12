#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
前端重写（录口令→生成站点→点站点播放）· 增量推送到已有分支 oktv-cat-hiker-v3。

- 只追加 1 个 commit（parent = 当前分支 HEAD），不新建分支、不动 4 个基线分支；
- 只改 assets 应用层：新增 hiker_app.js、重写 index.html；
- 附验证脚本/报告/预览图/交付说明到 hiker-entry/ 前缀，不干扰 Gradle 工程；
- blob→tree(base_tree)→commit→PATCH ref(force=false)；
- 推送后逐文件 SHA-1 + SHA-256 双重复核；
- 凭据只从 GH_TOKEN 环境变量读取。
"""
import os, sys, json, base64, hashlib, time, http.client
import urllib.request, urllib.error, urllib.parse

ROOT = os.path.dirname(os.path.abspath(__file__))
WS = os.path.dirname(ROOT)
TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
BRANCH = "oktv-cat-hiker-v3"
BASELINES = ["oktv-cat", "oktv-cat-koul-onestop", "oktv-cat-hiker-entry", "oktv-cat-hiker-v2"]
API = "https://api.github.com"

if not TOKEN:
    print("缺 GH_TOKEN 环境变量"); sys.exit(1)

PAIRS = [
    # ---- 工程真实路径（参与 Gradle 构建 / WebView 加载）----
    ("app/src/main/assets/hiker/hiker_app.js",
     ROOT + "/app/assets/hiker/hiker_app.js"),
    ("app/src/main/assets/hiker/index.html",
     ROOT + "/app/assets/hiker/index.html"),
    # ---- 验证 / 审计 / 预览（hiker-entry/ 前缀，不干扰工程）----
    ("hiker-entry/verify_frontend.py",                 ROOT + "/verify_frontend.py"),
    ("hiker-entry/verify_frontend_report.json",        ROOT + "/verify_frontend_report.json"),
    ("hiker-entry/前端预览_01_录口令.png",              ROOT + "/前端预览_01_录口令.png"),
    ("hiker-entry/前端预览_02_站点内容.png",            ROOT + "/前端预览_02_站点内容.png"),
    ("hiker-entry/push_frontend.py",                   ROOT + "/push_frontend.py"),
    ("hiker-entry/前端重写_口令到播放_交付说明.md",      WS + "/前端重写_口令到播放_交付说明.md"),
]

MSG = ("feat(hiker/frontend): 重写前端为「录口令→生成站点→点站点播放」用户流程\n\n"
       "- index.html: 7 个后端数据可视化/自测 tab → 4 个用户视图\n"
       "  (录口令 / 站点网格 / 站点内容 / 详情选集)，移除能力表/覆盖率/兼容性表/registry 注入\n"
       "- 新增 hiker_app.js 应用逻辑层：口令解析 + 站点生成 + home/cate/search/detail/play\n"
       "- 口令四形态：单口令 / 多口令 / 含密码 / 导入(粘贴/剪贴板/文件)\n"
       "- 真实格式(取自 xingchen HkRuleManager)：云1~云10 oooole 通道映射、"
       "￥home_rule￥ 分享包装、base64:// 壳、裸规则 JSON/数组、{sites:[]} 配置、文本行\n"
       "- 规则在新鲜作用域执行并捕获 setResult 完整值(不截断)，结果归一化后交 App 播放器\n"
       "- 选择器/Rhino/Python/加密/配置型(csp/jar) 明确降级，不假成功；云口令离线明确提示\n"
       "- Java 运行时层(8 类)/同步桥/6 个既有 assets/入口/工作流 零改动\n"
       "- 验证: verify_frontend.py 24/24；无头 Chromium 真实点击全链路通过；"
       "360/390/430/500px 零横向溢出")


def call(method, path, payload=None, retries=5):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    last = None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(API + path, data=data, method=method, headers={
            "Authorization": "Bearer " + TOKEN,
            "User-Agent": "cb-hiker-frontend",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code >= 500 and attempt < retries:
                print("  重试 %d/%d (HTTP %s) %s" % (attempt, retries, e.code, path))
                time.sleep(1.5 * attempt); continue
            if e.code != 404:
                print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:600]))
            raise
        except (urllib.error.URLError, http.client.IncompleteRead, OSError) as e:
            last = e
            if attempt < retries:
                print("  重试 %d/%d (%s) %s" % (attempt, retries, type(e).__name__, path))
                time.sleep(1.5 * attempt); continue
            raise
    raise last


def blob_sha1(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def sha256hex(raw):
    return hashlib.sha256(raw).hexdigest()


def main():
    print("认证身份:", call("GET", "/user").get("login"))
    ref = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))
    head = ref["object"]["sha"]
    base_tree = call("GET", "/repos/%s/git/commits/%s" % (REPO, head))["tree"]["sha"]
    print("分支 %s @ %s  tree=%s" % (BRANCH, head[:12], base_tree[:12]))

    # 只探测本清单涉及的 8 个路径：用 contents API 逐路径取 blob sha（小响应）。
    # 刻意不拉 ?recursive=1 大 tree —— 该仓库 tree 约 900KB，网络层会 IncompleteRead。
    def probe(path):
        try:
            q = "/".join(urllib.parse.quote(seg, safe="") for seg in path.split("/"))
            d = call("GET", "/repos/%s/contents/%s?ref=%s" % (REPO, q, BRANCH), retries=2)
            return d.get("sha") if isinstance(d, dict) else None
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None     # 新增文件
            raise
    remote = {}
    for rp, lp in PAIRS:
        s = probe(rp)
        if s:
            remote[rp] = s
        print("  probe %-64s %s" % (rp, (s[:12] if s else "新增")))

    baseline_heads = {}
    for b in BASELINES:
        try:
            baseline_heads[b] = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, b))["object"]["sha"]
        except urllib.error.HTTPError:
            baseline_heads[b] = None

    changed, unchanged = {}, []
    for rp, lp in PAIRS:
        if not os.path.isfile(lp):
            print("缺本地文件:", lp); sys.exit(3)
        raw = open(lp, "rb").read()
        lsha = blob_sha1(raw)
        if remote.get(rp) == lsha:
            unchanged.append(rp)
        else:
            changed[rp] = (lp, raw, lsha)
    print("清单 %d 个：变化/新增 %d / 未变 %d" % (len(PAIRS), len(changed), len(unchanged)))
    for p in sorted(changed):
        print("  %s %-64s %7d B" %
              ("新增" if p not in remote else "改动", p, len(changed[p][1])))
    if not changed:
        print("无变化，无需提交"); sys.exit(0)

    items = sorted(changed.items())
    tree_items = []
    for i, (path, (lp, raw, lsha)) in enumerate(items, 1):
        blob = call("POST", "/repos/%s/git/blobs" % REPO,
                    {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        assert blob["sha"] == lsha, "blob sha 不匹配 %s" % path
        tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %d/%d %s" % (i, len(items), path.split("/")[-1]))

    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": tree_items})
    print("新 tree:", tree["sha"][:12])
    commit = call("POST", "/repos/%s/git/commits" % REPO,
                  {"message": MSG, "tree": tree["sha"], "parents": [head]})
    new_sha = commit["sha"]
    print("新 commit:", new_sha[:12], "parent:", head[:12])
    upd = call("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
               {"sha": new_sha, "force": False})
    print("分支已更新 ->", upd["object"]["sha"][:12])

    # 复核：非递归小响应逐目录取，避免大 tree 的 IncompleteRead
    rows, ok = [], True
    for path, (lp, raw, lsha) in items:
        blob = call("GET", "/repos/%s/git/blobs/%s" % (REPO, lsha))
        content = base64.b64decode(blob.get("content", "")) if blob.get("encoding") == "base64" else b""
        good = (sha256hex(content) == sha256hex(raw)) and len(content) == len(raw)
        ok = ok and good
        rows.append({"path": path, "bytes": len(raw), "local_sha1": lsha,
                     "local_sha256": sha256hex(raw), "remote_sha256": sha256hex(content),
                     "match": good})
    print("\n===== 远端/本地一致性复核（SHA-1 + SHA-256）=====")
    for r in rows:
        print("  %s %-64s %d B" % ("OK " if r["match"] else "BAD", r["path"], r["bytes"]))
    print("复核结论:", "全部一致" if ok else "存在不一致")

    after = {}
    for b in BASELINES:
        try:
            after[b] = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, b))["object"]["sha"]
        except urllib.error.HTTPError:
            after[b] = None
    baseline_untouched = (after == baseline_heads)
    print("基线分支未变动:", baseline_untouched)

    out = {
        "branch": BRANCH, "parent": head, "commit": new_sha, "tree": tree["sha"],
        "changed": len(changed), "unchanged": len(unchanged),
        "file_list": sorted(changed),
        "verify_all_match": ok, "verify_rows": rows,
        "baseline_heads_before": baseline_heads, "baseline_heads_after": after,
        "baseline_untouched": baseline_untouched,
        "url": "https://github.com/%s/tree/%s" % (REPO, BRANCH),
    }
    with open(os.path.join(ROOT, "push_frontend_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n完成:", out["url"])
    sys.exit(0 if (ok and baseline_untouched) else 5)


if __name__ == "__main__":
    main()
