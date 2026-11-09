# 猫爪系 .js.md5 订阅：撤回 Node 方案核实记录

## 结论
放弃「直填 index.js.md5 即出站点」的实现。零下载方案在协议层面不成立，完整支持必须附带约 60MB Node 运行时，已按需求否决。

## GitHub 实证（GitHub API 直读，2026-11-10）
- `Darklessing/catvod/xuenai/` 仅含 `1.txt`、`index.config.js`(17424)、`index.config.js.md5`、`index.js`(664046)、`index.js.md5`；**无任何 json 配置**。
- `index.js` 中 `sites`、`"api"`、`"key"` 出现次数均为 **0**，尾部 `runInThisContext(...)` + `brotliDecompressSync`，站点清单运行时解压生成。
- `index.config.js` 是 CommonJS 配置模块（`module.exports`），内容为网盘 / 弹幕 / 直播源 / 排序，**不是 TVBox sites 清单**。
- `xymn2023/catbox` README 明确并列两类：`index.js.md5` 一键订阅 与 `18+.json`「Json一键订阅--TVbox专用」。
- catbox `index.js`(1494721) 的 `/config` 逻辑：`l.api = hZ + "/" + l.key + "/" + l.type`，站点 api 指向**本地 Node 服务 HTTP 路由**，脱离 Node 进程连地址都不成立。
- catbox `18+.json` 为 942 字节 hex 加密串，非明文 json。

## 保留行为
1. 标准 TVBox / 猫源 JSON 配置地址全链路支持（含 spider `;md5;` 三段式校验）。
2. 填 `.js.md5` / `/index.js` 时由 `CatBundleNotice` 明确报错，不再静默给出空站点列表。
3. 不引入 `:node` 进程、NDK 模块、libnode.so 下载、jniLibs 二进制。

## 约束
- 分支 `catvod-upgrade` 不构建、不出包，等待整体收口。
- 不得再以「静态抠 sites」「QuickJS 打 Node 垫片」方式尝试该协议：站点与 api 均依赖运行时服务。
