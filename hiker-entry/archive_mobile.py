#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建后归档：把结果报告与 CI 脚本/结果 JSON 追加为 docs-only commit。
构建基于 commit f660d5e8，本提交不改变任何工程源码、不改变已产出的 APK。"""
import os, sys, json, base64, hashlib, time, http.client
import urllib.request, urllib.error

ROOT = os.path.dirname(os.path.abspath(__file__))
WS = os.path.dirname(ROOT)
TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
BRANCH = "oktv-cat-hiker-v3"
API = "https://api.github.com"
if not TOKEN:
    print("缺 GH_TOKEN 环境变量"); sys.exit(1)

PAIRS = [
    ("hiker-entry/手机版入口补齐与构建_结果报告.md", WS + "/手机版入口补齐与构建_结果报告.md"),
    ("hiker-entry/ci_mobile_dispatch.py",            ROOT + "/ci_mobile_dispatch.py"),
    ("hiker-entry/ci_mobile_poll.py",                ROOT + "/ci_mobile_poll.py"),
    ("hiker-entry/ci_mobile_dispatch.json",          ROOT + "/ci_mobile_dispatch.json"),
    ("hiker-entry/ci_mobile_result.json",            ROOT + "/ci_mobile_result.json"),
    ("hiker-entry/archive_mobile.py",                ROOT + "/archive_mobile.py"),
]
MSG = ("docs(hiker/mobile): 归档手机版入口补齐与构建结果（run 43154069051 success）\n\n"
       "- 结果报告：mobile + leanback 两个 arm64-v8a APK，31 步 success，BUILD SUCCESSFUL\n"
       "- 归档 CI 触发/轮询脚本与 dispatch、result JSON\n"
       "- 构建基于 commit f660d5e8；本提交为纯文档/脚本归档，不改变已产出 APK")


def call(method, path, payload=None, retries=4):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    last = None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(API + path, data=data, method=method, headers={
            "Authorization": "Bearer " + TOKEN, "User-Agent": "cb-hiker-mobile-archive",
            "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code >= 500 and attempt < retries:
                last = e; time.sleep(1.5 * attempt); continue
            print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:400])); raise
        except (urllib.error.URLError, http.client.IncompleteRead, OSError) as e:
            last = e
            if attempt < retries:
                print("  重试 %d/%d %s" % (attempt, retries, type(e).__name__)); time.sleep(1.5 * attempt)
                continue
            raise
    raise last


def sha1(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def main():
    head = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))["object"]["sha"]
    base_tree = call("GET", "/repos/%s/git/commits/%s" % (REPO, head))["tree"]["sha"]
    remote = {t["path"]: t["sha"] for t in
              call("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, base_tree))["tree"]
              if t["type"] == "blob"}
    print("归档前 HEAD:", head[:12], "tree:", base_tree[:12])

    items = []
    for rp, lp in PAIRS:
        if not os.path.isfile(lp):
            print("缺本地文件:", lp); sys.exit(3)
        raw = open(lp, "rb").read()
        items.append((rp, raw, sha1(raw)))
    print("归档 %d 个文件" % len(items))

    tree_items = []
    for rp, raw, lsha in items:
        b = call("POST", "/repos/%s/git/blobs" % REPO,
                 {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        assert b["sha"] == lsha, "blob sha 不匹配 %s" % rp
        tree_items.append({"path": rp, "mode": "100644", "type": "blob", "sha": b["sha"]})

    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": tree_items})
    commit = call("POST", "/repos/%s/git/commits" % REPO,
                  {"message": MSG, "tree": tree["sha"], "parents": [head]})
    upd = call("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, BRANCH),
               {"sha": commit["sha"], "force": False})
    print("归档 commit:", commit["sha"][:12], "分支 ->", upd["object"]["sha"][:12])

    vmap = {t["path"]: t["sha"] for t in
            call("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, tree["sha"]))["tree"]
            if t["type"] == "blob"}
    ok = True
    for rp, raw, lsha in items:
        rsha = vmap.get(rp)
        b = call("GET", "/repos/%s/git/blobs/%s" % (REPO, rsha)) if rsha else {}
        content = base64.b64decode(b.get("content", "")) if b.get("encoding") == "base64" else b""
        good = (rsha == lsha) and (sha256(content) == sha256(raw))
        ok = ok and good
        print("  %s %-56s %6d B" % ("OK " if good else "BAD", rp, len(raw)))
    print("复核:", "全部一致 ✅" if ok else "存在不一致 ❌")

    out = {"branch": BRANCH, "parent": head, "archive_commit": commit["sha"],
           "build_commit": "f660d5e83b43", "files": [p for p, _, _ in items],
           "verify_all_match": ok,
           "url": "https://github.com/%s/tree/%s" % (REPO, BRANCH)}
    with open(os.path.join(ROOT, "archive_mobile_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    sys.exit(0 if ok else 5)


if __name__ == "__main__":
    main()
