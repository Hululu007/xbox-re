#!/usr/bin/env python3
"""真实 WCAG 校验：枚举 4 预设 × 8 强调色，断言前景/背景不会同色或低对比。
用法: python3 tools/aurora_contrast_check.py <repo_root>
"""
import sys, re, pathlib
def lin(c):
    c=c/255.0
    return c/12.92 if c<=0.04045 else ((c+0.055)/1.055)**2.4
def lum(v):
    return 0.2126*lin((v>>16)&255)+0.7152*lin((v>>8)&255)+0.0722*lin(v&255)
def cr(a,b):
    x,y=lum(a),lum(b)
    if x<y: x,y=y,x
    return (x+0.05)/(y+0.05)
def parse(path):
    s=pathlib.Path(path).read_text(encoding='utf-8')
    out={}
    for m in re.finditer(r'<style name="([^"]+)"[^>]*>(.*?)</style>',s,re.S):
        d={}
        for mm in re.finditer(r'<item name="([^"]+)">(#[0-9A-Fa-f]+)</item>',m.group(2)):
            d[mm.group(1)]=int(mm.group(2)[1:],16)|0xFF000000
        out[m.group(1)]=d
    return s,out
root=pathlib.Path(sys.argv[1] if len(sys.argv)>1 else '.')
themes_s,presets=parse(root/'app/src/main/res/values/aurora_themes.xml')
combos_s,combos=parse(root/'app/src/main/res/values/aurora_combos.xml')
fails=[]
assert len(combos)==32, '组合 style 数量应为 32，实际 %d'%len(combos)
for name,d in combos.items():
    for k in ('colorPrimary','colorOnPrimary','colorSecondary','colorOnSecondary','colorControlActivated','auroraAccent','auroraAccentSoft'):
        assert k in d, name+' 缺 '+k
    assert cr(d['colorOnPrimary'],d['colorPrimary'])>=4.5, name+' 按钮文字对比 %.2f'%cr(d['colorOnPrimary'],d['colorPrimary'])
    assert cr(d['auroraAccent'],d['auroraAccentSoft'])>=4.5, name+' chip 文字对比 %.2f'%cr(d['auroraAccent'],d['auroraAccentSoft'])
    p=name.split('.')[2]
    base=presets['Theme.App.'+p]
    assert cr(d['auroraAccent'],base['colorSurface'])>=3.0, name+' 强调色在卡片上对比 %.2f'%cr(d['auroraAccent'],base['colorSurface'])
for name,d in presets.items():
    assert cr(d['colorOnSurface'],d['colorSurface'])>=4.5, name+' 正文对比不足'
    assert cr(d['colorOnSurfaceVariant'],d['colorSurface'])>=4.5, name+' 次文字对比不足'
    assert cr(d['colorPrimary'],d['colorOnPrimary'])>=4.5, name+' 主色按钮对比不足'
# 组合 style 绝不能含 framework attr（历史上正是它造成 #0x0 启动崩溃）
for m in re.finditer(r'<style name="(Theme\.App\.[^"]+)"[^>]*>(.*?)</style>',combos_s,re.S):
    bad=re.findall(r'<item name="(android:[^"]+)"',m.group(2))
    assert not bad, m.group(1)+' 含 framework attr: '+','.join(bad)
print('PASS: 32 组合 + 4 预设，正文/次文字/按钮/chip/强调色对比度全部达标，且组合 style 不含 android:*')
