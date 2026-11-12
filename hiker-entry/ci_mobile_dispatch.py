#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""触发 oktv-cat-hiker-v3 的 Android Release 构建（含 mobile 变体），并定位新建 run。"""
import os, sys, json, time, subprocess
import urllib.request, urllib.error

ROOT = os.path.dirname(os.path.abspath(__file__))
HERE = "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d6c-6c38ebd20000"
TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
BRANCH = "oktv-cat-hiker-v3"
EXPECT_HEAD = os.environ.get("EXPECT_HEAD", "f660d5e83b43")
API = "https://api.github.com"

if not TOKEN:
    print("缺 GH_TOKEN 环境变量"); sys.exit(1)


def call(method, path, payload=None, retries=4):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    last = None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(API + path, data=data, method=method, headers={
            "Authorization": "Bearer " + TOKEN,
            "User-Agent": "cb-hiker-mobile-ci",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code >= 500 and attempt < retries:
                last = e; time.sleep(1.5 * attempt); continue
            print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:600])); raise
        except (urllib.error.URLError, OSError) as e:
            last = e
            if attempt < retries:
                print("  重试 %d/%d %s" % (attempt, retries, type(e).__name__))
                time.sleep(1.5 * attempt); continue
            raise
    raise last


def tag_exists(tag):
    try:
        call("GET", "/repos/%s/git/ref/tags/%s" % (REPO, tag))
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def main():
    print("认证身份:", call("GET", "/user").get("login"))
    head = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BRANCH))["object"]["sha"]
    print("分支 HEAD:", head[:12])
    assert head.startswith(EXPECT_HEAD), "HEAD 与预期不一致，拒绝触发：%s" % head[:12]

    # tag：GMT+8 当前分钟，若已存在则顺延分钟，确保不与历史 tag 冲突
    ts = subprocess.check_output(["bash", "-c", "TZ=Asia/Shanghai date +%Y%m%d%H%M"]).decode().strip()
    tag = "v5.6.0-beta-" + ts
    from datetime import datetime, timedelta
    base = datetime.strptime(ts, "%Y%m%d%H%M")
    bump = 0
    while tag_exists(tag) and bump < 60:
        bump += 1
        tag = "v5.6.0-beta-" + (base + timedelta(minutes=bump)).strftime("%Y%m%d%H%M")
    print("release_tag:", tag, "(顺延 %d 分钟)" % bump)

    inputs = {
        "release_tag": tag,
        "release_channel": "beta",
        "prerelease": "true",
        "apk_variants": "mobile-arm64_v8a,leanback-arm64_v8a",
        "sync_cnb": "false",
        "publish_oci": "false",
        "release_notes": (
            "手机版首页补齐海阔独立入口（底部导航第 5 项，点击跳 HikerActivity 独立 WebView）。"
            "\\n本包同时含 mobile / leanback 两个 arm64-v8a 变体；运行时层与桥层零改动。"
            "\\n构建前兼容性验证 31/31 通过，桥层 ecj/d8 编译回归通过。"
        ),
    }
    print("构建参数:", json.dumps(inputs, ensure_ascii=False))

    call("POST", "/repos/%s/actions/workflows/android-release.yml/dispatches" % REPO,
         {"ref": BRANCH, "inputs": inputs})
    print("workflow_dispatch 已发送（HTTP 204）")

    # 定位新建 run（head_sha 匹配且创建时间在本脚本启动之后）
    started = time.time() - 120
    run = None
    for i in range(1, 25):
        runs = call("GET", "/repos/%s/actions/workflows/android-release.yml/runs"
                    "?event=workflow_dispatch&per_page=10" % REPO)
        for r in runs.get("workflow_runs", []):
            if r["head_sha"].startswith(EXPECT_HEAD) and r["created_at"]:
                from datetime import datetime, timezone
                ct = datetime.strptime(r["created_at"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp()
                if ct >= started:
                    run = r
                    break
        if run:
            break
        print("  等待 run 出现 %d/24 ..." % i)
        time.sleep(5)

    if not run:
        print("未能定位到新 run，请到 Actions 页面核对"); sys.exit(2)

    out = {
        "run_id": run["id"],
        "html_url": run["html_url"],
        "head_sha": run["head_sha"],
        "head_branch": run["head_branch"],
        "status": run["status"],
        "created_at": run["created_at"],
        "tag": tag,
        "inputs": inputs,
        "branch": BRANCH,
    }
    with open(os.path.join(ROOT, "ci_mobile_dispatch.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n=== 已触发 ===")
    print("run_id:", run["id"])
    print("url:", run["html_url"])
    print("head_sha:", run["head_sha"][:12])
    print("tag:", tag)


if __name__ == "__main__":
    main()
