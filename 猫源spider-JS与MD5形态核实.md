# 猫源 spider 的 JS + MD5 形态核实（2026-11-09）

## 用户给的两个文件（GitHub API 直读实证）

| 文件 | 字节 | 内容 |
|---|---|---|
| `xuenai/index.js` | 664046 | 混淆后的 drpy/猫源 JS 爬虫加载器，开头 `function _0x2783(...)` |
| `xuenai/index.js.md5` | 32 | `f0c67ae0db9c1310589065c6b473ded9` |

`index.js` 实测 MD5 = `f0c67ae0db9c1310589065c6b473ded9`，与 `.md5` 文件**逐字符一致**。
所以「由 index.js 和 index.js.md5 两部分组成」成立：本体 + 更新校验。

## 结论：在支持范围内，但它不是「接口地址」

接口仍然是配置 json（`sites` / `lives` / `spider` / `parses`）。
`VodConfig.initSite` 读 `spider` → `BaseLoader.parseJar` → `JarLoader.parseJar`：

```
167  String[] texts = jar.split(";md5;");
168  String md5 = texts.length > 1 ? texts[1].trim() : "";
169  if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim();   ← 拉 .md5 文件
170  jar = texts[0];
172  if (!md5.isEmpty() && Util.equals(jar, md5)) load(local cache);  ← 命中就复用
174  else if (jar.startsWith("http")) load(Download.create(jar, ...)); ← 否则重下 js
```

所以配置里写
`"spider": "<index.js URL>;md5;<index.js.md5 URL>"`
就会被正确识别：拉校验文件、比对缓存、按需更新 JS。

⚠️ **别把 `index.js.md5` 直接填进「新增接口」**：它是 32 字节纯文本，
`VodConfig.parseConfig()` 解析不出 `sites` → 站点列表为空。

## 与影视TV接口的关系

不是互斥的两种接口类型，共用同一套 `VodConfig.parseConfig()`。
区别只在站点级：影视TV 类站点 type 0/1/4 直接请求 json；猫源 type=3
站点走 `csp_` / `.js` / `.py` 爬虫，才需要 `spider` 提供运行时。
这正是「猫源接口经过处理能做到和影视tv接口同功能」的含义。

## 本轮分支更新（catvod-upgrade，不构建）

- `ConfigSanitizer.spider(object)`：安全取 spider（数组取首项、对象拼 {url,md5}、
  三段 trim 但保留 http 校验引用、单段原样返回），正常配置一个字节不变
- `VodConfig.initSite` / `LiveConfig.initLive` 改用它，把 spider 纳入上一轮
  在线订阅规范化的覆盖范围
- `AGENTS.md` 第 31 条记下口径

没动：app 名称 / 包名 / 版本、build.gradle、workflow、海阔、实验室、TV 代理按钮、
`oktvpro` / `main` / `lab-fix`。校验文件请求走 `OkHttp.client()`，代理总闸亮着仍生效。
