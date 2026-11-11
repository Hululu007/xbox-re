#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段八「出包」：基于 oktv-cat-hiker-v2 用 Git Data API 增量提交到新分支
oktv-cat-hiker-v3。不动 oktv-cat 基线，不覆盖任何已有分支。

要点：
- 精确清单（不做目录扫描，避免误传中间产物）；
- 逐文件计算 Git blob SHA-1，与 v2 tree 对比，只提交真正变化的文件；
- 推送后用 Git blob API 取回内容，做 SHA-1 + SHA-256 双重复核；
- 凭据只从 GH_TOKEN 环境变量读取，脚本内不含任何 token，可安全入库。
"""
import os, sys, json, base64, hashlib, urllib.request, urllib.error

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
BASE_BRANCH = "oktv-cat-hiker-v2"
NEW_BRANCH = os.environ.get("NEW_BRANCH", "oktv-cat-hiker-v3")
ROOT = os.path.dirname(os.path.abspath(__file__))
WS = os.path.dirname(ROOT)
API = "https://api.github.com"

if not TOKEN:
    print("缺 GH_TOKEN 环境变量"); sys.exit(1)

JAVA = ROOT + "/app/src/main/java/com/fongmi/android/tv/hiker/"
ASSETS = ROOT + "/app/assets/hiker/"

# 仓库内路径 -> 本地路径。工程源码落真实路径（checkout 后参与 gradle 构建），
# 交付文档/脚本/报告归 hiker-entry/ 前缀，不干扰工程。
PAIRS = [
    # ---- 工程真实路径：新增 3 个纯 JDK 类 + 改动 Java 桥 ----
    ("app/src/main/java/com/fongmi/android/tv/hiker/HikerApi.java",      JAVA + "HikerApi.java"),
    ("app/src/main/java/com/fongmi/android/tv/hiker/HikerJson.java",     JAVA + "HikerJson.java"),
    ("app/src/main/java/com/fongmi/android/tv/hiker/HikerQR.java",       JAVA + "HikerQR.java"),
    ("app/src/main/java/com/fongmi/android/tv/hiker/HikerBridge.java",   JAVA + "HikerBridge.java"),
    # ---- 工程真实路径：改动的 2 个 assets ----
    ("app/src/main/assets/hiker/hiker_api_surface.js",                   ASSETS + "hiker_api_surface.js"),
    ("app/src/main/assets/hiker/hiker_bridge_adapter.js",                ASSETS + "hiker_bridge_adapter.js"),
    # ---- 交付包：阶段八新增产物 ----
    ("hiker-entry/verify_bridge_api.py",                                 ROOT + "/verify_bridge_api.py"),
    ("hiker-entry/verify_bridge_api_report.json",                        ROOT + "/verify_bridge_api_report.json"),
    ("hiker-entry/hiker_v3_poc.png",                                     ROOT + "/hiker_v3_poc.png"),
    ("hiker-entry/push_v3.py",                                           ROOT + "/push_v3.py"),
    # ---- 交付包：本轮更新的文档/报告 ----
    ("hiker-entry/app/BUILD.md",                                         ROOT + "/app/BUILD.md"),
    ("hiker-entry/两项目对照_经验教训与超越方案.md",                      ROOT + "/两项目对照_经验教训与超越方案.md"),
    ("hiker-entry/compat_report.json",                                   ROOT + "/compat_report.json"),
    ("hiker-entry/verify_entry_report.json",                             ROOT + "/verify_entry_report.json"),
    ("hiker-entry/verify_v2_report.json",                                ROOT + "/verify_v2_report.json"),
    ("hiker-entry/hiker_compat_poc.png",                                 ROOT + "/hiker_compat_poc.png"),
    ("hiker-entry/hiker_entry_poc.png",                                  ROOT + "/hiker_entry_poc.png"),
    ("hiker-entry/hiker_v2_poc.png",                                     ROOT + "/hiker_v2_poc.png"),
    # ---- /workspace 顶层交付文档（与 v2 口径一致，归入 hiker-entry/ 前缀）----
    ("hiker-entry/两项目对照_交付说明.md",                                WS + "/两项目对照_交付说明.md"),
    ("hiker-entry/阶段八补桥方法_交付说明.md",                            WS + "/阶段八补桥方法_交付说明.md"),
    ("hiker-entry/出包就绪度评估_兼容性缺口清单.md",                      WS + "/出包就绪度评估_兼容性缺口清单.md"),
]


def call(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
        headers={"Authorization": "Bearer " + TOKEN, "User-Agent": "cb-hiker-v3",
                 "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:600]))
        raise


def git_blob_sha1(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def sha256hex(raw):
    return hashlib.sha256(raw).hexdigest()


def main():
    print("认证身份:", call("GET", "/user").get("login"))

    # 基线分支 + 递归 tree
    base = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BASE_BRANCH))
    base_sha = base["object"]["sha"]
    base_commit = call("GET", "/repos/%s/git/commits/%s" % (REPO, base_sha))
    base_tree = base_commit["tree"]["sha"]
    tree_all = call("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, base_tree))
    remote = {t["path"]: t["sha"] for t in tree_all["tree"] if t["type"] == "blob"}
    print("基线 %s @ %s  tree=%s  blob=%d" %
          (BASE_BRANCH, base_sha[:12], base_tree[:12], len(remote)))

    # 目标分支必须不存在
    try:
        call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, NEW_BRANCH))
        print("!! 分支已存在，换 NEW_BRANCH"); sys.exit(2)
    except urllib.error.HTTPError:
        pass

    # 校验本地文件齐全，并按 Git blob SHA-1 过滤出真正变化的文件
    changed, unchanged = {}, []
    for rp, lp in PAIRS:
        if not os.path.isfile(lp):
            print("缺本地文件:", lp); sys.exit(3)
        raw = open(lp, "rb").read()
        local_sha = git_blob_sha1(raw)
        if remote.get(rp) == local_sha:
            unchanged.append(rp)
        else:
            changed[rp] = (lp, raw, local_sha)
    print("清单 %d 个：变化 %d / 未变 %d" % (len(PAIRS), len(changed), len(unchanged)))
    for p in sorted(changed):
        flag = "新增" if p not in remote else "改动"
        print("  %s %-62s %7d B" % (flag, p, len(changed[p][1])))

    if not changed:
        print("无变化，无需提交"); sys.exit(0)

    # 1) 建 blob
    tree_items = []
    items = sorted(changed.items())
    for i, (path, (full, raw, lsha)) in enumerate(items, 1):
        blob = call("POST", "/repos/%s/git/blobs" % REPO,
                    {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        assert blob["sha"] == lsha, "blob sha 不匹配 %s" % path
        tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %d/%d %s" % (i, len(items), path.split("/")[-1]))

    # 2) tree（base_tree 保留 v2 全部文件，只叠加变化项）
    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": tree_items})
    print("新 tree:", tree["sha"][:12])

    # 3) commit
    msg = ("feat(hiker-v3): 补桥方法 — 25 个官方 bridge 档 API 从「有符号」补齐为「有语义」\n\n"
           "- 新增 HikerJson（极简 JSON + 自建 Base64，避 API26+）/ HikerQR（QR 编码器）\n"
           "- 新增 HikerApi：call(name,argsJson) 分发 35 个方法名，纯 JDK 真实现\n"
           "  md5/AES/RSA/Cookie/缓存/并发批量/POST/下载/M3U8/懒规则/IP/base64 图片/二维码\n"
           "- HikerBridge：@JavascriptInterface 13→31，新增 call 通用主通道 + 18 具名方法\n"
           "- JS 转发：bridgeCall 优先 HikerBridge.call，参数显式序列化，解决数组/对象隐式转换\n"
           "- 3 内核依赖仍明确降级：fetchCodeByWebView / x5Rule / parseLazyRuleAsync\n"
           "- 实证：verify_bridge_api.py 51/51（QR 与 python-qrcode 逐格一致 6/6）；\n"
           "  ecj+android-all 0 错误 → 44KB dex；verify_v2 36/36 / verify_entry 44/44 /\n"
           "  verify_patch 45/45 / compat A~E 无回退；134/134 覆盖率保持\n"
           "- 交付：阶段八补桥方法_交付说明 / 出包就绪度评估_兼容性缺口清单 / hiker_v3_poc.png")
    commit = call("POST", "/repos/%s/git/commits" % REPO,
                  {"message": msg, "tree": tree["sha"], "parents": [base_sha]})
    print("新 commit:", commit["sha"][:12])

    # 4) create ref
    ref = call("POST", "/repos/%s/git/refs" % REPO,
               {"ref": "refs/heads/" + NEW_BRANCH, "sha": commit["sha"]})
    print("新分支 %s -> %s" % (NEW_BRANCH, ref["object"]["sha"][:12]))

    # 5) 复核：取回新 tree，逐文件比对 Git SHA-1；再用 blob API 取回内容算 SHA-256
    verify_tree = call("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, tree["sha"]))
    verify_map = {t["path"]: t["sha"] for t in verify_tree["tree"] if t["type"] == "blob"}
    rows, ok = [], True
    for path, (full, raw, lsha) in items:
        rsha = verify_map.get(path)
        blob = call("GET", "/repos/%s/git/blobs/%s" % (REPO, rsha)) if rsha else {}
        content = base64.b64decode(blob.get("content", "")) if blob.get("encoding") == "base64" else b""
        r256 = sha256hex(content)
        l256 = sha256hex(raw)
        good = (rsha == lsha) and (r256 == l256)
        ok = ok and good
        rows.append({"path": path, "bytes": len(raw),
                     "local_sha1": lsha, "remote_sha1": rsha,
                     "local_sha256": l256, "remote_sha256": r256, "match": good})
    print("\n===== 远端/本地一致性复核（SHA-1 + SHA-256）=====")
    for r in rows:
        print("  %s %-62s %d B  sha256=%s…" %
              ("OK " if r["match"] else "BAD", r["path"], r["bytes"], r["remote_sha256"][:12]))
    print("复核结论:", "全部一致 ✅" if ok else "存在不一致 ❌")

    out = {"branch": NEW_BRANCH, "base_branch": BASE_BRANCH, "base_sha": base_sha,
           "commit": commit["sha"], "tree": tree["sha"],
           "changed": len(changed), "unchanged": len(unchanged),
           "file_list": sorted(changed), "verify_all_match": ok, "verify_rows": rows,
           "url": "https://github.com/%s/tree/%s" % (REPO, NEW_BRANCH)}
    with open(os.path.join(ROOT, "push_v3_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n完成: %s" % out["url"])
    sys.exit(0 if ok else 5)


if __name__ == "__main__":
    main()
