#!/usr/bin/env python3
"""主题启动链检查：只验「不改基线」这一条，源码级；编译后另需 aapt2 dump。"""
import os, re, sys, glob, xml.etree.ElementTree as ET
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
errors = []
BASE_PARENTS = {}
for p in glob.glob(os.path.join(ROOT,'app','src','*','res','values','styles.xml')):
    try: root = ET.parse(p).getroot()
    except ET.ParseError as e: errors.append('XML %s %s' % (p,e)); continue
    for s in root.iter('style'):
        if s.get('name') == 'Theme.Base' and s.get('parent'):
            BASE_PARENTS[p] = s.get('parent')
# 1) Theme.Base parent 不得被主题改动（mobile 必须仍是 DynamicColors 链）
for p, parent in BASE_PARENTS.items():
    if 'mobile' in p and 'DynamicColors' not in parent:
        errors.append('Theme.Base parent changed on mobile: %s -> %s' % (p, parent))
# 2) 四套预设必须 parent=""
themes = glob.glob(os.path.join(ROOT,'app','src','main','res','values','aurora_themes.xml'))
for p in themes:
    root = ET.parse(p).getroot()
    for s in root.iter('style'):
        if s.get('name','').startswith('Theme.App.') and s.get('parent') not in ('', None):
            errors.append('preset must be pure overlay (parent=""): %s parent=%s' % (s.get('name'), s.get('parent')))
        if s.get('name','').startswith('Theme.App.'):
            for i in s.iter('item'):
                n = i.get('name','')
                if n.startswith('android:window') or n == 'isLightTheme':
                    errors.append('preset %s contains forbidden window/daynight item: %s' % (s.get('name'), n))
# 3) 主题引擎禁止 setTheme
for p in glob.glob(os.path.join(ROOT,'app','src','main','java','**','AuroraTheme.java'), recursive=True):
    txt = open(p,encoding='utf-8').read()
    if re.search(r'\bsetTheme\s*\(', txt): errors.append('AuroraTheme calls setTheme(): %s' % p)
# 4) ?attr/aurora* 必须有声明
attrs=set(); refs=set()
for p in glob.glob(os.path.join(ROOT,'app','src','*','res','values*','*.xml')):
    txt=open(p,encoding='utf-8',errors='replace').read()
    for m in re.finditer(r'<attr name="(aurora\w+)"',txt): attrs.add(m.group(1))
for p in glob.glob(os.path.join(ROOT,'app','src','*','res','**','*.xml'), recursive=True):
    txt=open(p,encoding='utf-8',errors='replace').read()
    for m in re.finditer(r'\?attr/(aurora\w+)',txt): refs.add(m.group(1))
for a in sorted(refs-attrs): errors.append('?attr/%s used but not declared' % a)
if errors:
    print('THEME OVERLAY CHECK FAILED (%d):' % len(errors))
    for e in errors: print('  -',e)
    sys.exit(1)
print('THEME OVERLAY CHECK PASSED: %d Theme.Base parents, %d aurora attrs, %d refs' % (len(BASE_PARENTS),len(attrs),len(refs)))
print('NEXT: aapt2 dump resources --values <apk> | check bottomNavigationStyle/colorSurface/colorSurfaceContainer non-null')
