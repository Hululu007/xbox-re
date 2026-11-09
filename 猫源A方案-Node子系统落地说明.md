# A 方案落地：`.js.md5` 猫源订阅直填即用

> 2026-11-10 · `catvod-upgrade` 分支 · **不构建、零 dispatch**

## 为什么改

GitHub 实证：`index.js.md5` 是猫爪/MiraPlay/PeekPili/JSTV 一系的**一键订阅**，
不是配置 json。同目录 `index.js` 是 **Node.js CommonJS 整包运行时**
（雪乃 664KB 混淆 + `runInThisContext(exports,require,module,...,process)`；
catbox 1.49MB `globalThis.websiteBundle` + `require`×133），执行后在本机 HTTP 服务
产出 `{video:{sites:[...]}}`。CatVodOpen v1.1.3+ 也是「quickjs 改 nodejs」。
**QuickJS 跑不了，上一版把整包当单站点源的 `JsMd5Interface` 必出空站点，已删除。**

现成实现来自同源仓 `xinyonghuu/Mooyingshi` / `alantang1977/tangtv`
（包名同为 `com.fongmi.android.tv`，带的 `assets/lab/proot/7zz/libc++_shared.so`
与我们实验室那份逐字节一致）。本轮整体移植其 Node 子系统，未自己造轮子。

## 链路

```
新增点播填 .../index.js.md5
 → VodConfig.load 识别 CatSource.isBundle()
 → CatSource.serve() 阻塞等就绪（最长 12min，在 load 后台线程）
 → NodeRuntime.start() → :node 子进程 NodeService
    ① NodeLib.ensure  下载 libnode.so（nodejs-mobile v18.20.4 单 ABI 约 60MB，
       走「关于」页的 GitHub 代理配置，失败回退直连；已装则复用）
    ② NodeBundle.ensure 并行拉 index.js.md5 / index.config.js.md5 → 比对缓存 →
       按需重下 index.js / index.config.js，双校验 + 原子安装 + 失败回滚
    ③ NodeBoot.write 生成 boot.js（注入 catServerFactory / catDartServerPort /
       NODE_PATH / DEV_HTTP_PORT，回写 port 文件）
    ④ NodeBridge.start → dlopen libnode.so → node::Start(boot.js)
    ⑤ 探 /config，CatSource.isConfig() 认准真服务（排除弹幕服务的 401 信封）
 → CatSource.normalize()：扁平站点数组包一层 / `{video:{sites}}` 提上来、
   相对 api 补 `scheme://userinfo@host:port` 基址、错误信封如实抛错
 → 回到老 checkJson / parseConfig / ConfigSanitizer / 代理总闸 / 播放链
```

站点 api 多为 `127.0.0.1:<port>/...` 本机地址，走我们 Server 那条链；
带 userinfo 的接口由 catvod 的 `AuthInterceptor` 处理。

## 本轮改动清单（21 个文件）

**新增 13 个**：`nodejs/` 模块 4 个（`build.gradle`、`CMakeLists.txt`、
`node_bridge.cpp`、`NodeBridge.java`）+ `CatSource.java` + `node/` 下 8 个类
（`NodeBoot` / `NodeBundle` / `NodeLib` / `NodeNotify` / `NodeRangeZip` /
`NodeRuntime` / `NodeService` / `NodeZip`）。裁掉了上游 `NodeDialog`
（依赖我们没有的 binding / builder），进度走通知。

**改 6 个**：`settings.gradle` 加 `include ':nodejs'`；`app/build.gradle` 加
`implementation project(':nodejs')`；Manifest 加 `:node` 进程 Service；
`values/strings.xml` 加 8 个 node_* 文案；`VodConfig` / `LiveConfig` 接线。

**删除 1 个**：`JsMd5Interface.java`（错误实现）。

**复用二进制 2 个**：`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libc++_shared.so`
直接引用实验室已有的 `app/src/<abi>/assets/lab/libc++_shared.so` blob，
尺寸与上游逐字节一致（1374336 / 963028），零上传。

**适配 3 处**：`NodeLib` 桥到我们 `update.GithubProxy.resolve(...).rewrite(...)`
（上游在 `utils` 包且是 `apply()`）；`NodeService` 的 `BuildConfig.APPLICATION_ID`
改 `context.getPackageName()`（AGP 9 不可靠）；`nodejs/build.gradle` 的
`ndkVersion` 由 28.2 对齐到我们 app 的 29.0.14206865，避免 CI 多下一份 NDK。

## 没动

app 名称 / 包名 `com.fongmi.android.oktv` / 版本 5.6.0 / versionCode 560、
workflow（签名链、两变体、apk 命名一字未改）、Manifest 其它声明、
实验室、海阔、TV 代理按钮、`oktvpro` / `main` / `lab-fix`。

## 如实说的风险

1. **首次使用要下约 60MB `libnode.so`**（GitHub Release）。国内直连可能不通，
   已接用户配置的 GitHub 代理 + 失败回退；如果用户没配且直连不通，猫源会明确报
   「Node 运行时不可用」，不会静默空站点。
2. **CI 需要 NDK + cmake 3.22.1 编 `libcnode.so`**。当前 workflow 的 sdkmanager
   只装了 platform-tools / platforms / build-tools；GitHub runner 通常预装，
   但若报缺，补一行 `sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"` 即可，
   **不动签名链**。沙盒无 SDK，本轮未编译验证。
3. **只接点播**。上游 `CatSource.normalize` 只提 `video.sites`，bundle 不产出 lives；
   在直播里填这类地址会明确报错而不是解析出空直播。
4. **NodeRuntime 全局单例**：同时只服务一个 bundle，换源会杀旧 `:node` 进程再起新的；
   点播与直播不能各挂不同猫源。
5. bundle 内部对外发请求由 Node 自己发，**不受 JVM 代理总闸管**；
   libnode / bundle / md5 的下载走 `OkHttp.client()`，总闸亮着生效。
6. 单测（上游 6 个）本轮**未搬**，避免测试源集编译噪音；功能类全在 main。

## 校验

相对基线差异逐项回读；Java 括号平衡、import 可达、R.string / R.drawable 引用、
Manifest 进程声明、`nodejs` 模块 include 与依赖、两 ABI 二进制 blob sha 一致。
**未构建、未 dispatch、run=0。**
