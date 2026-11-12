#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""轮询手机版 CI 构建结果并逐项核验（run / steps / Release / APK / 基线分支 / 日志实证）。"""
import os, sys, json, time, io, zipfile, re
import urllib.request, urllib.error
import requests

ROOT = os.path.dirname(os.path.abspath(__file__))
TMP = "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d6c-6c38ebd20000"
TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
API = "https://api.github.com"

if not TOKEN:
    print("缺 GH_TOKEN 环境变量"); sys.exit(1)

DISPATCH = json.load(open(os.path.join(ROOT, "ci_mobile_dispatch.json"), encoding="utf-8"))
RUN_ID = DISPATCH["run_id"]
TAG = DISPATCH["tag"]
HEADERS = {"Authorization": "Bearer " + TOKEN, "User-Agent": "cb-hiker-mobile-ci",
           "Accept": "application/vnd.github+json"}


def call(path, retries=4):
    last = None
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(API + path, headers=HEADERS)
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code >= 500 and attempt < retries:
                last = e; time.sleep(1.5 * attempt); continue
            print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:400])); raise
        except (urllib.error.URLError, OSError) as e:
            last = e
            if attempt < retries:
                time.sleep(1.5 * attempt); continue
            raise
    raise last


def main():
    print("监控 run %s tag %s" % (RUN_ID, TAG), flush=True)
    run = None
    for i in range(1, 200):
        run = call("/repos/%s/actions/runs/%d" % (REPO, RUN_ID))
        if run.get("status") == "completed":
            break
        if i % 2 == 1:
            print("  [%3d] status=%s conclusion=%s" %
                  (i, run.get("status"), run.get("conclusion")), flush=True)
        time.sleep(30)
    conclusion = run.get("conclusion")
    print("\nrun 结束: status=%s conclusion=%s" % (run.get("status"), conclusion), flush=True)

    # 全部步骤
    jobs = call("/repos/%s/actions/runs/%d/jobs?per_page=10" % (REPO, RUN_ID))
    steps = []
    for j in jobs.get("jobs", []):
        for s in j.get("steps", []):
            steps.append({"name": s.get("name"), "status": s.get("status"),
                          "conclusion": s.get("conclusion"), "number": s.get("number")})
    bad = [s for s in steps if s["conclusion"] not in ("success", "skipped", None)]
    print("步骤: 共 %d，非 success/skipped: %d" % (len(steps), len(bad)), flush=True)
    for s in bad:
        print("   !! %s -> %s" % (s["name"], s["conclusion"]), flush=True)

    # run 日志实证
    log_evidence = {"downloaded": False, "build_success": None,
                    "mobile_task": None, "errors": []}
    try:
        r = requests.get(API + "/repos/%s/actions/runs/%d/logs" % (REPO, RUN_ID),
                         headers={"Authorization": "Bearer " + TOKEN,
                                  "User-Agent": "cb-hiker-mobile-ci"},
                         timeout=180, allow_redirects=True)
        if r.status_code == 200 and r.content[:2] == b"PK":
            z = zipfile.ZipFile(io.BytesIO(r.content))
            texts = []
            for n in z.namelist():
                if n.endswith(".txt"):
                    t = z.read(n).decode("utf-8", "replace")
                    texts.append(t)
                    for line in t.splitlines():
                        if "##[error]" in line and len(log_evidence["errors"]) < 20:
                            log_evidence["errors"].append(n + ": " + line.strip()[:220])
            alltext = "\n".join(texts)
            log_evidence["downloaded"] = True
            log_evidence["build_success"] = bool(re.search(r"BUILD SUCCESSFUL in ", alltext))
            log_evidence["mobile_task"] = "assembleMobileArm64_v8aRelease" in alltext
            print("日志: zip %d 字节 / %d 文本文件 / BUILD SUCCESSFUL=%s / mobile任务=%s" %
                  (len(r.content), len(texts), log_evidence["build_success"],
                   log_evidence["mobile_task"]), flush=True)
        else:
            print("日志: 未能下载 zip (HTTP %s, %d 字节)" % (r.status_code, len(r.content)), flush=True)
    except Exception as e:
        print("日志取证异常（不影响主结论）:", type(e).__name__, str(e)[:160], flush=True)

    # Release 与资产
    release = {}
    assets = []
    try:
        release = call("/repos/%s/releases/tags/%s" % (REPO, TAG))
        assets = [{"name": a["name"], "size": a["size"], "id": a["id"],
                   "browser_download_url": a["browser_download_url"]}
                  for a in release.get("assets", [])]
        print("Release: %s (draft=%s prerelease=%s) 资产 %d 个" %
              (release.get("tag_name"), release.get("draft"),
               release.get("prerelease"), len(assets)), flush=True)
    except urllib.error.HTTPError as e:
        print("Release 未找到或读取失败:", e.code, flush=True)
    for a in assets:
        print("   %-52s %12d B" % (a["name"], a["size"]), flush=True)

    # APK 资产可下载性（只取 302 头，不下载 50MB）
    downloads = []
    for a in assets:
        if not a["name"].endswith(".apk"):
            continue
        try:
            rr = requests.get(API + "/repos/%s/releases/assets/%d" % (REPO, a["id"]),
                              headers={"Authorization": "Bearer " + TOKEN,
                                       "User-Agent": "cb-hiker-mobile-ci",
                                       "Accept": "application/octet-stream"},
                              timeout=60, allow_redirects=False, stream=True)
            loc = rr.headers.get("Location", "")
            downloads.append({"name": a["name"], "status": rr.status_code,
                              "redirect_host": loc.split("/")[2] if "//" in loc else "",
                              "ok": rr.status_code in (302, 200)})
            print("   下载探测 %-46s HTTP %s -> %s" %
                  (a["name"], rr.status_code,
                   loc.split("/")[2] if "//" in loc else "(无重定向)"), flush=True)
        except Exception as e:
            downloads.append({"name": a["name"], "ok": False, "error": type(e).__name__})

    # 基线分支复核
    push = json.load(open(os.path.join(ROOT, "push_mobile_result.json"), encoding="utf-8"))
    before = push["baseline_heads_before"]
    after = {}
    for b in before:
        try:
            after[b] = call("/repos/%s/git/ref/heads/%s" % (REPO, b))["object"]["sha"]
        except urllib.error.HTTPError:
            after[b] = None
    baseline_untouched = (after == before)
    print("基线 4 分支未变动:", baseline_untouched, flush=True)

    ok = (conclusion == "success" and len(bad) == 0 and len(assets) >= 3
          and all(d["ok"] for d in downloads) and len(downloads) == 2
          and baseline_untouched)
    out = {
        "run_id": RUN_ID, "html_url": DISPATCH["html_url"], "head_sha": run.get("head_sha"),
        "tag": TAG, "status": run.get("status"), "conclusion": conclusion,
        "created_at": run.get("created_at"), "updated_at": run.get("updated_at"),
        "total_steps": len(steps),
        "success_steps": sum(1 for s in steps if s["conclusion"] == "success"),
        "skipped_steps": sum(1 for s in steps if s["conclusion"] == "skipped"),
        "non_success_steps": bad, "all_steps": steps,
        "log_evidence": log_evidence,
        "release": {"tag_name": release.get("tag_name"), "draft": release.get("draft"),
                    "prerelease": release.get("prerelease"), "html_url": release.get("html_url")},
        "assets": assets, "downloads": downloads,
        "baseline_heads_before": before, "baseline_heads_after": after,
        "baseline_untouched": baseline_untouched,
        "apk_variants": DISPATCH["inputs"]["apk_variants"],
        "all_checks_pass": ok,
    }
    with open(os.path.join(ROOT, "ci_mobile_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n===== 总结论: %s =====" % ("全部通过 ✅" if ok else "存在未通过项 ❌"), flush=True)
    print("结果文件:", os.path.join(ROOT, "ci_mobile_result.json"), flush=True)
    sys.exit(0 if ok else 3)


if __name__ == "__main__":
    main()
