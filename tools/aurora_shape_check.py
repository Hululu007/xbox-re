#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""形状层硬判据：禁 applyStyle / 禁 theme 级 android:textColor* / 禁 drawable 内 ?attr/ / 对比度。"""
import os, re, sys, glob

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
err = []

base = os.path.join(ROOT, "app/src/mobile/java/com/fongmi/android/tv/ui/base/BaseActivity.java")
if os.path.exists(base):
    t = open(base, encoding="utf-8").read()
    if "applyStyle" in t:
        err.append("BaseActivity 含 applyStyle（#0x0 成因）")

for f in glob.glob(os.path.join(ROOT, "app/src/main/res/values/aurora_*.xml")):
    t = open(f, encoding="utf-8").read()
    for m in re.finditer(r'<style name="(Theme\.App\.[^"]+)"[^>]*>(.*?)</style>', t, re.S):
        if re.search(r'name="android:textColor\w*"', m.group(2)):
            err.append("%s: theme 级 android:textColor* 覆盖" % m.group(1))
    if re.search(r'name="android:colorBackground"', t):
        err.append("%s: theme 级 android:colorBackground 覆盖" % f)

for f in glob.glob(os.path.join(ROOT, "app/src/main/res/drawable/aurora_*.xml")):
    t = open(f, encoding="utf-8").read()
    if "?attr/" in t or "?android:attr/" in t:
        err.append("%s: drawable 引用 ?attr/（有 0x0 风险）" % os.path.basename(f))

st = os.path.join(ROOT, "app/src/main/res/values/aurora_shape_styles.xml")
if os.path.exists(st):
    t = open(st, encoding="utf-8").read()
    for name, parent in re.findall(r'<style name="([^"]+)"\s+parent="([^"]+)"', t):
        if not parent.strip():
            err.append("%s: parent 为空" % name)
    if re.search(r'name="android:textColor\w*"', t):
        err.append("shape styles: 控件 style 出现 android:textColor*")

def lum(h):
    h = h.lstrip("#"); h = h[2:] if len(h) == 8 else h
    v = [int(h[i:i+2], 16)/255.0 for i in (0, 2, 4)]
    v = [c/12.92 if c <= .03928 else ((c+.055)/1.055)**2.4 for c in v]
    return .2126*v[0] + .7152*v[1] + .0722*v[2]

def ratio(a, b):
    x, y = sorted((lum(a), lum(b)), reverse=True)
    return (x + .05) / (y + .05)

pairs = [("#FF101A16", "#FFFFFFFF", "浅色主文字"), ("#FF47574F", "#FFFFFFFF", "浅色次文字"),
         ("#FFF2F7F4", "#FF17211D", "深色主文字"), ("#FFC2D0C9", "#FF17211D", "深色次文字"),
         ("#FF101A16", "#FFD9EDE4", "选中胶囊文字"), ("#FFF2F7F4", "#FF173A2C", "深色选中胶囊")]
for fg, bg, label in pairs:
    r = ratio(fg, bg)
    print("  %-14s %.2f:1 %s" % (label, r, "OK" if r >= 4.5 else "FAIL"))
    if r < 4.5:
        err.append("%s 对比度 %.2f < 4.5" % (label, r))

print("SHAPE_CHECK", "PASS" if not err else "FAIL", "| rules", len(glob.glob(os.path.join(ROOT, "app/src/main/res/drawable/aurora_*.xml"))), "drawables")
for e in err:
    print("  ERR", e)
sys.exit(1 if err else 0)
