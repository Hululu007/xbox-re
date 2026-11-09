# 猫源 `.js` / `.js.md5` 单文件接口：直接填进新增点播/直播

> 2026-11-10 · `catvod-upgrade` 分支 · **不构建**
> 你附的 `index.js.md5` 链接解析服务仍失败；内容由上一轮 GitHub API 直读实证：
> `index.js` 664046 字节、`index.js.md5` 32 字节 `f0c67ae0db9c1310589065c6b473ded9`，
> 两者 MD5 一致。

## 先认个错

上一轮我说「**别把 `.md5` 填进新增接口，要填含 sites 的配置 json**」——那是按
「接口 = 配置 json」的旧口径说的。你的真实需求是：**app 自己把这类地址解析成可用接口**，
让用户在新增点播 / 直播里直接填它，就能像影视TV接口一样出站点、取数、播放。
这句按新口径作废，现在改为自动合成。

## 为什么原来不行

| 入口 | 原来发生什么 |
|---|---|
| 点播 | `VodConfig.load` → `Decoder.getJson` 拿到 32 字节 `f0c67ae...` → `Json.parse` 抛异常 → 站点列表空 |
| 直播 | `LiveConfig.load` → `Json.isObj` 为假 → `parseText` 把 hash 当 m3u 文本 → 一条空直播 |

## 落地做法（只合成内存配置，不改任何 loader）

新增 `JsMd5Interface`，在 `VodConfig.load` / `LiveConfig.load` **最前面**按 URL 形态短路：

```
填 .../index.js.md5  →  jsUrl = .../index.js
                        md5Url = .../index.js.md5
填 .../index.js      →  jsUrl = 原地址
                        md5Url = 原地址 + ".md5"（推测）

点播合成： { "sites": [ { key, name, type:3, api:jsUrl, ext:"", jar:"jsUrl;md5;md5Url" } ] }
直播合成： { "lives": [ { name, type:3, api:jsUrl, ext:"", jar:"jsUrl;md5;md5Url" } ] }
```

**关键点：`;md5;` 放在条目的 `jar`，不放顶层 `spider`。**

- 放顶层 → `BaseLoader.parseJar(spider, true)` 预加载，且 `Site/Live.objectFrom`
  会把空 jar 继承成 spider，664KB 纯文本 js 被当 dex 塞给所有条目。
- 放条目 jar → 站点首次点击时 `site.spider()` → `JsLoader` → `BaseLoader.dex(jar)`
  → `JarLoader.parseJar` 第 167–177 行：split `;md5;`、拉 md5Url、
  `Util.equals(jsUrl, md5)` 比对 `Path.jar(jsUrl)` 缓存、**不一致才重新下载 js**。
  md5 更新检测天然生效，零新写校验逻辑。

`api` 保持远程 http(s)：`Module.fetch` 只认 http / assets / lib/，
`UrlUtil.convert` 对 http 原样返回，quickjs 拉源码执行；
dex 对纯文本 js 的 `loadClass` 失败被 `createFun` 的 catch 吞掉，不影响 JS 引擎。

顶层不写 `spider` → `ConfigSanitizer.spider` 返回空 → `parseJar("")` 直接 return；
点播合成不写 lives → `VodConfig.initLive` 对空 lives 直接 return，不串改直播。

## 与影视TV接口同功能

共用同一套 `VodConfig.parseConfig` / `LiveConfig.parseConfig`、
`ConfigSanitizer`、`SiteFeature` 代理总闸、`MediaSourceFactory` 播放链。
区别只在站点级：这类是 type=3 的 js 爬虫，其它接口是 type 0/1/4 直接取 json。
「新增接口 → 出站点 → 取数 → 播放」的路径完全一致。

刷新语义也一致：`VodConfig.clear()` 会 `BaseLoader.clear()`，
重新加载即重新校验 md5、按需更新 js；`Config` 表里存的仍是用户填的原始地址。

## 如实说的边界

1. **脚本本身要提供对应方法**：点播需 `home/category/detail/search/play`，
   直播需 `live`（`LiveParser` 第 67 行 `live.spider().liveContent(...)`）。
   我们负责把它挂成站点，不负责让任意 js 变成万能爬虫。
2. 填 `.js` 时 `.md5` 是推测地址；取不到时 `dex()` 内部吞异常，
   降级成「无校验但仍能执行」，不会让站点失效。
3. 同一次使用会发两个请求：`JarLoader` 拉校验/缓存、`Module.fetch` 拉 js 源码；
   前者有文件缓存、后者有 LruCache，都走 `OkHttp.client()`，代理总闸亮着生效。
4. 没动 app 名称 / 包名 / 版本、build.gradle、workflow、Manifest、海阔、实验室、
   TV 代理按钮、`oktvpro` / `main` / `lab-fix`。沙盒无 SDK 未编译，等 CI。

## 本轮提交

`catvod-upgrade` 新增 1 个 commit，改动 5 个文件：
新增 `JsMd5Interface.java` + 说明文档；`VodConfig.load` / `LiveConfig.load`
各加 3–4 行短路；`AGENTS.md` 第 32 条。**未构建、未 dispatch。**
