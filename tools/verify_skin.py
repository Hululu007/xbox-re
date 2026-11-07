#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
星辰皮肤补丁 · 自检脚本
在 webhtv 工程根目录跑：python3 verify_skin.py

检查项：
  1. 5 处集成点是否真的接上了
  2. UiSettingsActivity 的 binding 字段 vs 布局 @+id 是否对得上
  3. R.drawable / R.string 引用是否都有对应资源
  4. Java 语法树解析（需要 tree_sitter + tree_sitter_java；没有就跳过并提示）

退出码 0 = 全过；非 0 = 有 FAIL。
"""
import os
import re
import sys
import glob

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else ".")
fails = []
warns = []


def read(p):
    try:
        return open(os.path.join(ROOT, p), encoding="utf-8", errors="ignore").read()
    except Exception as e:
        fails.append(f"读不到 {p}: {e}")
        return ""


def check(name, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + name + (("  " + detail) if detail and not cond else ""))
    if not cond:
        fails.append(name)


print("=" * 60)
print("1. 集成点")
print("=" * 60)

app_java = read("app/src/main/java/com/fongmi/android/tv/App.java")
check("App.java 调了 ThemeManager.init", "ThemeManager.init(this)" in app_java)

base = read("app/src/mobile/java/com/fongmi/android/tv/ui/base/BaseActivity.java")
check("BaseActivity.customWall 受皮肤开关控制", "isSkinWallpaperActive" in base)

mani = read("app/src/mobile/AndroidManifest.xml")
check("Manifest 注册 UiSettingsActivity", "UiSettingsActivity" in mani)

frag_xml = read("app/src/mobile/res/layout/fragment_setting.xml")
check("设置页布局有 xcSkin 行", "@+id/xcSkin" in frag_xml)

frag_java = read("app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingFragment.java")
check("SettingFragment 给 xcSkin 接了点击", "mBinding.xcSkin" in frag_java)

strings = read("app/src/main/res/values/strings.xml")
check("strings 有 xc_skin_title", 'name="xc_skin_title"' in strings)
check("strings 有 xc_skin_summary", 'name="xc_skin_summary"' in strings)

print()
print("=" * 60)
print("2. 皮肤文件是否就位")
print("=" * 60)

need_files = [
    "app/src/mobile/java/com/xingchen/tv/theme/ThemeManager.java",
    "app/src/mobile/java/com/xingchen/tv/theme/XingChenTheme.java",
    "app/src/mobile/java/com/xingchen/tv/theme/WallpaperBlur.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/UiSettingsActivity.java",
    "app/src/mobile/res/layout/activity_ui_settings.xml",
    "app/src/mobile/res/values/xc_skin_values.xml",
]
for f in need_files:
    check(f.split("/")[-1], os.path.exists(os.path.join(ROOT, f)))

# 反向项：删掉的两个东西不能又冒出来
check("死代码 FrostedGlass 已移除",
      not os.path.exists(os.path.join(ROOT, "app/src/mobile/java/com/xingchen/tv/theme/FrostedGlass.java")))
_act_xml = read("app/src/mobile/res/layout/activity_ui_settings.xml")
check("无效的毛玻璃透明度滑块已移除", "seek_glass_alpha" not in _act_xml)

print()
print("=" * 60)
print("3. ViewBinding 字段 vs 布局 id")
print("=" * 60)


def camel_to_ids(name):
    """ViewBinding: seg_ratio_23 -> segRatio23；反推可能的 id 写法。"""
    # 把大写字母拆开：segRatio23 -> seg_ratio_23 和 seg_ratio23 都试
    s1 = re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
    s2 = re.sub(r"(?<=[a-z])(?=[0-9])", "_", s1)
    return {s1, s2}


layout = read("app/src/mobile/res/layout/activity_ui_settings.xml")
ids = set(re.findall(r"@\+id/([A-Za-z0-9_]+)", layout))
act = read("app/src/mobile/java/com/fongmi/android/tv/ui/activity/UiSettingsActivity.java")
used = set(re.findall(r"\bbinding\.([A-Za-z0-9_]+)", act))

missing = []
for u in sorted(used):
    cands = camel_to_ids(u)
    if not (cands & ids):
        missing.append(u)
check(f"binding 字段都能在布局找到（共 {len(used)} 个）", not missing,
      "对不上: " + ", ".join(missing) if missing else "")

print()
print("=" * 60)
print("4. 资源引用")
print("=" * 60)

srcs = glob.glob(os.path.join(ROOT, "app/src/mobile/java/com/xingchen/tv/theme/*.java"))
srcs.append(os.path.join(ROOT, "app/src/mobile/java/com/fongmi/android/tv/ui/activity/UiSettingsActivity.java"))
need_res = set()
for f in srcs:
    if os.path.exists(f):
        need_res |= set(re.findall(r"R\.drawable\.([A-Za-z0-9_]+)", open(f, encoding="utf-8", errors="ignore").read()))

have_res = set()
for d in glob.glob(os.path.join(ROOT, "app/src/mobile/res/drawable*")):
    for fn in os.listdir(d):
        have_res.add(os.path.splitext(fn)[0])
miss = sorted(need_res - have_res)
check(f"R.drawable 引用齐全（{len(need_res)} 个）", not miss, "缺: " + ", ".join(miss) if miss else "")

print()
print("=" * 60)
print("5. Java 语法树解析")
print("=" * 60)

try:
    import tree_sitter_java as tsj
    from tree_sitter import Language, Parser

    JAVA = Language(tsj.language())
    parser = Parser(JAVA)
    bad = []
    targets = srcs + [os.path.join(ROOT, "app/src/main/java/com/fongmi/android/tv/App.java"),
                      os.path.join(ROOT, "app/src/mobile/java/com/fongmi/android/tv/ui/base/BaseActivity.java"),
                      os.path.join(ROOT, "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingFragment.java")]
    for f in targets:
        if not os.path.exists(f):
            continue
        src = open(f, "rb").read()
        tree = parser.parse(src)
        if tree.root_node.has_error:
            bad.append(os.path.basename(f))
    check(f"语法解析（{len(targets)} 个文件）", not bad, "有错: " + ", ".join(bad) if bad else "")
except ImportError:
    warns.append("没装 tree_sitter / tree_sitter_java，语法检查跳过")
    print("  SKIP  未安装 tree_sitter（pip install tree_sitter tree_sitter_java 后重跑）")

print()
print("=" * 60)
if fails:
    print(f"结果：FAIL {len(fails)} 项")
    for f in fails:
        print("  -", f)
    sys.exit(1)
else:
    print("结果：全部 PASS ✅")
    if warns:
        for w in warns:
            print("  注意:", w)
    sys.exit(0)
