#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对 oktv-cat 的现有文件做「精确最小插入」，生成可提交的目标文件 + unified diff 审计。

原则：每一处替换都要求 old 在原文件中恰好出现 1 次，否则中止（防止误改/重复插入）。
所有改动都是「加一行 / 加一段」，不删除、不重排任何原有代码，符合「不与首页混用」。
"""
import os, sys, difflib, json

HERE = os.path.dirname(os.path.abspath(__file__))
PKG = os.path.dirname(HERE)                       # /workspace/hiker-entry/app
BASE = os.path.join(PKG, "..", "interfaces")     # 远端基线快照目录
OUT = os.path.join(PKG, "src")                   # 目标源码根

# 需要真实读取的基线（有些文件不在 interfaces/ 快照里，单独下载到 patch/base/）
BASEDIR = os.path.join(HERE, "base")
os.makedirs(BASEDIR, exist_ok=True)

EDITS = []


def add(path, base_file, old, new, note):
    EDITS.append(dict(path=path, base=base_file, old=old, new=new, note=note))


# ---------------------------------------------------------------- ① Func.java（已单独写，用 diff 复核）
add("leanback/java/com/fongmi/android/tv/bean/Func.java", "Func.java",
    '        else if (resId == R.string.home_setting) this.drawable = R.drawable.ic_home_setting;\n',
    '        else if (resId == R.string.home_setting) this.drawable = R.drawable.ic_home_setting;\n'
    '        // 新增：海阔独立入口（阶段六）。与首页其它按钮同一体系，仅多一个 drawable 分支。\n'
    '        else if (resId == R.string.home_hiker) this.drawable = R.drawable.ic_home_hiker;\n',
    "setDrawable 追加 home_hiker 分支，drawable=0 图标空白的兼容性坑")

# ---------------------------------------------------------------- ② HomeActivity.java（leanback）
add("leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java", "HomeActivity.java",
    '        items.add(Func.create(R.string.home_setting));\n'
    '        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());\n',
    '        items.add(Func.create(R.string.home_setting));\n'
    '        items.add(Func.create(R.string.home_hiker)); // 新增：海阔独立入口（阶段六）\n'
    '        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());\n',
    "setFunc 在横排功能按钮末尾追加一个入口")
add("leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java", "HomeActivity.java",
    '        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);\n',
    '        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);\n'
    '        else if (item.getResId() == R.string.home_hiker) com.fongmi.android.tv.hiker.HikerEntry.start(this);\n',
    "onItemClick 追加跳转（全限定名，避免改 import 区）")

# ---------------------------------------------------------------- ③ HomeButton.java（设置里可显隐排序的按钮组）
add("leanback/java/com/fongmi/android/tv/bean/HomeButton.java", "HomeButton.java",
    '    private static final String ALL = "0,1,2,3,8,4,5,6,7,9";\n',
    '    private static final String ALL = "0,1,2,3,8,4,5,6,7,9,10";\n',
    "排序全集追加 id=10；sortedAll()/insertMissing 会自动补齐旧用户偏好串，向后兼容")
add("leanback/java/com/fongmi/android/tv/bean/HomeButton.java", "HomeButton.java",
    '        buttons.add(new HomeButton(9, R.string.home_custom_csp));\n',
    '        buttons.add(new HomeButton(9, R.string.home_custom_csp));\n'
    '        buttons.add(new HomeButton(10, R.string.home_hiker));\n',
    "all() 追加第 11 个按钮")
add("leanback/java/com/fongmi/android/tv/bean/HomeButton.java", "HomeButton.java",
    '        ids.add("7");\n        ids.add("9");\n',
    '        ids.add("7");\n        ids.add("9");\n        ids.add("10");\n',
    "getDefaultButtons 追加，保证新用户默认可见")

# ---------------------------------------------------------------- ④ 字符串资源（新增独立文件，不改现有 strings.xml）
def new_file(path, content, note):
    EDITS.append(dict(path=path, new_file=content, note=note))

STR_EN = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 阶段六：海阔独立入口（新增文件，避免改动现有 strings.xml，多语言按资源回退） -->
    <string name="home_hiker">Hiker</string>
    <string name="hiker_entry_title">Hiker Module</string>
</resources>
'''
STR_ZH = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 阶段六：海阔独立入口 -->
    <string name="home_hiker">海阔</string>
    <string name="hiker_entry_title">海阔 · 独立模块</string>
</resources>
'''
STR_ZHTW = '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 階段六：海闊獨立入口 -->
    <string name="home_hiker">海闊</string>
    <string name="hiker_entry_title">海闊 · 獨立模組</string>
</resources>
'''
new_file("main/res/values/strings_hiker.xml", STR_EN, "英文基准（所有 flavor 共用 main，资源回退兜底）")
new_file("main/res/values-zh-rCN/strings_hiker.xml", STR_ZH, "简体中文")
new_file("main/res/values-zh-rTW/strings_hiker.xml", STR_ZHTW, "繁体中文")
new_file("main/res/values-zh/strings_hiker.xml", STR_ZH, "中文 fallback")

# ---------------------------------------------------------------- ⑤ 图标（vector，全密度通用，避免 3 套 png）
ICON = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="26dp"
    android:height="26dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,2L3,7v10l9,5 9,-5V7L12,2zM12,4.3l6.4,3.6L12,11.4 5.6,7.9 12,4.3zM5,9.6l6,3.3v6.5L5,16.1V9.6zM13,19.4v-6.5l6,-3.3v6.5L13,19.4z" />
</vector>
'''
new_file("main/res/drawable/ic_home_hiker.xml", ICON, "海阔入口图标（立方体，与现有 ic_home_* 风格一致）")

# ---------------------------------------------------------------- ⑥ Manifest（main，两个 flavor 共用）
add("main/AndroidManifest.xml", "AndroidManifest_main.xml",
    '        <activity\n            android:name=".ui.activity.CrashActivity"',
    '        <!-- 阶段六：海阔独立入口。独立 WebView / 独立同步桥，不复用首页 webOverlay。 -->\n'
    '        <activity\n'
    '            android:name=".hiker.HikerActivity"\n'
    '            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"\n'
    '            android:exported="false"\n'
    '            android:screenOrientation="sensorLandscape" />\n\n'
    '        <activity\n            android:name=".ui.activity.CrashActivity"',
    "注册 HikerActivity（放在 main，leanback/mobile 两个 flavor 都生效）")


def main():
    report = {"edits": [], "diffs": {}, "ok": True}
    for e in EDITS:
        path = e["path"]
        dest = os.path.join(OUT, path)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if "new_file" in e:
            if os.path.exists(dest) and open(dest, encoding="utf-8").read() == e["new_file"]:
                pass
            else:
                open(dest, "w", encoding="utf-8").write(e["new_file"])
            report["edits"].append({"path": path, "type": "new-file", "note": e["note"]})
            print("[新增] %-62s %s" % (path, e["note"]))
            continue

        bf = os.path.join(BASEDIR, e["base"])
        if not os.path.exists(bf):
            bf2 = os.path.join(BASE, e["base"])
            if os.path.exists(bf2):
                bf = bf2
            else:
                print("!! 缺基线文件: %s" % e["base"]); report["ok"] = False; continue
        src = open(bf, encoding="utf-8").read()
        # 若之前已跑过一次，dest 已含改动 → 从 dest 读，但要保证 old 只出现 0/1 次
        cur = open(dest, encoding="utf-8").read() if os.path.exists(dest) else src
        cnt = cur.count(e["old"])
        if cnt == 1:
            cur = cur.replace(e["old"], e["new"], 1)
        elif cnt == 0 and e["new"] in cur:
            pass  # 已插入过，幂等
        else:
            print("!! 锚点命中 %d 次，中止: %s / %s" % (cnt, e["base"], e["note"]))
            report["ok"] = False; continue
        open(dest, "w", encoding="utf-8").write(cur)
        d = "\n".join(difflib.unified_diff(src.splitlines(), cur.splitlines(),
                                           "a/" + path, "b/" + path, lineterm=""))
        report["diffs"].setdefault(path, []).append(d)
        report["edits"].append({"path": path, "type": "insert", "note": e["note"]})
        print("[插入] %-62s %s" % (path, e["note"]))

    # 输出统一 diff
    with open(os.path.join(HERE, "app_changes.diff"), "w", encoding="utf-8") as f:
        for path, ds in report["diffs"].items():
            for d in ds:
                f.write(d + "\n\n")
    # 新增文件清单
    with open(os.path.join(HERE, "new_files.txt"), "w", encoding="utf-8") as f:
        for e in EDITS:
            if "new_file" in e: f.write(e["path"] + "\n")
    del report["diffs"]
    with open(os.path.join(HERE, "patch_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print("\n结果: %s  改动 %d 处  diff -> app_changes.diff" %
          ("OK" if report["ok"] else "有失败", len(report["edits"])))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
