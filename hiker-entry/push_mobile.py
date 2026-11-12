#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机版海阔独立入口 · 增量推送到已有分支 oktv-cat-hiker-v3。

- 只追加 1 个 commit（parent = 当前分支 HEAD），不新建分支、不动 oktv-cat 等基线；
- 精确清单，按 Git blob SHA-1 只提交真正变化/新增的文件；
- 用 PATCH git/refs 更新分支（force=false，防止并发覆盖）；
- 推送后逐文件做 SHA-1 + SHA-256 双重复核；
- 顺带取回 app/build.gradle 的 versionName，供随后触发 CI 时拼 release_tag；
- 凭据只从 GH_TOKEN 环境变量读取，脚本内不含任何 token。
"""
import os, sys, json, base64, hashlib, re, time, http.client
import urllib.request, urllib.error

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
    # ---- 工程真实路径（参与 Gradle 构建）----
    ("app/src/mobile/res/menu/menu_nav.xml",
     ROOT + "/app/src/mobile/res/menu/menu_nav.xml"),
    ("app/src/mobile/res/drawable/ic_nav_hiker.xml",
     ROOT + "/app/src/mobile/res/drawable/ic_nav_hiker.xml"),
    ("app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java",
     ROOT + "/app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java"),
    # ---- 验证与审计产物（hiker-entry/ 前缀，不干扰工程）----
    ("hiker-entry/verify_mobile_entry.py",                 ROOT + "/verify_mobile_entry.py"),
    ("hiker-entry/verify_mobile_entry_report.json",       ROOT + "/verify_mobile_entry_report.json"),
    ("hiker-entry/app/patch/mobile_changes.diff",         ROOT + "/app/patch/mobile_changes.diff"),
    ("hiker-entry/app/patch/mobile_base/menu_nav.xml",    ROOT + "/app/patch/mobile_base/menu_nav.xml"),
    ("hiker-entry/app/patch/mobile_base/HomeActivity.java", ROOT + "/app/patch/mobile_base/HomeActivity.java"),
    ("hiker-entry/app/patch/mobile_base/ic_nav_lab.xml",  ROOT + "/app/patch/mobile_base/ic_nav_lab.xml"),
    ("hiker-entry/app/patch/mobile_base/android-release.yml", ROOT + "/app/patch/mobile_base/android-release.yml"),
    ("hiker-entry/push_mobile.py",                        ROOT + "/push_mobile.py"),
    ("hiker-entry/手机版入口补齐_构建前兼容性分析.md",      WS + "/手机版入口补齐_构建前兼容性分析.md"),
]

MSG = ("feat(hiker/mobile): 手机版首页补齐海阔独立入口（底部导航第 5 项）\n\n"
       "- menu_nav.xml 新增 @+id/hiker：4→5 项，未越 BottomNavigationView MAX_ITEM_COUNT=5\n"
       "- 新增 ic_nav_hiker.xml：48dp/tint/viewport 与既有 ic_nav_lab 完全一致\n"
       "- HomeActivity: setNavigation() 置可见；onNavigationItemSelected() 跳\n"
       "  com.fongmi.android.tv.hiker.HikerEntry.start(this) 并 return false\n"
       "- 与 lab 同模式：不占 FragmentStateManager position，不改选中态/currentPosition\n"
       "- 标题复用 main 的 home_hiker（中/英/繁），全限定名调用，import 段零改动\n"
       "- 运行时层(main 8 类)/桥/WebView/assets/leanback 电视版入口零改动\n"
       "- 验证: verify_mobile_entry.py 31/31；build_verify.sh ecj 0 错误 / 15 class / 44KB dex\n"
       "- 交付: 构建前兼容性分析 + mobile_base 基线快照 + mobile_changes.diff")


def call(method, path, payload=None, retries=4):
    """GitHub API 偶发 IncompleteRead / 5xx：GET 幂等可重试；
    POST 在读取响应失败时重试最多产生一个孤立对象，不影响分支指向。"""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    last = None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(API + path, data=data, method=method, headers={
            "Authorization": "Bearer " + TOKEN,
            "User-Agent": "cb-hiker-mobile",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code >= 500 and attempt < retries:
                last = e
                print("  重试 %d/%d（HTTP %s）%s" % (attempt, retries, e.code, path))
                time.sleep(1.5 * attempt)
                continue
            print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:600]))
            raise
        except (urllib.error.URLError, http.client.IncompleteRead, OSError) as e:
            last = e
            if attempt < retries:
                print("  重试 %d/%d（%s）%s" % (attempt, retries, type(e).__name__, path))
                time.sleep(1.5 * attempt)
                continue
            raise
    raise last


def git_blob_sha1(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def sha256hex(raw):
    return hashlib.sha256(raw).hexdigest()


def main():
    print("认证身份:", call("GET", "/user").get("login"))

    ref = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))
    head = ref["object"]["sha"]
    commit0 = call("GET", "/repos/%s/git/commits/%s" % (REPO, head))
    base_tree = commit0["tree"]["sha"]
    tree_all = call("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, base_tree))
    remote = {t["path"]: t["sha"] for t in tree_all["tree"] if t["type"] == "blob"}
    print("分支 %s @ %s  tree=%s  blob=%d" %
          (BRANCH, head[:12], base_tree[:12], len(remote)))

    # 基线分支 HEAD 快照（构建后复核用）
    baseline_heads = {}
    for b in BASELINES:
        try:
            baseline_heads[b] = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, b))["object"]["sha"]
        except urllib.error.HTTPError:
            baseline_heads[b] = None
    print("基线分支 HEAD 已记录:", {k: (v[:8] if v else None) for k, v in baseline_heads.items()})

    # versionName（拼 tag 用）
    bg_sha = remote.get("app/build.gradle")
    version_name = None
    if bg_sha:
        bg = call("GET", "/repos/%s/git/blobs/%s" % (REPO, bg_sha))
        txt = base64.b64decode(bg["content"]).decode("utf-8", "replace")
        m = re.search(r"versionName\s*[=:]\s*[\"']([^\"']+)[\"']", txt)
        version_name = m.group(1) if m else None
    print("app/build.gradle versionName =", version_name)

    changed, unchanged = {}, []
    for rp, lp in PAIRS:
        if not os.path.isfile(lp):
            print("缺本地文件:", lp); sys.exit(3)
        raw = open(lp, "rb").read()
        lsha = git_blob_sha1(raw)
        if remote.get(rp) == lsha:
            unchanged.append(rp)
        else:
            changed[rp] = (lp, raw, lsha)
    print("清单 %d 个：变化/新增 %d / 未变 %d" % (len(PAIRS), len(changed), len(unchanged)))
    for p in sorted(changed):
        print("  %s %-70s %7d B" %
              ("新增" if p not in remote else "改动", p, len(changed[p][1])))
    if not changed:
        print("无变化，无需提交"); sys.exit(0)

    # 1) blobs
    items = sorted(changed.items())
    tree_items = []
    for i, (path, (full, raw, lsha)) in enumerate(items, 1):
        blob = call("POST", "/repos/%s/git/blobs" % REPO,
                    {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        assert blob["sha"] == lsha, "blob sha 不匹配 %s" % path
        tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %d/%d %s" % (i, len(items), path.split("/")[-1]))

    # 2) tree
    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": tree_items})
    print("新 tree:", tree["sha"][:12])

    # 3) commit
    commit = call("POST", "/repos/%s/git/commits" % REPO,
                  {"message": MSG, "tree": tree["sha"], "parents": [head]})
    new_sha = commit["sha"]
    print("新 commit:", new_sha[:12], "parent:", head[:12])

    # 4) 更新分支 ref（force=false）
    upd = call("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
               {"sha": new_sha, "force": False})
    print("分支已更新 ->", upd["object"]["sha"][:12])

    # 5) 复核：tree 中 sha 一致 + blob 内容 SHA-256 一致
    vmap = {t["path"]: t["sha"] for t in
            call("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, tree["sha"]))["tree"]
            if t["type"] == "blob"}
    rows, ok = [], True
    for path, (full, raw, lsha) in items:
        rsha = vmap.get(path)
        blob = call("GET", "/repos/%s/git/blobs/%s" % (REPO, rsha)) if rsha else {}
        content = base64.b64decode(blob.get("content", "")) if blob.get("encoding") == "base64" else b""
        good = (rsha == lsha) and (sha256hex(content) == sha256hex(raw))
        ok = ok and good
        rows.append({"path": path, "bytes": len(raw),
                     "local_sha1": lsha, "remote_sha1": rsha,
                     "local_sha256": sha256hex(raw), "remote_sha256": sha256hex(content),
                     "match": good})
    print("\n===== 远端/本地一致性复核（SHA-1 + SHA-256）=====")
    for r in rows:
        print("  %s %-70s %d B" % ("OK " if r["match"] else "BAD", r["path"], r["bytes"]))
    print("复核结论:", "全部一致 ✅" if ok else "存在不一致 ❌")

    # 6) 基线分支未被改动
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
        "baseline_heads_before": baseline_heads,
        "baseline_heads_after": after,
        "baseline_untouched": baseline_untouched,
        "version_name": version_name,
        "url": "https://github.com/%s/tree/%s" % (REPO, BRANCH),
    }
    with open(os.path.join(ROOT, "push_mobile_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n完成:", out["url"])
    sys.exit(0 if (ok and baseline_untouched) else 5)


if __name__ == "__main__":
    main()
