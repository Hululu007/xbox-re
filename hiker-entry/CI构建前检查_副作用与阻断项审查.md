# CI 构建前检查 · 副作用与阻断项审查

> 时间：2026-11-12 · 目标：在 `oktv-cat-hiker-v3` 分支触发一次**真正的 Gradle 构建**
> 工作流：`Android Release`（workflow id `408000493`）

## 1. 为什么之前"没触发构建"

- 推送走的是 **Git Data API**（blob→tree→commit→create ref），不是 `git push`；
- 仓库两个工作流（`Android Release` / `CNB Release Sync`）的 `on:` **只有 `workflow_dispatch`**（纯手动），没有 `push` / `pull_request`；
- 实证：`oktv-cat-hiker-v3` 分支 `workflow_runs` 总数 **0**；仓库近期 107 次 run 全在 `ui/native-aurora-theme-cat` 等其它分支；
- 本地 `build_verify.sh` 是 `ecj + android-all.jar` 编译 8 个 Java 类 → `d8` 生成 44 KB `classes.dex`，属**编译前置验证**，不是 Gradle APK 构建，未产出任何 APK/AAB。

## 2. 工作流两份定义的差异

| | `main`（默认分支） | `oktv-cat-hiker-v3` |
|---|---|---|
| 行数 | 616 | 823 |
| 独有步骤 | `Add v1 (JAR) signature` | `Ensure NDK and CMake`、`Sign APK with v2 only`、`Verify APK completeness and installability`、`Attach signature evidence to release notes` |

两者 inputs、门禁、签名、Release 逻辑一致。`workflow_dispatch` 经 REST API 带 `ref` 触发时，用该 ref 分支的工作流定义并 checkout 该分支代码——**`ref` 必须是 `oktv-cat-hiker-v3`**，否则构建的是别分支代码、不含海阔模块。

## 3. 阻断项逐条实证（全部排除）

| 检查项 | 结论 |
|---|---|
| 源码门禁 22 条 grep（mobile search-scope / 弹窗窗口参数 / 简繁转换 / RemoteTrustDialog 计数=6） | v3 分支**逐条拉取真实文件校验，0 失败** ✅ |
| 首页入口改动是否在真实路径 | `app/src/leanback/.../HomeActivity.java`、`app/src/main/AndroidManifest.xml` 均含 Hiker ✅ |
| 发布签名 | 仓库 secrets 实配 4 个：`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` / `RELEASE_STORE_PASSWORD`，正好覆盖工作流主用值 ✅ |
| `Configure release signing` 的 `sed` 目标行 `signingConfig = hasReleaseSigning ? ...` | `app/build.gradle` 中存在 ✅ |
| `versionName` / `versionCode` | `5.6.0` / `560` ✅ |
| NDK：工作流装 `28.2.13676358`，`build.gradle` 要 `29.0.14206865` | 安装行带 `\|\| echo "AGP will download"` 兜底，AGP 9.2.1 自动取正确 NDK；107 次历史构建成功，非阻断 |
| 环境一致性 | wrapper `gradle-9.5.1`、JDK 21、compileSdk 37、minSdk 24，与工作流 setup 段一致 |
| tag 冲突 | 工作流自动生成 `v5.6.0-beta-<12位时间戳>`，`git rev-parse` 校验存在性，当前时间不会撞已有 tag |

## 4. 缺失 secrets 的影响（不会导致失败或外推）

仓库 secrets 仅 4 个 `RELEASE_*`；variables 0 个。工作流引用但缺失的项：

- `vars.OCI_REPOSITORY` / `secrets.OCI_USERNAME` / `secrets.OCI_TOKEN` → `.github/scripts/publish-oci-apks.sh` 第 15-16 行 `echo "OCI publishing skipped"; exit 0` 早退；且步骤 `continue-on-error: true`，只产生 warning；
- `secrets.CNB_TOKEN` → `sync_cnb` 默认 false，步骤不执行；
- `secrets.KEYSTORE_BASE64` / `KEY_ALIAS` / `KEY_PASSWORD` → 仅作 `${RELEASE_*:-$KEY_*}` 的 fallback，主用值齐备，不触发。

## 5. 副作用清单（触发后会发生什么）

| 副作用 | 说明 | 缓解 |
|---|---|---|
| 新建 GitHub Release + tag | `v5.6.0-beta-<时间戳>`，带签名 APK 资产 | 用 `beta` 渠道 + `prerelease=true`，标记为预发布 |
| 上传 workflow artifacts | 保留 30 天 | CI 内部，无外部影响 |
| 单元测试 + Gradle assemble | 真实编译整个 app | 这正是本次要验证的目标 |
| OCI registry 推送 | 缺配置自动跳过 | 不传 `publish_oci`，脚本早退 |
| CNB Release 同步 | 默认关闭 | 不传 `sync_cnb` |
| **改动分支 / 基线** | **无** —— 工作流只 checkout + build + 建 Release/tag，不 push 任何分支 | `oktv-cat` 等 4 个既有分支 HEAD 不受影响 |

## 6. 触发参数（最小副作用）

```
POST /repos/kuby007/xbox-re/actions/workflows/408000493/dispatches
ref:    oktv-cat-hiker-v3
inputs: release_channel = beta        （预发布，非 stable）
        prerelease      = true
        apk_variants    = leanback-arm64_v8a   （单变体，先验证链路）
        release_tag     = 留空（自动生成合规时间戳 tag）
        release_notes   = 标注为海阔入口 v3 构建验证
        sync_cnb        = 不传（默认 false）
        publish_oci     = 不传（默认 true，但脚本因缺配置早退）
```

> 说明：boolean 型 input 经 REST API 传字符串可能在 `if:` 中被判 truthy，故对默认即安全的项**不传值**、用工作流默认，避免误开 CNB/OCI 外部写入。

## 7. 结论

**阻断项全部排除，副作用可控且已最小化，允许触发。** 触发后以 run 状态与日志为准，如实回报成功或失败点。
