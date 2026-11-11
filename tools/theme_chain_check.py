#!/usr/bin/env python3
"""启动期资源链静态校验：把 XML 主题表按 parent 链展开，找出会解析成 0x0 的属性。
这类 HomeActivity inflate 崩溃不需要真机——编译前把资源表展开一遍就能定位。
"""
import os, re, sys, glob, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
errors = []
styles = {}
attrs = set()
aurora_refs = set()

for path in glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', 'values*', '*.xml')) + glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', 'layout*', '*.xml')) + glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', 'drawable*', '*.xml')) + glob.glob(os.path.join(ROOT, 'app', 'src', '*', 'res', 'color*', '*.xml')):
    txt = open(path, encoding='utf-8', errors='replace').read()
    for m in re.finditer(r'\?attr/(aurora\w+)', txt):
        aurora_refs.add(m.group(1))
    try:
        root = ET.fromstring(txt)
    except ET.ParseError as e:
        errors.append('XML parse: %s %s' % (path, e)); continue
    for el in root.iter():
        tag = el.tag.split('}')[-1]
        if tag == 'attr' and el.get('name'):
            attrs.add(el.get('name'))
        if tag == 'style' and el.get('name'):
            name = el.get('name'); parent = el.get('parent')
            if parent is None and '.' in name: parent = name.rsplit('.', 1)[0]
            items = {i.get('name'): (i.text or '').strip() for i in el if i.tag.split('}')[-1] == 'item' and i.get('name')}
            styles.setdefault(name, []).append((parent, items, os.path.basename(path)))

LIB = ('Theme.Material', 'Theme.AppCompat', 'ThemeOverlay.Material', 'Widget.', 'TextAppearance.', 'Base.', '@android:')

def parent_of(name):
    e = styles.get(name)
    return e[0][0] if e else None

# 1) parent 链完整、无自引用、无循环
for name, entries in styles.items():
    for parent, items, f in entries:
        if parent == name:
            errors.append('self-inheritance: %s (%s)' % (name, f))
        elif parent and not parent.startswith(LIB) and parent and parent not in styles:
            errors.append('broken parent: %s -> %s (%s)' % (name, parent, f))
    seen = set(); cur = name
    while cur and cur in styles and cur not in seen:
        seen.add(cur); cur = parent_of(cur)
    if cur in seen:
        errors.append('parent cycle at %s' % name)

# 2) 真正的静态解耦：Theme.Base 不得挂在 DynamicColors 链上
for name in ('Theme.Base',):
    for parent, items, f in styles.get(name, []):
        if parent and 'DynamicColors' in parent:
            errors.append('Theme.Base still depends on DynamicColors injection: %s (%s)' % (parent, f))

# 3) 四套预设必须挂在基线 Theme.App 链上，且关键 M3 颜色为字面值
REQUIRED = ('colorPrimary', 'colorOnSurface', 'colorSurface', 'colorSurfaceContainer')
for name in ('Theme.App.Aurora', 'Theme.App.Paper', 'Theme.App.Ink', 'Theme.App.Glass'):
    entries = styles.get(name)
    if not entries:
        errors.append('missing preset: %s' % name); continue
    parent, items, f = entries[0]
    if parent != 'Theme.App':
        errors.append('%s parent=%s (must be baseline Theme.App)' % (name, parent))
    for a in REQUIRED:
        if a not in items:
            errors.append('%s missing literal %s -> runtime 0x0' % (name, a))

# 4) 所有 ?attr/aurora* 必须在 Theme.Base 有兜底值（Manifest 主题也能解析）
base_items = {}
for parent, items, f in styles.get('Theme.Base', []):
    base_items.update(items)
for a in sorted(aurora_refs):
    if a not in attrs:
        errors.append('?attr/%s used but attr not declared' % a)
    if a not in base_items:
        errors.append('?attr/%s has no fallback in Theme.Base' % a)

if errors:
    print('THEME CHAIN CHECK FAILED (%d):' % len(errors))
    for e in errors: print('  -', e)
    sys.exit(1)
print('THEME CHAIN CHECK PASSED: %d styles, %d attrs, %d aurora refs, 4 presets, no 0x0 path'
      % (len(styles), len(attrs), len(aurora_refs)))
