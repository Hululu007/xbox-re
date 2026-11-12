# 海阔独立入口 · 构建说明（阶段六）

## 0. 这一版做了什么

在 `oktv-cat` 基础上新增一个**独立模块**：首页下部的多功能横按钮加一个「海阔」入口，
点击后进入独立 Activity 的独立 WebView，不复用首页 `webOverlay` / `fongmiBridge` / `HomeWebController`。

| 环节 | 实现 |
|---|---|
| 入口 | `Func.create(R.string.home_hiker)` → `HikerEntry.start(this)`；同时注册进 `HomeButton`（设置可显隐排序，id=10） |
| 独立运行时 | `HikerActivity` + 独立 `WebView` + `assets/hiker/index.html` |
| 同步桥 | `HikerBridge`（`@JavascriptInterface`，`req()` **同步返回 String**） |
| 兼容层 | `hiker_compat.js`：五类历史桥探测 / 语义归一 / 作用域隔离 / 多变形态降级 |
| 桥适配 | `hiker_bridge_adapter.js`：把 Java 同步桥适配成 `FM_SHELL` / `FM_JSREQ` / `fy_bridge_app` / `__fmBridge` 四种历史形态 |
| 运行时垫片 | `jst4.js`（37 个全局 API）+ `hiker_runtime_shim.js`（L1~L5 补齐层） |
| 业务桥实现（阶段八） | `HikerJson` / `HikerQR` / `HikerApi`（纯 JDK，35 个方法）+ `HikerBridge.call` 通用入口；`bridge` 档 25 个 API 由「有符号」补齐为「有语义」 |
| 六段链路 | 入口 → 口令管理 → 口令数据 → 生成站点 → 站点运行 → 桥接播放 |
| 播放出口 | `HikerBridge.play()` → 本地 `/action?do=push&url=` → `ServerEvent.push` → `VideoActivity.push` |

## 1. 文件清单

**新增（不动任何现有逻辑）**

```
app/src/main/java/com/fongmi/android/tv/hiker/
    HikerEntry.java        # 唯一对外出口 start(Activity)
    HikerActivity.java     # 独立 Activity / 独立 WebView / 生命周期
    HikerBridge.java       # 同步字符串 Java 桥（31 个 @JavascriptInterface 方法）
    HikerHttp.java         # 同步 HTTP（GBK/UTF-8 探测、gzip），仅 JDK 依赖
    HikerContract.java     # 常量
    HikerJson.java         # 极简 JSON + 自建 Base64（阶段八，纯 JDK）
    HikerQR.java           # QR 编码器（阶段八，纯 JDK）
    HikerApi.java          # 35 个 bridge API 的 call(name,argsJson) 分发与实现（阶段八，纯 JDK）

app/src/main/assets/hiker/
    index.html                   # 容器页（六段链路 + 兼容面板，ES5）
    hiker_bridge_adapter.js      # 桥形态适配
    hiker_compat.js              # 兼容层
    hiker_runtime_shim.js        # 补齐层（阶段五）
    jst4.js                      # JsT4 垫片（v390）

app/src/main/res/
    drawable/ic_home_hiker.xml                 # vector 图标
    values/strings_hiker.xml                   # 英文基准
    values-zh-rCN/strings_hiker.xml            # 简体
    values-zh-rTW/strings_hiker.xml            # 繁体
    values-zh/strings_hiker.xml                # 中文 fallback
```

**修改（精确最小插入，全部「只增不删」，见 `patch/app_changes.diff`）**

| 文件 | 改动 |
|---|---|
| `leanback/.../bean/Func.java` | +2 行：`setDrawable()` 追加 `home_hiker` 分支 |
| `leanback/.../ui/activity/HomeActivity.java` | +2 行：`setFunc()` 加一个按钮、`onItemClick()` 加一个跳转 |
| `leanback/.../bean/HomeButton.java` | +3 行 / 1 行常量值替换：`ALL`、`all()`、`getDefaultButtons()` 注册 id=10 |
| `main/AndroidManifest.xml` | +7 行：注册 `HikerActivity` |

> 资源用**新增独立 xml 文件**（`strings_hiker.xml` / `ic_home_hiker.xml`），不改任何现有资源文件；
> `HikerEntry.start()` 用全限定名调用，不改 `HomeActivity` 的 import 区。

## 2.0 更新（阶段九 · 真实 CI 构建已跑通）

2026-11-12 已通过仓库自带 `Android Release` 工作流在 GitHub 托管 runner 上对
`oktv-cat-hiker-v3` 触发一次真实 Gradle 构建：`BUILD SUCCESSFUL in 9m 16s`，
产出 `leanback-arm64_v8a-oktv-beta.apk`（≈49.5 MB，v2 签名、完整性校验通过），
发布于 Release `v5.6.0-beta-202611120847`，APK 可下载。
Run 整体 `failure` 仅因末尾 `Upload workflow artifacts` 触发 GitHub Actions
制品存储配额上限，与编译/签名/发布无关。详见 `CI真实构建_结果报告.md`。
本地沙箱无 Android SDK 的限制不变；本节第 2 部分的本地构建命令仍适用于本地自行出包。

## 2. 本地完整构建（需要你的环境）

沙箱没有 Android SDK / JDK 21 / Gradle 9.5.1 / NDK 29，**无法产出 APK**。请在本地：

```bash
# 环境要求（与仓库 gradle/libs.versions.toml 一致）
#   JDK 21 / Android SDK platform 37 / NDK 29.0.14206865 / Gradle 9.5.1 (wrapper)
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21

git clone -b oktv-cat-hiker-entry https://github.com/kuby007/xbox-re.git
cd xbox-re
./gradlew :app:assembleLeanbackDebug        # 或 assembleLeanbackRelease
# mobile flavor 同样可用：./gradlew :app:assembleMobileDebug
```

`app/build.gradle` **无需改动**：新增类在 `app/src/main`，两个 flavor 共用；
`flavorDimensions = ["mode","abi"]` 下所有 variant 自动包含。
签名沿用仓库原有 `hasReleaseSigning` 逻辑，无签名时自动用 debug。

## 3. 沙箱内已完成的构建前置验证

| 验证 | 结果 |
|---|---|
| `ecj + android-all.jar` 真编译 8 个 Java 类 | **0 错误** → 15 个 `.class` → `d8` 生成 44 KB `classes.dex` |
| `@JavascriptInterface` 方法签名核验 | 31 个公开方法全部正确（`javap`） |
| 改动文件静态校验 | **45/45 通过**：只增不删、XML 合法、Java 语法一致、符号引用闭合、assets 引用齐全 |
| 兼容性实证 A/B/C/D1/D2/E | 首页混用 `INCOMPATIBLE`；独立入口 `3/3`；全库同口径 `23/78 → 31/78` |
| 容器页端到端（有桥 / 离线两种形态） | **44/44 断言通过**，页面 JS 异常 0 |
| 所有 JS `node --check` | 通过；兼容层去注释后 ES5 禁用语法 0 命中 |
| **阶段八补桥方法语义验证** | `verify_bridge_api.py` **51/51**：A 纯计算 24 / B 本地 HTTP 网络语义 10 / C QR 与 python-qrcode 逐格一致 6 / D JS 转发链路 12 |

复现：

```bash
cd hiker-entry
python3 verify_bridge_api.py            # 阶段八补桥方法 51/51
./build_verify.sh                       # Java 真编译 + dex
python3 compat_verify.py                # 兼容性 A/B/C/D1/D2/E 实证
python3 verify_entry.py                 # 容器页六段链路 e2e
python3 app/patch/verify_patch.py       # 改动文件静态校验
```

## 4. 兼容性设计（为什么这样加）

见 `../海阔独立入口_兼容性分析与构建前置.md`。核心结论：

1. **不能与首页混用**：首页 `fongmiBridge.invoke` 是异步 Promise，海阔规则要同步字符串
   `fetch/request`；同一 JS 上下文里双方会互相覆盖 `window.fetch`（A 组实证 `INCOMPATIBLE`）。
2. **独立 Activity 的桥必须同步**：`HikerBridge.req()` 在 JavaBridge 线程同步返回 `String`，
   与 v390 `JsReqBridge` / 海阔原版 `fy_bridge_app.fetch` 同语义。
3. **海阔多变靠兼容层兜**：Rhino / Python / 加密规则被 `HikerCompat.classify()` 识别并显式降级，
   不静默假成功；`$` 风格、`MY_*` 每页变量、`hiker://` 路由由 shim + JsT4 补齐。
4. **两套横按钮都注册**：`Func`（leanback 实际渲染）+ `HomeButton`（可配置按钮组），
   后者用 `insertMissing()` 自动补齐旧用户偏好串，**升级后不丢按钮、不错位**。

## 5. 已知边界

| 项 | 说明 |
|---|---|
| 完整 APK | 沙箱未产出，需本地按第 2 节构建 |
| mobile flavor 入口 | `Func` / `HomeButton` 是 leanback（TV 版）特有体系，本版入口按钮改动落在 leanback；`HikerActivity`、桥、assets、资源都在 `main` 两个 flavor 共用。若也要在 mobile 首页加入口，需在 `app/src/mobile/.../HomeActivity.java` 自行加一行 `HikerEntry.start(this)`（独立模块本身无需改动） |
| `bridge` 档 25 个 API | 阶段八已补 21 个 Java 真实现 + 1 个 JS 层 CryptoJS 兼容对象（MD5/AES 走 Java 桥）；`fetchCodeByWebView`/`x5Rule`/`parseLazyRuleAsync` 3 个内核依赖仍明确降级 |
| Rhino / Python / 加密规则 | 兼容层只做识别与降级标记；真正执行需 Java 侧引擎（阶段五 P2），仓库未包含 |
| 覆盖率 31/78 | 离线 stub HTML 下的数字，真机需回归 |
| 真机最小探针 | 未做（独立 Activity 的 `addJavascriptInterface` 在真机 WebView 上的行为需过一遍） |
| `homePage` / 点播配置 | **刻意不改**，本版完全不依赖 WebHome 首页 |
| 回滚 | 删除 `hiker/` 包与 `assets/hiker/`，并还原 4 处插入即可；`oktv-cat` 分支本身未动 |
