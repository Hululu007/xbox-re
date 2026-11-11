#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
通过 GitHub Git Data API 新建分支并原子提交（不动 oktv-cat）。
blob -> tree(base_tree) -> commit -> create ref
"""
import os, sys, json, base64, urllib.request, urllib.error

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = "kuby007/xbox-re"
BASE_BRANCH = "oktv-cat"
NEW_BRANCH = os.environ.get("NEW_BRANCH", "oktv-cat-hiker-entry")
ROOT = os.path.dirname(os.path.abspath(__file__))
PREFIX = "hiker-entry/"          # 交付文档/验证脚本放这里（与上一轮 koul-onestop 并列）

if not TOKEN:
    print("缺 GH_TOKEN"); sys.exit(1)


def target_path(rel):
    """本地相对路径 -> 仓库内路径。

    源码必须落到工程真实路径，否则 checkout 后无法 gradle 构建：
      app/src/**            -> app/src/**                    （覆盖/新增，参与构建）
      app/assets/hiker/**   -> app/src/main/assets/hiker/**  （assets 真实位置）
    其余（报告/脚本/补丁/快照）归入 hiker-entry/ 交付包，不干扰工程。
    """
    if rel.startswith("app/src/"):
        return rel
    if rel.startswith("app/assets/hiker/"):
        return "app/src/main/assets/hiker/" + rel[len("app/assets/hiker/"):]
    return PREFIX + rel

API = "https://api.github.com"


def call(method, path, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
        headers={"Authorization": "Bearer " + TOKEN, "User-Agent": "cb-hiker-entry",
                 "Accept": "application/vnd.github+json",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        print("HTTP %s %s\n%s" % (e.code, path, e.read().decode("utf-8")[:600]))
        raise


def is_text(p):
    return p.endswith((".java", ".xml", ".js", ".html", ".md", ".json", ".sh",
                       ".diff", ".txt", ".py", ".css"))


def main():
    print("认证身份:", call("GET", "/user").get("login"))
    base = call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, BASE_BRANCH))
    base_sha = base["object"]["sha"]
    base_commit = call("GET", "/repos/%s/git/commits/%s" % (REPO, base_sha))
    base_tree = base_commit["tree"]["sha"]
    print("基线分支 %s @ %s  tree=%s" % (BASE_BRANCH, base_sha[:12], base_tree[:12]))

    # 检查目标分支是否已存在
    try:
        call("GET", "/repos/%s/git/ref/heads/%s" % (REPO, NEW_BRANCH))
        print("!! 分支已存在，换一个 NEW_BRANCH"); sys.exit(2)
    except urllib.error.HTTPError:
        pass

    # 收集要提交的文件
    files = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in
                       ("__pycache__", ".git", "build_verify", "node_modules")]
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, ROOT).replace(os.sep, "/")
            # 排除临时/中间产物、基线副本、大体积二进制
            if rel.startswith(("app/patch/base/", "build_verify/", "interfaces/")):
                continue
            if rel in ("push_result.json",):   # 运行时生成，不入库
                continue
            if rel.endswith((".pyc", ".class", ".dex", ".zip")):
                continue
            if rel.endswith(".png") and rel not in (
                    "hiker_compat_poc.png", "hiker_entry_poc.png"):
                continue
            if os.path.getsize(full) > 2_000_000:
                continue
            files[target_path(rel)] = full

    print("待提交文件: %d 个" % len(files))
    src_cnt = sum(1 for p in files if p.startswith("app/src/"))
    print("  其中工程真实路径 app/src/ 下: %d 个" % src_cnt)
    for p in sorted(files):
        print("  +", p)

    # 1) blobs
    tree_items = []
    for i, (path, full) in enumerate(sorted(files.items()), 1):
        raw = open(full, "rb").read()
        blob = call("POST", "/repos/%s/git/blobs" % REPO,
                    {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"})
        tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
        if i % 10 == 0: print("  blob %d/%d" % (i, len(files)))

    # 2) tree with base_tree（保留原有全部文件，只叠加新增）
    tree = call("POST", "/repos/%s/git/trees" % REPO,
                {"base_tree": base_tree, "tree": tree_items})
    print("新 tree:", tree["sha"][:12])

    # 3) commit
    msg = ("feat(hiker): 首页横按钮新增独立入口，先做接口兼容性分析再落实构建\n\n"
           "- 构建前置：基于五类历史桥接口代码的兼容性实证（A/B/C/D1/D2/E）\n"
           "- 独立模块：HikerActivity + 独立 WebView + 同步字符串 Java 桥\n"
           "- 兼容层：桥探测/语义归一/作用域隔离/多变形态降级（Rhino/Python/加密）\n"
           "- 六段链路：入口→口令→数据→生成站点→运行→桥接播放\n"
           "- 首页仅加 1 个按钮 + 1 个跳转，不改任何现有逻辑（只增不删）\n"
           "- 沙箱验证：ecj 真编译 0 错误 + dex；44/44 e2e；40/40 静态校验")
    commit = call("POST", "/repos/%s/git/commits" % REPO,
                  {"message": msg, "tree": tree["sha"], "parents": [base_sha]})
    print("新 commit:", commit["sha"][:12])

    # 4) create ref
    ref = call("POST", "/repos/%s/git/refs" % REPO,
               {"ref": "refs/heads/" + NEW_BRANCH, "sha": commit["sha"]})
    print("新分支 %s -> %s" % (NEW_BRANCH, ref["object"]["sha"][:12]))

    out = {"branch": NEW_BRANCH, "base_branch": BASE_BRANCH, "base_sha": base_sha,
           "commit": commit["sha"], "tree": tree["sha"], "files": len(files),
           "file_list": sorted(files), "url": "https://github.com/%s/tree/%s" % (REPO, NEW_BRANCH)}
    with open(os.path.join(ROOT, "push_result.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n完成: %s" % out["url"])


if __name__ == "__main__":
    main()
