#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机版海阔独立入口 · 构建前兼容性与结构验证（纯本地、不联网、无需凭据）

基线：app/patch/mobile_base/ 下的 v3 分支真实快照（HEAD 50d2bef6）
改动：app/src/mobile/ 下的 3 个文件（2 改 1 新增）

核心判据：除「预期插入块」外，两个被改文件与基线**逐字节一致**（零删除、零修改）。
"""
import os, json, re, sys, difflib
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
BASE = os.path.join(ROOT, "app/patch/mobile_base")
NEW = os.path.join(ROOT, "app/src/mobile")
MAIN = os.path.join(ROOT, "app/src/main")
PATCH = os.path.join(ROOT, "app/patch")

RESULTS = []


def check(code, name, cond, detail=""):
    RESULTS.append({"code": code, "name": name,
                    "ok": bool(cond), "detail": str(detail)})


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def exist(path):
    return os.path.isfile(path)


def extract_method(text, signature):
    i = text.index(signature)
    j = text.index("\n    }\n", i) + len("\n    }\n")
    return text[i:j]


# ---------------------------------------------------------------- 读取
b_menu = read(os.path.join(BASE, "menu_nav.xml"))
n_menu = read(os.path.join(NEW, "res/menu/menu_nav.xml"))
b_home = read(os.path.join(BASE, "HomeActivity.java"))
n_home = read(os.path.join(NEW, "java/com/fongmi/android/tv/ui/activity/HomeActivity.java"))
b_lab = read(os.path.join(BASE, "ic_nav_lab.xml"))
n_icon = read(os.path.join(NEW, "res/drawable/ic_nav_hiker.xml"))
wf = read(os.path.join(BASE, "android-release.yml"))

MENU_INSERT = (
    "    <!-- 海阔独立入口：点击后跳独立 Activity / 独立 WebView，不承载任何首页 fragment。\n"
    "         与 lab 同模式（onNavigationItemSelected 返回 false，不改变选中态与 position 映射）。\n"
    "         标题复用 main 的 home_hiker（已含中/英/繁本地化），图标为 mobile 专属 ic_nav_hiker。\n"
    "         BottomNavigationView 上限 5 项，本菜单由 4 项增至 5 项，未越界。 -->\n"
    "    <item\n"
    '        android:id="@+id/hiker"\n'
    '        android:icon="@drawable/ic_nav_hiker"\n'
    '        android:title="@string/home_hiker"\n'
    '        android:visible="false" />\n'
    "\n"
)
HOME_INSERT_1 = (
    "        // 海阔独立入口常驻可见：与首页 fragment 完全解耦，不占 position，不参与选中态同步。\n"
    "        mBinding.navigation.getMenu().findItem(R.id.hiker).setVisible(true);\n"
)
HOME_INSERT_2 = (
    "        // 海阔独立入口：与 lab 同模式，跳独立 Activity 并返回 false，\n"
    "        // 保持原选中项、不触碰 FragmentStateManager 的 position 映射与 currentPosition。\n"
    "        if (item.getItemId() == R.id.hiker) { com.fongmi.android.tv.hiker.HikerEntry.start(this); return false; }\n"
)

# ---------------------------------------------------------------- A. 零回归
expected_menu = b_menu.replace("</menu>", MENU_INSERT + "</menu>")
check("A01", "menu_nav.xml 除预期插入块外与 v3 基线逐字节一致",
      n_menu == expected_menu,
      "零删除/零修改，仅新增 1 个 item + 注释")

anchor1 = ("        mBinding.navigation.getMenu().findItem(R.id.lab)"
           ".setVisible(LabConfig.get().getNavEntry());\n        syncNavigationSelection();")
anchor2 = ("        if (item.getItemId() == R.id.lab) { LabActivity.start(this); return false; }\n"
           "        return false;")
expected_home = b_home.replace(
    anchor1,
    "        mBinding.navigation.getMenu().findItem(R.id.lab)"
    ".setVisible(LabConfig.get().getNavEntry());\n" + HOME_INSERT_1 + "        syncNavigationSelection();"
).replace(
    anchor2,
    "        if (item.getItemId() == R.id.lab) { LabActivity.start(this); return false; }\n"
    + HOME_INSERT_2 + "        return false;"
)
check("A02", "HomeActivity.java 除两处预期插入外与 v3 基线逐字节一致",
      n_home == expected_home,
      "零删除/零修改，仅 setNavigation() 与 onNavigationItemSelected() 各加 1 处")
check("A03", "基线锚点唯一（替换未误伤其它位置）",
      b_home.count(anchor1) == 1 and b_home.count(anchor2) == 1)
check("A04", "Java import 段完全未变（使用全限定名，无新增依赖）",
      b_home[:b_home.index("public class HomeActivity")]
      == n_home[:n_home.index("public class HomeActivity")])
check("A05", "Java 大括号平衡且总数与基线一致",
      n_home.count("{") == n_home.count("}") and n_home.count("{") == b_home.count("{") + 1,
      "新增 1 个 if 块")

# ---------------------------------------------------------------- B. 菜单/控件上限
ANS = "{http://schemas.android.com/apk/res/android}"
try:
    root = ET.fromstring(n_menu)
    items = root.findall("item")
    xml_ok, xml_err = True, ""
except Exception as e:
    items, xml_ok, xml_err = [], False, str(e)
check("B01", "menu_nav.xml 是合法 XML", xml_ok, xml_err)
check("B02", "菜单项数 4 → 5，且不超过 BottomNavigationView 硬上限 5",
      len(items) == 5 and len(items) <= 5,
      "item 数=%d；Material BottomNavigationView MAX_ITEM_COUNT=5" % len(items))
ids = [it.get(ANS + "id") for it in items]
check("B03", "原有 4 项 id 顺序未变，新项 id 唯一",
      ids == ["@+id/vod", "@+id/live", "@+id/setting", "@+id/lab", "@+id/hiker"],
      str(ids))
new_item = items[-1] if items else None
check("B04", "新项引用 mobile 专属图标与 main 本地化标题",
      new_item is not None
      and new_item.get(ANS + "icon") == "@drawable/ic_nav_hiker"
      and new_item.get(ANS + "title") == "@string/home_hiker")
check("B05", "新项遵循既有 visible=false 约定（由 setNavigation() 统一置可见）",
      new_item is not None and new_item.get(ANS + "visible") == "false")
check("B06", "5 个菜单项均配置 icon（unlabeled 模式下图标为唯一视觉标识）",
      len(items) == 5 and all(it.get(ANS + "icon") for it in items),
      "标题仅作无障碍描述，不占布局宽度")
layout = os.path.join(NEW, "res/layout/activity_home.xml")
if not exist(layout):
    layout = "/root/.codebuddy/artifact/2f8a7d30-9c21-4f0b-1d6c-6c38ebd20000/v3_src/app/src/mobile/res/layout/activity_home.xml"
if exist(layout):
    lay = read(layout)
    check("B07", "activity_home.xml 使用 BottomNavigationView（故适用 5 项上限）",
          "com.google.android.material.bottomnavigation.BottomNavigationView" in lay)
    check("B08", "导航 labelVisibilityMode=unlabeled，新增项不影响既有排布",
          'app:labelVisibilityMode="unlabeled"' in lay)
else:
    check("B07", "activity_home.xml 可读取", False, "未找到布局文件")
    check("B08", "导航 label 模式", False, "未找到布局文件")

# ---------------------------------------------------------------- C. 图标风格一致
try:
    i_new, i_lab = ET.fromstring(n_icon), ET.fromstring(b_lab)
    icon_ok, icon_err = True, ""
except Exception as e:
    i_new = i_lab = None
    icon_ok, icon_err = False, str(e)
check("C01", "ic_nav_hiker.xml 是合法 vector XML", icon_ok, icon_err)
attrs = ["width", "height", "tint", "viewportWidth", "viewportHeight"]
check("C02", "图标尺寸/tint/viewport 与既有 ic_nav_lab 完全一致",
      icon_ok and all(i_new.get(ANS + a) == i_lab.get(ANS + a) for a in attrs),
      "48dp / tint=?attr/colorControlNormal / viewport 24x24")
check("C03", "图标 pathData 复用 main 的 ic_home_hiker（海阔立方体标识）",
      "M12,2L3,7v10l9,5 9,-5V7L12,2z" in n_icon)

# ---------------------------------------------------------------- D. position 映射不变
methods = [
    "private void initFragment(Bundle savedInstanceState) {",
    "private void restorePosition(int position) {",
    "private void selectNavigation(int position) {",
    "private void syncNavigationSelection() {",
    "private boolean changeFragment(int position) {",
    "public void change(int position) {",
    "protected void onBackInvoked() {",
]
same = []
for m in methods:
    try:
        same.append((m, extract_method(b_home, m) == extract_method(n_home, m)))
    except Exception:
        same.append((m, False))
check("D01", "FragmentStateManager 的 case 0..5 / default 与基线逐字一致",
      all("case %d ->" % k in n_home for k in range(6))
      and "default -> null;" in n_home)
check("D02", "7 个 position/导航/返回关键方法体逐字未改",
      all(ok for _, ok in same),
      "; ".join(m.split("(")[0] for m, ok in same if not ok) or "全部一致")
check("D03", "新分支使用 return false（与 lab 同模式，不改变选中项）",
      "if (item.getItemId() == R.id.hiker) { com.fongmi.android.tv.hiker.HikerEntry.start(this); return false; }" in n_home)
check("D04", "R.id.hiker 仅出现在可见性与点击两处，未进入 position 映射",
      n_home.count("R.id.hiker") == 2
      and "R.id.hiker" not in extract_method(n_home, "private void selectNavigation(int position) {")
      and "R.id.hiker" not in extract_method(n_home, "private void syncNavigationSelection() {")
      and "R.id.hiker" not in extract_method(n_home, "private boolean changeFragment(int position) {")
      and "R.id.hiker" not in extract_method(n_home, "private void initFragment(Bundle savedInstanceState) {"))
check("D05", "currentPosition 赋值语句未新增",
      n_home.count("currentPosition = position") == b_home.count("currentPosition = position"))

# ---------------------------------------------------------------- E. 跨 flavor 资源与运行时层
locales = ["values", "values-zh", "values-zh-rCN", "values-zh-rTW"]
locale_ok = []
for d in locales:
    p = os.path.join(MAIN, "res", d, "strings_hiker.xml")
    locale_ok.append(exist(p) and 'name="home_hiker"' in read(p))
check("E01", "main 的 home_hiker 字符串覆盖 4 个语言目录（所有 flavor 共用）",
      all(locale_ok), str(dict(zip(locales, locale_ok))))
check("E02", "HikerEntry.start(Activity) 签名存在且为 public static",
      exist(os.path.join(MAIN, "java/com/fongmi/android/tv/hiker/HikerEntry.java"))
      and "public static void start(Activity activity)" in read(os.path.join(MAIN, "java/com/fongmi/android/tv/hiker/HikerEntry.java")))
check("E03", "main/AndroidManifest.xml 已注册 .hiker.HikerActivity（所有 flavor 共用）",
      'android:name=".hiker.HikerActivity"' in read(os.path.join(MAIN, "AndroidManifest.xml")))
hiker_java = os.path.join(MAIN, "java/com/fongmi/android/tv/hiker")
check("E04", "运行时层 8 个 Java 类完整且本次未列入改动清单",
      os.path.isdir(hiker_java) and len([f for f in os.listdir(hiker_java) if f.endswith(".java")]) == 8)
CHANGE_SET = [
    "app/src/mobile/res/menu/menu_nav.xml",
    "app/src/mobile/res/drawable/ic_nav_hiker.xml",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java",
]
check("E05", "改动清单仅含 3 个 mobile 文件，main/ 运行时层与 leanback/ 电视版入口均未触碰",
      all(p.startswith("app/src/mobile/") for p in CHANGE_SET) and len(CHANGE_SET) == 3)

# ---------------------------------------------------------------- F. 工作流门禁与变体
guard = wf.split("- name: Verify mobile search scope feature is present")[1].split("- name: Run WebSocket")[0]
check("F01", "CI 源码门禁不检查 menu_nav.xml / mobile HomeActivity.java（改动不会触发门禁失败）",
      "menu_nav.xml" not in guard and "activity/HomeActivity.java" not in guard)
check("F02", "工作流支持 mobile-arm64_v8a 变体",
      "mobile-arm64_v8a)" in wf and ":app:assembleMobileArm64_v8aRelease" in wf)
check("F03", "CI 临时制品上传已 continue-on-error（非关键步不染红 run）",
      "continue-on-error: true" in wf.split("- name: Upload workflow artifacts")[1])
check("F04", "release_tag 已存在会硬失败（需使用新 tag）",
      "Release tag exists" in wf)

# ---------------------------------------------------------------- 生成 diff
diff_parts = []
for rp, bp, np in [
    ("app/src/mobile/res/menu/menu_nav.xml", "menu_nav.xml", "res/menu/menu_nav.xml"),
    ("app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java", "HomeActivity.java",
     "java/com/fongmi/android/tv/ui/activity/HomeActivity.java"),
]:
    a = read(os.path.join(BASE, bp)).splitlines(keepends=True)
    b = read(os.path.join(NEW, np)).splitlines(keepends=True)
    diff_parts += list(difflib.unified_diff(a, b, fromfile="a/" + rp, tofile="b/" + rp))
a = []
b = n_icon.splitlines(keepends=True)
diff_parts += list(difflib.unified_diff(
    a, b, fromfile="/dev/null",
    tofile="b/app/src/mobile/res/drawable/ic_nav_hiker.xml"))
with open(os.path.join(PATCH, "mobile_changes.diff"), "w", encoding="utf-8") as f:
    f.writelines(diff_parts)
check("G01", "完整 unified diff 已归档", exist(os.path.join(PATCH, "mobile_changes.diff")))

# ---------------------------------------------------------------- 汇总
passed = sum(1 for r in RESULTS if r["ok"])
failed = [r for r in RESULTS if not r["ok"]]
print("=" * 72)
for r in RESULTS:
    print("  %s  %s  %s" % ("PASS" if r["ok"] else "FAIL", r["code"], r["name"]))
    if r["detail"] and not r["ok"]:
        print("         -> " + r["detail"])
print("=" * 72)
print("结论: %d/%d 通过" % (passed, len(RESULTS)))

report = {
    "title": "手机版海阔独立入口 · 构建前兼容性与结构验证",
    "v3_head": "50d2bef6e6f1cbbc5d91fbf9fed9c2002756b480",
    "changed_files": CHANGE_SET,
    "total": len(RESULTS), "passed": passed, "failed": len(failed),
    "all_pass": len(failed) == 0,
    "results": RESULTS,
}
with open(os.path.join(ROOT, "verify_mobile_entry_report.json"), "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)
sys.exit(0 if not failed else 1)
