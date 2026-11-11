#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主题启动链硬判据（CI 真正执行的那个检查，不是躺在仓库里的文档）。

背景：HomeActivity -> activity_home.xml:22 -> BottomNavigationView.<init>
      -> Resources$NotFoundException: Resource ID #0x0
真因是运行时 theme 链被改动（Theme.Base parent 被换 / setTheme 到不完整链），
不是签名、下载、重签或甲壳虫。oktv-cat @ f7f9a031 是能启动的基线：它的
styles.xml / AndroidManifest / activity_home.xml 必须逐字节保持；主题只能以
parent="" 的纯 item overlay 经 Theme.applyStyle 叠加。

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

# 3) 四套预设必须 parent="" 纯 overlay，禁止 window/daynight item
for p in glob.glob(os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'aurora_themes.xml')):
    root = ET.parse(p).getroot()
    for s in root.iter('style'):
        name = s.get('name', '')
        if not name.startswith('Theme.App.'):
            continue
        if s.get('parent') not in ('', None):
            errors.append('preset must be pure overlay (parent=""): %s parent=%s' % (name, s.get('parent')))
        for i in s.iter('item'):
            n = i.get('name', '')
            if n.startswith('android:window') or n == 'isLightTheme':
                errors.append('preset %s contains forbidden window/daynight item: %s' % (name, n))

# 4) 主题引擎禁止 setTheme（唯一合法入口是 Theme.applyStyle）
for p in glob.glob(os.path.join(ROOT, 'app', 'src', 'main', 'java', '**', 'AuroraTheme.java'), recursive=True):
    txt = open(p, encoding='utf-8').read()
    if re.search(r'\bsetTheme\s*\(', txt):
        errors.append('AuroraTheme calls setTheme(): ' + p)

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

# 6) 编译后 resources.arsc 诊断（可选；硬判据由 1~5 承担）
if len(sys.argv) >= 3:
    apk, aapt2 = sys.argv[1], sys.argv[2]
    if os.path.isfile(apk) and aapt2 and os.path.isfile(aapt2):
        try:
            r = subprocess.run([aapt2, 'dump', 'resources', '--values', apk],
                               capture_output=True, text=True, timeout=240)
            out = r.stdout or ''
            print('[aapt2] %s dump_lines=%d bytes=%d rc=%d'
                  % (os.path.basename(apk), len(out.splitlines()), len(out), r.returncode))
            key = ['colorPrimary', 'colorOnPrimary', 'colorSurface', 'colorOnSurface',
                   'colorSurfaceContainer', 'colorOutline', 'bottomNavigationStyle']
            for a in key:
                print('[aapt2]   attr/%s present=%s' % (a, ('attr/' + a) in out))
            # 明确出现空值才拦；格式不符只诊断，不误杀
            bad = [l.strip() for l in out.splitlines()
                   if re.search(r'@(?:null|0x0|empty)\b', l) and ('style' in l.lower() or 'item' in l.lower())]
            for l in bad[:10]:
                errors.append('compiled theme item resolved empty: ' + l)
        except Exception as e:
            print('[aapt2] diagnostic skipped: %s' % e)
    else:
        print('[aapt2] diagnostic skipped (apk or aapt2 not found): %s %s' % (apk, aapt2))

if errors:
    print('THEME STARTUP-CHAIN CHECK FAILED (%d):' % len(errors))
    for e in errors:
        print('  -', e)
    sys.exit(1)
print('THEME STARTUP-CHAIN CHECK PASSED: %d baseline files identical to oktv-cat, '
      '%d aurora attrs, %d refs' % (len(BASELINE), len(attrs), len(refs)))
