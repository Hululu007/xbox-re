#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段七：通过 GitHub Git Data API 在 oktv-cat-hiker-entry 之上新建分支并原子提交。
不动 oktv-cat、不覆盖任何已有分支；只叠加/更新独立模块 assets 与交付文档。
blob -> tree(base_tree) -> commit -> create ref
"""
import os, sys, json, base64, urllib.request, urllib.error

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
BASE_BRANCH = "oktv-cat-hiker-entry"
NEW_BRANCH = os.environ.get("NEW_BRANCH", "oktv-cat-hiker-v2")
ROOT = os.path.dirname(os.path.abspath(__file__))
WS = os.path.dirname(ROOT)
API = "https://api.github.com"

if not TOKEN:
    print("缺 GH_TOKEN"); sys.exit(1)

# 仓库内路径 -> 本地路径（精确清单，不做目录扫描，避免误传中间产物）
PAIRS = [
    # ---- 工程真实路径：checkout 后直接参与 gradle 构建 ----
    ("app/src/main/assets/hiker/hiker_scheme.js",        ROOT + "/app/assets/hiker/hiker_scheme.js"),
    ("app/src/main/assets/hiker/hiker_api_surface.js",   ROOT + "/app/assets/hiker/hiker_api_surface.js"),
    ("app/src/main/assets/hiker/hiker_compat.js",        ROOT + "/app/assets/hiker/hiker_compat.js"),
    ("app/src/main/assets/hiker/hiker_bridge_adapter.js", ROOT + "/app/assets/hiker/hiker_bridge_adapter.js"),
    ("app/src/main/assets/hiker/index.html",             ROOT + "/app/assets/hiker/index.html"),
    # ---- 交付包 ----
    ("hiker-entry/verify_v2.py",                        ROOT + "/verify_v2.py"),
    ("hiker-entry/verify_v2_report.json",               ROOT + "/verify_v2_report.json"),
    ("hiker-entry/hiker_v2_poc.png",                    ROOT + "/hiker_v2_poc.png"),
    ("hiker-entry/hiker_official_contract.json",        ROOT + "/hiker_official_contract.json"),
    ("hiker-entry/hiker_official_hits.json",            ROOT + "/hiker_official_hits.json"),
    ("hiker-entry/api_gap.json",                        ROOT + "/api_gap.json"),
    ("hiker-entry/两项目对照_经验教训与超越方案.md",     ROOT + "/两项目对照_经验教训与超越方案.md"),
    ("hiker-entry/两项目对照_交付说明.md",               WS + "/两项目对照_交付说明.md"),
    ("hiker-entry/push_v2.py",                          ROOT + "/push_v2.py"),
]


def call(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
        headers={"Authorization": "Bearer " + TOKEN, "User-Agent": "cb-hiker-v2",
                 "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:600]))
        raise


def main():
    print("认证身份:", call("GET", "/user").get("login"))
    base = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BASE_BRANCH))
    base_sha = base["object"]["sha"]
    base_commit = call("GET", "/repos/%s/git/commits/%s" % (REPO, base_sha))
    base_tree = base_commit["tree"]["sha"]
    print("基线分支 %s @ %s  tree=%s" % (BASE_BRANCH, base_sha[:12], base_tree[:12]))

    try:
        call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, NEW_BRANCH))
        print("!! 分支已存在，换一个 NEW_BRANCH"); sys.exit(2)
    except urllib.error.HTTPError:
        pass

    # 校验本地文件齐全
    files = {}
    for remote, local in PAIRS:
        if not os.path.isfile(local):
            print("缺本地文件:", local); sys.exit(3)
        files[remote] = local
    print("待提交文件: %d 个（app/src/ 下 %d 个）" %
          (len(files), sum(1 for p in files if p.startswith("app/src/"))))
    for p in sorted(files):
        print("  + %-64s %7d B" % (p, os.path.getsize(files[p])))

    tree_items = []
    for i, (path, full) in enumerate(sorted(files.items()), 1):
        raw = open(full, "rb").read()
        blob = call("POST", "/repos/%s/git/blobs" % REPO,
                    {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        print("  blob %d/%d %s" % (i, len(files), path.split("/")[-1]))

    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": tree_items})
    print("新 tree:", tree["sha"][:12])

    msg = ("feat(hiker-v2): 对照 hikerView/xingchen 后按官方契约补齐并吸收真机教训\n\n"
           "- 官方契约基准：hikerView JSEngine 100 @JSAnnotation / help_js 160 / "
           "web_bridge 27，hiker_54.db 真实规则命中 134 个 API\n"
           "- 新增 hiker_api_surface.js：134/134 符号可见（93 真实现/25 桥转发/"
           "12 保留/4 降级）+ installFresh 新鲜作用域 + 规则级变量隔离 + normalizePlayData\n"
           "- 新增 hiker_scheme.js：hiker:// 10 类分发 + file:// + 404/500 语义 + "
           "clearTag 不砍真实地址（xingchen 1.2.8 全灭事故）\n"
           "- fy_bridge_app 8→28 方法；HikerCompat v1→v2（contract/coverage/scheme/runInFresh）\n"
           "- 实证：verify_v2.py 36/36；verify_entry 44/44；全库 31→32/78；"
           "ecj 0 错误；patch 42/42\n"
           "- 报告：三方支持度矩阵 / 经验 / 教训 / 超越点 / 未闭合边界")
    commit = call("POST", "/repos/%s/git/commits" % REPO,
                  {"message": msg, "tree": tree["sha"], "parents": [base_sha]})
    print("新 commit:", commit["sha"][:12])

    ref = call("POST", "/repos/%s/git/refs" % REPO,
               {"ref": "refs/heads/" + NEW_BRANCH, "sha": commit["sha"]})
    print("新分支 %s -> %s" % (NEW_BRANCH, ref["object"]["sha"][:12]))

    out = {"branch": NEW_BRANCH, "base_branch": BASE_BRANCH, "base_sha": base_sha,
           "commit": commit["sha"], "tree": tree["sha"], "files": len(files),
           "file_list": sorted(files),
           "url": "https://github.com/%s/tree/%s" % (REPO, NEW_BRANCH)}
    with open(os.path.join(ROOT, "push_v2_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n完成: %s" % out["url"])


if __name__ == "__main__":
    main()
