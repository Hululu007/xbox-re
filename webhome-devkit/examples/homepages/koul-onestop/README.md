# 口令一条龙 · WebHome（oktv-cat 增量版）

> 分支：`oktv-cat-koul-onestop`，基线 `oktv-cat` @ `f7f9a03`
> 本目录 **只新增文件，不修改 `oktv-cat` 的任何现有代码 / 配置 / 构建脚本**。
> 形态：一个自包含的单文件 WebHome 首页（`index.html`），挂进 App 后由 Native 注入 `fm` SDK。

---

## 0. 先回答「是否可以做到」

**可以，六段链路全部有现成的宿主能力可对接**，本目录已把它实现并离线跑通。对应关系：

| 环节 | 用到的宿主能力（真实存在于 `oktv-cat`） | 本页实现 |
|---|---|---|
| ① 入口 | WebHome `homePage` → `HomeWebController` 加载 WebView + 注入 `fm` | 单文件 `index.html`，六个视图 |
| ② 口令管理 | `fm.cache.set/get(key, value, rule)` 持久化 | 口令 CRUD / 启停 / 端点模板 |
| ③ 获取口令数据 | `fm.req(url, options)`（Native OkHttp，无 CORS） | 拉取 → 数据集管理 / 离线导入 |
| ④ 生成站点管理 | `POST /manage/csp?registry=<JSON>` → `CustomCspSetting` 注入 | 归一化解析 → 站点管理 → 一键注入 |
| ⑤ 站点运行 | `fm.search(keyword,{direct:true})` / `fm.vod(siteKey,vodId,…)` + 页内 `fm.req`+DOMParser | 原生运行 + 页内运行双路径 |
| ⑥ 桥接播放 | `fm.play` / `fm.vodInline` / `fm.pan.play` → `SiteApi.PUSH` → App 播放器 | 按 URL 类型自动路由 |

**但有三个必须说清的边界**（不夸大）：

1. **仓库里没有「口令」这个概念**（全库代码搜索 `口令` 命中 0）。所以口令 → 数据的接口是**可配置模板**，不是内置服务：`endpoint` 里的 `{code}` 会被替换成口令码，走 `fm.req`。真实口令服务由你自己提供；本页内置离线演示样本保证链路可验。
2. **站点真正运行在 App 原生 Spider/CSP 引擎里**，WebView 不能替代 App 跑 CSP 站点。本页的「运行」是：把站点注入 App 后调用 `fm.search` / `fm.vod` 交给原生链路；页内 `fm.req` + DOMParser 只是**兜底 / 预览**。
3. **没有编译 APK**。沙箱不具备 JDK 21 + Android SDK 37 + NDK 29 + Gradle 9.5.1 的完整构建环境，本版交付的是 WebHome 层（无需编译即可部署验证），不是新 APK。真机回归仍需要你在 App 里过一遍。

---

## 1. 六段链路逐段说明

### ① 入口
`site.json` 是一条标准 WebHome 站点配置（`type:3` + `homePage`）。把 `index.html` 放到任意可访问地址，配置指向它即可。页面自检 `window.fm`：

- 有 `fm` → 用 App 原生 SDK（状态栏显示绿色 `App 原生 fm SDK`）；
- 无 `fm` → **自动挂载内置离线演示桥**，浏览器 / 电脑直接打开也能跑完整流程（PoC 靠这条）。

### ② 口令管理
口令记录：`{id, name, code, endpoint, method, headers, note, enabled, updatedAt}`，存 `fm.cache(key="state", rule="koul1")`，同时写 `localStorage` 双保险。支持新增、编辑、启停、删除、批量。

`endpoint` 模板示例：
```
https://api.example.com/koul?code={code}
```
`{code}` 会做 `encodeURIComponent` 后替换；`kw` 关键词自动追加到 query。请求头可填 JSON，统一走 `fm.req`。

### ③ 获取口令数据
一次拉取 = 一条**数据集** `{id, source, tokenId, tokenName, raw, fetchedAt, siteCount}`。保留原始响应，可反复解析、重新生成站点、删除、重拉，也可直接粘贴 JSON/文本离线导入。

响应解析器 `normalizeSites()` 兼容：

| 输入形态 | 识别方式 |
|---|---|
| TVBox/CSP 配置 | `{"sites":[...]}` |
| 多配置源 | `{"urls":[...]}`（depot，每条 url 生成一条配置源站点） |
| `data` / `list` / `items` 数组 | 通用数组外壳 |
| 顶层数组 | 直接当站点列表 |
| 单站点对象 | 含 `key` / `api` / `name` |
| 纯文本 | 每行 `名称,地址`、`名称  地址` 或单独 URL |

字段归一化为 `{key, name, type, api, homePage, ext, searchable, enabled, sourceId}`；缺省 `key` 用 api 的 hash 生成。

### ④ 口令数据生成站点管理
按 `key` 合并（同源重新生成不会重复堆叠）。管理动作：全选启停、按 key 去重、导出、复制、删除、单条运行。

**注入到 App**（闭环关键）：
```text
POST http://127.0.0.1:{port}/manage/csp
Content-Type: application/x-www-form-urlencoded

registry={"enabled":true,"insertIndex":0,"sites":[{key,name,type,api,homePage,...}]}
```
对应 `app/src/main/java/com/fongmi/android/tv/server/process/Manage.java:761` 的 `csp()` → `CustomCspSetting.parse/save` → `reloadConfigs()` 重载点播配置。端口优先取 `fm.device().ip` 里的端口，兜底 `9978`（App 实际在 9978–9998 探测）。

注入失败会**自动降级**为「复制 registry JSON → 设置 → 增强功能 → 管理页面 → 站点注入 手动粘贴」，不会静默失败。

### ⑤ 站点运行
- **原生路径**（推荐）：`fm.search(keyword, {direct:true})` 触发 App 全站搜索；有真实 `vod_id` 时 `fm.vod(siteKey, vodId, title, pic)` 打开 App 原生详情/播放链。
- **页内路径**（兜底/预览）：`fm.req(homePage 或 api)` 取回 HTML → `DOMParser` 提取可播放条目 → 渲染成 VOD 卡片。演示样本产出 3 条，覆盖三种播放桥。

### ⑥ 桥接 App 播放器播放
| URL 类型 | 调用 | 去向 |
|---|---|---|
| `http(s)` 直链 / m3u8 / mp4 | `fm.play(url, title, {pic, wallPic})` | `SiteApi.PUSH` |
| 网盘 / `magnet:` / `ed2k:` / `thunder:` | `fm.pan.play({type,url,title,pic})` | 统一推送解析链 |
| 多集 / 按集解析 | `fm.vodInline(payload)` | 临时多集 + resolver |

播放前把 `{pic, wallPic}` 按 URL hash 写进 `fm.cache`，便于 `fm.history()` 恢复剧照（README 明确原生 push 历史通常只保留海报）。播放记录同时落本地，⑥ 视图可对照 `fm.history()`。

---

## 2. 部署（三选一）

**A. 站点注入（推荐，不改原配置）**
1. 把本目录 4 个文件（或只 `index.html`）传到静态服务器 / 对象存储。
2. 改 `site.json` 的 `homePage` 为真实地址。
3. App：设置 → 增强功能 → 管理页面 → 站点注入 → 新增 WebHome，粘贴 `site.json` 内容（或填 `homePage` 链接）。

**B. 直接加进点播配置**
把 `site.json` 这条追加到配置的 `sites[]`：
```json
{ "key":"koul_onestop", "name":"口令一条龙", "type":3,
  "api":"csp_KoulOnestop", "homePage":"https://你的域名/koul-onestop/index.html" }
```

**C. 本地文件**
`file://` 路径也可作 `homePage`（`CustomCspSetting.localUrl` 支持），把 `index.html` 放到设备存储即可。

> 页面内「注入到 App」按钮是把**④ 生成的站点**写进 App，不是把本页写进 App，两者别混。

---

## 3. 离线验证

```bash
python3 verify.py
```

Playwright 加载 `file://index.html`，自动走离线演示桥，逐段断言：

| 断言 | 期望 |
|---|---|
| 入口 | 6 个 Tab、状态栏为离线演示桥 |
| 口令 | `state.tokens.length == 1` |
| 取数 | `datasets.length == 1` 且 `siteCount == 4` |
| 生成站点 | `sites.length == 4`，`buildRegistry().sites.length == 4` |
| 注入 | 产生 `/manage/csp` 的 `req` 调用且返回成功 |
| 运行 | `vods().length == 3` |
| 播放 | `fm.play` / `fm.pan.play` / `fm.vodInline` **三种桥各至少 1 次** |
| 播放记录 | `playLog.length == 3` |
| 页面异常 | 0 |

另做 JS 语法基线扫描（`?.` / `??` / `=>` / 模板字符串 / `let|const|class|async|await` / `replaceAll` / `fromEntries` / `flat` / `globalThis`），确保兼容文档要求的 Chromium 50–70 基线。产物：`verify_report.json`、`koul_onestop_poc.png`。

---

## 4. 兼容性（按 `webhome-devkit/README.md` 第 13 章）

- JS 全篇 `var` + `function` + 字符串拼接 + `Promise`，**不用** `?.` / `??` / 箭头 / 模板字符串 / `class` / `async-await` / `String.replaceAll` / `Object.fromEntries` / `Array.flat` / `globalThis`。
- `Object.values/entries`、`closest`、`replaceChildren` 均不依赖；`navigator.clipboard` 不用，复制走 `document.execCommand('copy')` 兜底。
- CSS：`flex` 可用但**不用 flex `gap`**（用 margin）；不用 `:is()` / `:has()` / `clamp()`；`max()`/`env()` 安全区声明带固定值兜底（旧内核丢弃整条声明仍保留 `18px`）；高度用 `var(--fm-web-height, 100vh)`；TV 焦点用 `:focus`。
- `fmsdk` 事件、返回边界、`fm.ui` 均未强依赖，页面在纯浏览器里也能完整跑。

---

## 5. 边界 / 未实现（真机前请知悉）

| 项 | 状态 |
|---|---|
| 真实口令服务端 | **不在本版**：需自备，填 `endpoint` 模板即可 |
| 口令数据加密 / 签名 | 未做；`fm.req` 支持自定义 headers，可自行加 |
| CSP/Python/JS Spider 的页内执行 | **未做**：站点由 App 原生引擎运行，页内只做 HTML 解析兜底 |
| `fm.vod` 的真实 `vod_id` | 需从 App 搜索结果取得；本页演示用占位 id |
| `/manage/csp` 鉴权 | 管理页当前无鉴权，仅在可信局域网临时开启；注入前需先开「管理页面」 |
| 自动写点播配置 | **刻意不做**（`/action?do=setting` 会替换整份配置，风险高），只提供复制 |
| 真机 / 最旧盒子回归 | 未做，需你在 Chrome 预览之外过一遍（`webview` tag 可看内核版本） |

---

## 6. 回滚

本分支只新增 `webhome-devkit/examples/homepages/koul-onestop/` 下 5 个文件，不动任何现有文件。删除该目录或切回 `oktv-cat` 即完全回滚；`oktv-cat` 分支 HEAD 未变。

## 7. 文件清单

| 文件 | 说明 |
|---|---|
| `index.html` | 单文件 WebHome 首页，六段链路，自包含离线演示桥 |
| `sample-data.json` | 离线演示口令数据（4 个站点） |
| `site.json` | WebHome 站点配置片段 |
| `verify.py` | Playwright 端到端验证器 |
| `README.md` | 本文档 |
