#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
主题启动链 + 配色可读性硬判据（CI 真正执行，不是躺在仓库里的文档）。

历史教训（三条，全部有真实崩溃栈 / diff 为证，禁止再犯）：
  1) HomeActivity -> activity_home.xml:22 -> BottomNavigationView.<init>
     -> Resources$NotFoundException #0x0：启动期 Theme.applyStyle 叠加 parent=""
     裸 style，并把 framework android:textColor* 写成字面色。
  2) 「点应用弹窗直接消失」：AuroraTheme.relaunch() 在按钮回调里
     FLAG_ACTIVITY_CLEAR_TASK + finishAffinity() 销毁了 Dialog 宿主。
  3) CI 全绿但功能不生效：判据只查自己写的 4 个颜色，没查编译后 APK、
     没查强调色是否真的进了默认主题、没查查表引用的 style 是否存在。

本脚本在构建前对源码做硬断言，签名后对 APK 的 resources.arsc 做诊断。
任何一条失败都 exit 1；不把「能编译」当成「能启动 / 能生效」。

用法：
  python3 tools/theme_baseline_check.py                  # 源码级硬断言（构建前）
  python3 tools/theme_baseline_check.py <apk> <aapt2>   # 追加编译后资源表诊断
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


# ---- 1) 启动关键路径文件必须与可运行基线逐字节一致 -------------------------
for rel, exp in BASELINE.items():
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        errors.append('missing startup-chain file: ' + rel)
        continue
    got = sha256(p)
    if got != exp:
        errors.append('startup-chain file changed vs oktv-cat: %s (got %s expected %s)'
                      % (rel, got[:12], exp[:12]))

# ---- 2) Theme.Base parent 不得被改动 ----------------------------------------
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

# ---- 3) 所有 Theme.App.* 变体必须是完整 parent 链、禁止 framework 覆盖 ------
THEME_FILES = [
    os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'aurora_themes.xml'),
    os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'aurora_combos.xml'),
]
all_styles = {}   # name -> (parent, {item: '#RRGGBB'})
for p in THEME_FILES:
    if not os.path.isfile(p):
        errors.append('missing theme variant file: ' + os.path.basename(p))
        continue
    try:
        root = ET.parse(p).getroot()
    except ET.ParseError as e:
        errors.append('XML parse %s: %s' % (p, e))
        continue
    for s in root.iter('style'):
        name = s.get('name', '')
        if not name.startswith('Theme.App.'):
            continue
        parent = s.get('parent') or ''
        if parent != 'Theme.App' and not re.match(r'^Theme\.App\.(Aurora|Paper|Ink|Glass)$', parent):
            errors.append('theme variant must inherit full chain: %s parent=%s' % (name, parent))
        items = {}
        for i in s.iter('item'):
            n = i.get('name', '')
            if n.startswith('android:textColor') or n.startswith('android:window') \
                    or n == 'isLightTheme' or n.startswith('android:colorBackground'):
                errors.append('theme variant %s contains forbidden framework item: %s' % (name, n))
            v = (i.text or '').strip()
            if v.startswith('#'):
                items[n] = v
        all_styles[name] = (parent, items)

presets = [n for n in all_styles if re.match(r'^Theme\.App\.(Aurora|Paper|Ink|Glass)$', n)]
combos = [n for n in all_styles if n not in presets]
if len(presets) != 4:
    errors.append('expected 4 base presets, found %d' % len(presets))
if len(combos) != 32:
    errors.append('expected 32 preset x accent combos, found %d' % len(combos))

# ---- 3b) WCAG 对比度硬判据：文字与背景绝不同色 -----------------------------
def _lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(h):
    h = h.lstrip('#')
    if len(h) == 8:
        h = h[2:]
    if len(h) != 6:
        return None
    try:
        r, g, b = [int(h[i:i + 2], 16) for i in (0, 2, 4)]
    except ValueError:
        return None
    return 0.2126 * _lin(r) + 0.7152 * _lin(g) + 0.0722 * _lin(b)


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    if la is None or lb is None:
        return None
    hi, lo = sorted([la, lb], reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def resolve(name, seen=None):
    if name not in all_styles:
        return {}
    seen = seen or set()
    if name in seen:
        return {}
    seen.add(name)
    parent, items = all_styles[name]
    merged = resolve(parent, seen)
    merged.update(items)
    return merged


# (前景, 背景, 说明, 最低对比度)
PAIRS = [
    ('colorOnSurface', 'colorSurface', '正文/卡片', 4.5),
    ('colorOnSurfaceVariant', 'colorSurface', '次文字/卡片', 4.5),
    ('colorOnPrimary', 'colorPrimary', '主按钮字/底', 4.5),
    ('colorOnSecondary', 'colorSecondary', '次按钮字/底', 4.5),
    ('auroraOnSurface', 'auroraSurface', '原生正文/表面', 4.5),
    ('auroraOnSurfaceVariant', 'auroraSurface', '原生次字/表面', 4.5),
    ('auroraAccent', 'auroraSurface', '强调色/表面', 3.0),
    ('auroraAccent', 'auroraAccentSoft', '强调色/柔底', 3.0),
]
worst = {}
for name in all_styles:
    d = resolve(name)
    for fg, bg, label, need in PAIRS:
        if fg not in d or bg not in d:
            errors.append('theme variant %s missing %s or %s' % (name, fg, bg))
            continue
        ratio = contrast(d[fg], d[bg])
        if ratio is None:
            errors.append('theme variant %s has malformed color: %s=%s / %s=%s'
                          % (name, fg, d[fg], bg, d[bg]))
            continue
        worst[label] = min(worst.get(label, 99.0), ratio)
        if ratio < need:
            errors.append('LOW CONTRAST %s %s: %s on %s = %.2f:1 (need >= %.1f)'
                          % (name, label, d[fg], d[bg], ratio, need))
if worst:
    print('[contrast] %d variants checked, worst ratios: %s'
          % (len(all_styles), ', '.join('%s=%.2f' % kv for kv in sorted(worst.items()))))

# ---- 3c) 查表引用的每个 R.style 必须真实存在（防编译失败 / 静默不生效）------
engine = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'fongmi',
                      'android', 'tv', 'ui', 'theme', 'AuroraTheme.java')
if not os.path.isfile(engine):
    errors.append('missing AuroraTheme.java')
else:
    txt = open(engine, encoding='utf-8').read()
    for ref in set(re.findall(r'R\.style\.(Theme_App_[A-Za-z0-9_]+)', txt)):
        style_name = ref.replace('_', '.')
        # Theme_App_Aurora_Jade -> Theme.App.Aurora.Jade
        if style_name not in all_styles and style_name != 'Theme.App':
            errors.append('AuroraTheme references undefined style: %s (%s)' % (ref, style_name))
    if 'R.attr.colorOnPrimary' in txt:
        errors.append('AuroraTheme uses library attr R.attr.colorOnPrimary '
                      '(non-transitive R -> cannot find symbol)')

# ---- 4) 启动期引擎：禁止 applyStyle / 禁止销毁弹窗宿主 ----------------------
DIALOG = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'fongmi',
                      'android', 'tv', 'ui', 'theme', 'AuroraThemeDialog.java')
def strip_java_comments(t):
    t = re.sub(r'/\*.*?\*/', '', t, flags=re.S)
    t = re.sub(r'//[^\n]*', '', t)
    return t


for p, label in [(engine, 'AuroraTheme'), (DIALOG, 'AuroraThemeDialog')]:
    if not os.path.isfile(p):
        continue
    # 只查真实代码：注释里写「严禁 XXX」是文档，不能当成实际调用误杀。
    t = strip_java_comments(open(p, encoding='utf-8').read())
    for forbidden in ['FLAG_ACTIVITY_CLEAR_TASK', 'finishAffinity(', 'relaunch(',
                      'CLEAR_TOP', 'applyStyle(']:
        if forbidden in t:
            errors.append('%s contains forbidden %s (would destroy dialog host / break inflate)'
                          % (label, forbidden))
if os.path.isfile(engine):
    t = open(engine, encoding='utf-8').read()
    if not re.search(r'\bsetTheme\s*\(', t):
        errors.append('AuroraTheme must use Activity.setTheme()')
    if 'themeUsable' not in t:
        errors.append('AuroraTheme missing themeUsable() startup probe')
    if '.commit()' not in t:
        errors.append('AuroraTheme.put() must commit synchronously before recreate()')

# ---- 4b) 配置真正生效 / 应用按钮关窗（2026-11-12 回归防线）----------------
# 历史教训 4)「点应用并刷新后窗口没关、配置看似没生效」：
#   a) themeUsable 把 framework attr「解析不到」当失败 -> 每次静默回落 Theme.App；
#   b) recreate() 会恢复 DialogFragment -> 弹窗重新弹出，用户以为没保存；
#   c) 细项只在预览面板生效 -> 业务界面永远不变。
APP = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'fongmi',
                   'android', 'tv', 'App.java')
if os.path.isfile(engine):
    t = strip_java_comments(open(engine, encoding='utf-8').read())
    if re.search(r'resolveAttribute\(id,\s*raw,\s*false\)\s*\)\s*return false', t):
        errors.append('themeUsable still treats unresolvable framework attr as failure '
                      '(would silently fall back and never apply user config)')
    for token in ['markChanged', 'onActivityCreated', 'onActivityResumed', 'decorateTree']:
        if token not in t:
            errors.append('AuroraTheme missing global refresh/decoration entrypoint: ' + token)
if os.path.isfile(DIALOG):
    t = strip_java_comments(open(DIALOG, encoding='utf-8').read())
    for token in ['applyAndRefresh()', 'dismissAllowingStateLoss()', 'recreate()']:
        if token not in t:
            errors.append('apply button missing ' + token + ' (must close dialog and refresh)')
    if 'savedInstanceState != null' not in t:
        errors.append('dialog must self-dismiss after Activity recreation')
if os.path.isfile(APP):
    t = strip_java_comments(open(APP, encoding='utf-8').read())
    if 'AuroraTheme.onActivityCreated' not in t or 'AuroraTheme.onActivityResumed' not in t:
        errors.append('App must wire AuroraTheme lifecycle so detail options apply globally')

# ---- 5) ?attr/aurora* 必须全部声明 -----------------------------------------
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

# ---- 6) 编译后 resources.arsc 诊断（签名后 APK，不依赖 --values）------------
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

            # 只扫四套基础预设的 style bag：framework attr（0x0101xxxx）若被赋成
            # 字面色 #AARRGGBB，Material 控件取 value.resourceId 会得到 0 -> #0x0。
            # 注意：aapt2 会把父链继承项展开进子 style bag，Material 库自带 style
            # 里大量 @null 是合法的（itemBackground / itemActiveIndicatorStyle 等）。
            # 把 @null 检查扩到 32 个 combo 会把正常包全部误杀 —— 上一次构建
            # 第 20 步失败正是踩了这条。组合变体只做「是否编进 APK」的计数诊断，
            # 其 framework 色 / 对比度 / 查表完整性已由源码级 3/3b/3c 硬判据覆盖。
            in_bag = False
            preset_seen = set()
            variant_seen = set()
            for line in lines:
                mv = re.search(r'style/(Theme\.App\.[A-Za-z0-9_.]+)\s*$', line)
                if mv:
                    variant_seen.add(mv.group(1))
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
                if in_bag and re.search(r'=\s*@(?:null|0x0|empty)\b', line):
                    errors.append('compiled theme preset item resolved empty: ' + line.strip())
            if len(preset_seen) != 4:
                errors.append('aapt2 dump missing theme presets: found %s' % sorted(preset_seen))
            combo_seen = len([v for v in variant_seen if v.count('.') >= 3])
            print('[aapt2]   theme variants seen=%d (combos=%d, diagnostic only)'
                  % (len(variant_seen), combo_seen))
        except Exception as e:
            errors.append('aapt2 diagnostic failed: %s' % e)

if errors:
    print('THEME STARTUP-CHAIN CHECK FAILED (%d):' % len(errors))
    for e in errors:
        print('  -', e)
    sys.exit(1)
print('THEME CHECK PASSED: %d baseline files identical, %d variants, '
      'contrast OK, %d aurora attrs' % (len(BASELINE), len(all_styles), len(attrs)))
