#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主题启动链硬判据（CI 真正执行的那个检查，不是躺在仓库里的文档）。

背景：HomeActivity -> activity_home.xml:22 -> BottomNavigationView.<init>
      -> Resources$NotFoundException: Resource ID #0x0
真因是 BaseActivity 在 setContentView 之前用 Theme.applyStyle 叠加 parent="" 的裸
style，并把 framework android:textColor* 覆盖成字面色；inflate 期
BottomNavigationView 构造读到 resource id 0 -> Resources$NotFoundException #0x0。
oktv-cat @ f7f9a031 是能启动的基线：它的 styles.xml / AndroidManifest /
activity_home.xml 必须逐字节保持；主题只允许用 parent="Theme.App" 的完整链
经 Activity.setTheme 切换，并且切换后必须有探针兜底。

用法：
  python3 tools/theme_baseline_check.py                  # 源码级硬断言（构建前）
  python3 tools/theme_baseline_check.py <apk> <aapt2>   # 追加编译后 resources.arsc 诊断

硬判据 1/2 会 exit 1；aapt2 段只作诊断输出，避免依赖 dump 格式误杀正常包。
"""
import glob
import hashlib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# oktv-cat @ f7f9a031 实测 sha256。任何一项变动 = 启动关键路径被改，直接挡住。
BASELINE = {
    'app/src/main/res/values/styles.xml': 'bed4fce68d56756062610e484cce983415f8eef5a7d6b051cae7e1d17899464f',
    'app/src/mobile/res/values/styles.xml': '47600637887553ace2479c28ca9143955fa5861aa9b1f317c99e391ced17a16f',
    'app/src/leanback/res/values/styles.xml': '174ce0b82f1d31c79a6f9e829fc0f42e3c2dc9ca1613a9d659a5c186d76a1802',
    'app/src/main/AndroidManifest.xml': 'e8489e131d761b075880829669fbe3acf9927ba15f57f50711b53ac9de5b5531',
    'app/src/mobile/AndroidManifest.xml': '956817998fd32ce4de8c6c6a093b5323a53bf2a4c8fa86db626a30fe3daba449',
    'app/src/leanback/AndroidManifest.xml': '3989460d3746da9ea1e7aa52cb091b6d2c104aaffbc905bea3686b05638be686',
    'app/src/mobile/res/layout/activity_home.xml': '213858797a2bea38767d0173bde1dbe51b30f03396b53b17854d29cb91edb31f',
    'app/src/leanback/res/layout/activity_home.xml': '11ddb24d43e66c292082fbb051867abbcac8e223b2d99a168765bc839d9b8781',
}

errors = []


def sha256(path):
    with open(path, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()


# 1) 启动关键路径文件必须与可运行基线逐字节一致
for rel, exp in BASELINE.items():
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        errors.append('missing startup-chain file: ' + rel)
        continue
    got = sha256(p)
    if got != exp:
        errors.append('startup-chain file changed vs oktv-cat: %s (got %s expected %s)'
                      % (rel, got[:12], exp[:12]))

# 2) Theme.Base parent 不得被改动（mobile 必须仍是 DynamicColors 链）
for p in glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', 'values', 'styles.xml')):
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError as e:
        errors.append('XML parse %s: %s' % (p, e))
        continue
    for s in root.iter('style'):
        if s.get('name') == 'Theme.Base' and s.get('parent') and 'mobile' in p:
            if 'DynamicColors' not in s.get('parent'):
                errors.append('Theme.Base parent changed on mobile: %s -> %s' % (p, s.get('parent')))

# 3) 四套预设必须是 parent="Theme.App" 的完整链，禁止 framework 级覆盖
for p in glob.glob(os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'aurora_themes.xml')):
    root = ET.parse(p).getroot()
    found = 0
    for s in root.iter('style'):
        name = s.get('name', '')
        if not name.startswith('Theme.App.'):
            continue
        found += 1
        if s.get('parent') != 'Theme.App':
            errors.append('preset must inherit full chain (parent="Theme.App"): %s parent=%s' % (name, s.get('parent')))
        for i in s.iter('item'):
            n = i.get('name', '')
            if n.startswith('android:textColor') or n.startswith('android:window') or n == 'isLightTheme':
                errors.append('preset %s contains forbidden framework item: %s' % (name, n))
    if found != 4:
        errors.append('expected 4 theme presets, found %d' % found)

# 4) 主题引擎禁止 applyStyle（inflate 前叠加裸 style 是 #0x0 的根因）；
#    必须 setTheme + 探针兜底
for p in glob.glob(os.path.join(ROOT, 'app', 'src', 'main', 'java', '**', 'AuroraTheme.java'), recursive=True):
    txt = open(p, encoding='utf-8').read()
    if re.search(r'\bapplyStyle\s*\(', txt):
        errors.append('AuroraTheme calls Theme.applyStyle(): ' + p)
    if not re.search(r'\bsetTheme\s*\(', txt):
        errors.append('AuroraTheme must use Activity.setTheme(): ' + p)
    if 'themeUsable' not in txt:
        errors.append('AuroraTheme missing themeUsable() startup probe: ' + p)

# 5) ?attr/aurora* 必须全部声明
attrs, refs = set(), set()
for p in glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', 'values*', '*.xml')):
    txt = open(p, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'<attr name="(aurora\w+)"', txt):
        attrs.add(m.group(1))
for p in glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', '**', '*.xml'), recursive=True):
    txt = open(p, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'\?attr/(aurora\w+)', txt):
        refs.add(m.group(1))
for a in sorted(refs - attrs):
    errors.append('?attr/%s used but not declared' % a)

# 6) 编译后 resources.arsc 硬判据（源码级由 1~5 承担，此处拦的是「只有真机才暴露」的那类）。
#    aapt2 没有 --values 选项（只有 --no-values）：旧版传 --values 会让它把该串当文件名，
#    stdout 为空 -> dump_lines=0 -> 下面所有诊断形同虚设，CI 全绿仍会启动即崩。
if len(sys.argv) >= 3:
    apk, aapt2 = sys.argv[1], sys.argv[2]
    if not (os.path.isfile(apk) and aapt2 and os.path.isfile(aapt2)):
        errors.append('aapt2 diagnostic requested but apk/aapt2 missing: %s %s' % (apk, aapt2))
    else:
        try:
            r = subprocess.run([aapt2, 'dump', 'resources', apk],
                               capture_output=True, text=True, timeout=240)
            out = r.stdout or ''
            lines = out.splitlines()
            print('[aapt2] %s dump_lines=%d bytes=%d rc=%d'
                  % (os.path.basename(apk), len(lines), len(out), r.returncode))
            if len(lines) < 100:
                errors.append('aapt2 dump produced no usable output (bad aapt2 args or corrupt apk)')
            key = ['colorPrimary', 'colorOnPrimary', 'colorSurface', 'colorOnSurface',
                   'colorSurfaceContainer', 'colorOutline', 'bottomNavigationStyle']
            for a in key:
                print('[aapt2]   attr/%s present=%s' % (a, ('attr/' + a) in out))

            # 只扫四套预设的 style bag：framework attr（0x0101xxxx）若被赋成字面色
            # #AARRGGBB，Material 控件取 value.resourceId 会得到 0 -> #0x0 崩溃。
            # 引用形式（= @0x... / =?0x...）不算违规，不会误杀合法配置。
            in_bag = False
            preset_seen = set()
            for line in lines:
                m = re.search(r'style/Theme\.App\.(Aurora|Paper|Ink|Glass)\s*$', line)
                if m:
                    in_bag = True
                    preset_seen.add(m.group(1))
                    continue
                if in_bag and line.strip().startswith('resource 0x'):
                    in_bag = False
                    continue
                if in_bag and re.match(r'\s+0x0101[0-9a-fA-F]{4}=#', line):
                    errors.append('compiled preset writes framework color attr as literal '
                                  '(material widget would read resourceId=0): ' + line.strip())
                # 只拦「预设内」的空值。Material 库自带 style 里大量 @null 是合法的
                # （itemBackground / itemActiveIndicatorStyle / itemMinHeight 等），
                # 全局扫 @null 会把正常包全部误杀（AGENTS.md 第 139 条同款坑）。
                if in_bag and re.search(r'=\s*@(?:null|0x0|empty)\b', line):
                    errors.append('compiled theme preset item resolved empty: ' + line.strip())
            if len(preset_seen) != 4:
                errors.append('aapt2 dump missing theme presets: found %s' % sorted(preset_seen))
        except Exception as e:
            errors.append('aapt2 diagnostic failed: %s' % e)

if errors:
    print('THEME STARTUP-CHAIN CHECK FAILED (%d):' % len(errors))
    for e in errors:
        print('  -', e)
    sys.exit(1)
print('THEME STARTUP-CHAIN CHECK PASSED: %d baseline files identical to oktv-cat, '
      '%d aurora attrs, %d refs' % (len(BASELINE), len(attrs), len(refs)))
