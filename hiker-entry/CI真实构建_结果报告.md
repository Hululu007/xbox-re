# CI 真实构建 · 结果报告

> 时间：2026-11-12 08:44–08:57（GMT+8）· 分支：`oktv-cat-hiker-v3`
> Run：[43129445593](https://github.com/kuby007/xbox-re/actions/runs/43129445593)

## 一句话结论

**真正触发了 Gradle 构建并产出了可安装的 APK**。Gradle `BUILD SUCCESSFUL in 9m 16s`，
签名 Release 资产 `leanback-arm64_v8a-oktv-beta.apk`（51,925,347 B）已上传且可下载。
Run 整体标记 `failure` 只是因为**最后一步上传 CI 临时制品时 GitHub Actions 存储配额用尽**，与代码/构建/签名/发布无关。

## 1. 触发参数（最小副作用，已审）

| 参数 | 值 | 目的 |
|---|---|---|
| `ref` | `oktv-cat-hiker-v3` | 确保 checkout 到含海阔模块的代码（`head_sha=2478f9bb`） |
| `release_channel` | `beta` | 预发布，不污染 stable |
| `prerelease` | `true` | 明确标记预发布 |
| `apk_variants` | `leanback-arm64_v8a` | 单变体，先验证链路 |
| `sync_cnb` / `publish_oci` | 不传（默认关/早退） | 不向 CNB/OCI 外部推送 |
| `release_tag` | 留空自动生成 | `v5.6.0-beta-202611120847` |

`dispatch` 返回 `204`（已接受），随后 run 创建并 `in_progress`，Checkout 成功。

## 2. 31 个步骤结果

| 阶段 | 关键步骤 | 结论 |
|---|---|---|
| 环境 | Checkout / JDK21 / Python3.10 / Android SDK / NDK+CMake | ✅ 全 success |
| 门禁 | `Verify mobile search scope feature`（22 条 grep） | ✅ success |
| 测试 | `Run WebSocket danmaku tests` | ✅ success |
| 发布准备 | Resolve tag / Configure release signing | ✅ success（4 个 `RELEASE_*` secrets 齐备） |
| **编译** | **`Build release APKs`** | ✅ **`BUILD SUCCESSFUL in 9m 16s`（214 tasks）** |
| 收产物 | Collect artifacts | ✅ success |
| **签名** | `Sign APK with v2 only` | ✅ v2 验签 true、ZIP 1891→1891 零改写、`zipalign -c 4 OK` |
| **校验** | 包身份 / 签名 / 完整性与可安装性 | ✅ 全 OK（`resources.arsc Stored`、`lib/`、`assets/` 齐备） |
| OCI | Set up ORAS / Publish to OCI | ✅ 脚本因缺配置早退，未外推 |
| **发布** | **`Create GitHub Release`** | ✅ **Release created，3 个资产全部 uploaded** |
| 附注 | Attach signature evidence | ✅ success |
| CNB | Sync to CNB | ⏭ skipped（默认关） |
| 末尾 | `Upload workflow artifacts` | ❌ **failure（配额，见下）** |

## 3. 唯一失败项的真实原因

`Upload workflow artifacts` 日志：

```
With the provided path, there will be 4 files uploaded
Artifact name is valid!  Root directory input is valid!
##[error]Failed to CreateArtifact: Artifact storage quota has been hit.
Unable to upload any new artifacts. Usage is recalculated every 6-12 hours.
```

- 这是 **GitHub Actions 制品存储配额耗尽**（本仓库近期 107 次构建积累了大量 30 天保留制品），属账户级资源限制；
- 该步骤是 `if: always()` 的**收尾附加动作**，不是交付路径；产物在此之前已通过 `Create GitHub Release` 上传到 Release 资产；
- 因此 **APK 未受影响**：Release 资产 API 返回 `302` 重定向到对象存储，可正常下载。

## 4. 交付物（已可下载）

**Release**：https://github.com/kuby007/xbox-re/releases/tag/v5.6.0-beta-202611120847

| 资产 | 大小 | 说明 |
|---|---|---|
| `leanback-arm64_v8a-oktv-beta.apk` | 51,925,347 B (≈49.5 MB) | **可安装 APK**，v2 签名，leanback(TV)/arm64 |
| `leanback-arm64_v8a-oktv-beta.json` | 708 B | 更新清单 |
| `signature-report.txt` | 9,345 B | 签名与完整性自检报告 |

签名证书：`SHA-1 4d804656449ffb260b244558950658d56cc652ac` / `MD5 e9af038921952291e9e7c31ce73c2a8c`。

## 5. 与之前结论的修正

- 之前「沙箱打不出 APK」**仍然成立**：本地沙箱确实无 Android SDK/JDK21/Gradle 9.5.1；
- 但**不能据此说"没有出包"**：本次通过仓库自带 `Android Release` 工作流在 GitHub 托管 runner 上完成了真实构建，**APK 已产出**；
- 之前「v3 分支 0 次构建」也已改变：现在有 1 次 `workflow_dispatch` run（本次）。

## 6. 诚实边界（不变）

- 这是 **leanback / arm64-v8a 单变体**；mobile 与 armeabi-v7a 变体未构建；
- 本次构建验证的是**编译/打包/签名链路**，不等于海阔规则在真机上全量回归；3 个内核依赖 API 降级、4 个 degrade 档、Rhino/Python/私有加密规则仍只识别不执行；
- **真机最小探针**（`addJavascriptInterface` + 一次同步 `req`）与全库真机回归仍未做——这是"当海阔完整可用版"发布前的最后一项 P0；
- `oktv-cat` 等 4 个既有分支 HEAD 未改动；本次新增 1 个 beta Release + 1 个 tag + 1 次 run。

## 7. 如需消除那个红色 failure

三选一（都不影响已产出的 APK）：
1. 在仓库 Settings 清理旧的 workflow artifacts / 等待配额 6–12 小时自动重算后重跑同一 dispatch（换新的时间戳 tag）；
2. 把工作流末尾 `Upload workflow artifacts` 改为 `continue-on-error: true`（配额满时只警告）；
3. 直接忽略——Release 资产才是正式交付物，CI 临时制品不是。

## 8. 凭据

本次 token 仅在进程内注入、临时区文件已销毁、未写入任何交付文件；此前明文贴过的两个 token 仍建议撤销/轮换。
