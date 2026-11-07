# WebHTV Agent Contract

This file applies to the whole repository. Keep work correct, narrow, reversible, and fast. A nested `AGENTS.md` may add path-specific rules but may not weaken the safety, scope, or rollback rules here.

## 1. Start with a bounded lane

Before the first edit, state one completion sentence, the allowed paths, protected pre-existing dirty paths, the current local time and timezone, a realistic total-duration estimate with expected finish time, and the cheapest decisive verification. For multi-phase work, give a short estimate for each phase. Treat these estimates as execution targets rather than guard gates; when the estimate is reached or slips materially, stop optional work, state the cause, and continue with the narrowest completion path or a materially different shortest route. Do not let a stale estimate justify repeated checks, open-ended research, or an unfinished handoff. Use the smallest applicable lane:

Estimate elapsed wall-clock time for the current Codex agent in this workspace, not the time a human engineer or team would need. Base the estimate on the actual repository state, available tools, warm caches, ABI/build scope, device availability, and unavoidable network/build/user wait; split those phases when they materially differ and give the expected local finish time. Do not quote person-days or staffing estimates unless the user explicitly asks for them.

### Governance-maintenance fast path

When the task only edits `AGENTS.md`, `.codex/skills/**`, `.codex/scripts/**`, or their review document:

- Diagnose from the user's observed failure and the current diff only. Do not search the web, reread general best-practice sources, forward-test, create a temporary repository, or expand the methodology unless the user explicitly asks or one concrete unresolved fact blocks the edit.
- Select one root cause, apply one bounded patch, then run exactly one combined validation pass covering only changed artifacts. If it passes, stop immediately and hand off; do not perform reassurance checks.
- Do not update the same rule in every layer by default. Put the decision rule in `AGENTS.md`, domain-only detail in the Skill, and deterministic behavior in the script. Update another layer only when its behavior would otherwise contradict the fix.
- The repository task guard is not required for maintenance of the guard itself or its instruction files. Preserve unrelated dirty files and do not commit/tag unless the user explicitly requests it or this maintenance is already an isolated task-owned change.
- Maximum normal tool sequence: one inspection call, one patch call, one combined validation call. A fourth call requires a failed validation or a concrete blocker, and the reason must be stated before running it.

- A small bug is `quick-fix` by default. Do not promote it to architecture, broad research, or native work merely because more investigation is possible.
- Lane names describe risk and workflow only. They do not impose elapsed-time, changed-file, cycle-count, checkpoint, or replan gates.
- Optimize for shortest elapsed time by removing redundant exploration, repeated commands, speculative scope, and low-value validation. Never gain speed by dropping required behavior, risk-driven verification, rollback, or the requested completion target.
- Under a user-stated time constraint, never repeat a successful or otherwise conclusive check, and never expand research after the available evidence can decide the approved implementation. A retry requires a relevant edit or an inconclusive result; broader research requires one named unresolved fact that can materially change the decision.
- When the user states a time budget or deadline, treat it as the execution target for the current turn and reserve roughly the final quarter for verification, documentation, commit, tag, and handoff. Choose one shortest evidence-backed route, run each expensive build/test at most once unless a relevant edit or an inconclusive result requires a retry, and stop optional research or cleanup before the budget is exhausted. Keep all code, lock, artifact, and task-document changes for one approved unit in one guard session and one commit when possible; only make a second documentation-only closure commit when an unavoidable post-commit ID/tag must be recorded, with no extra build or research pass. This routing rule never lowers correctness, rollback, or completion requirements.
- Classify command failures before asking for approval: a repository file-mode or invocation error is fixed by using the correct in-scope invocation once (for example `bash ./gradlew`), while sandbox/network/permission errors are the cases that warrant an escalation request. Do not spend a second attempt or approval round on the wrong failure class.
- Do not widen declared behavior or paths without explicit user approval. Split genuinely large work into independently useful units while preserving the original completion target.
- Except for the governance-maintenance fast path above, run `bash .codex/scripts/task_guard.sh start --id <task-id> --mode <lane> --scope <path>...` before code edits. `check` is an optional read-only safety audit; it is not required after each cycle, and `finish` performs the final safety check itself.
- A timeout may bound one potentially hung command; it must not bound the task. Diagnose the timeout and continue with a safer, narrower, or resumable command.

## 2. Scope is closed by default

- Modify only the declared paths and behavior needed for the completion sentence. No unrelated refactor, cleanup, formatting sweep, dependency upgrade, generated-file churn, or speculative abstraction.
- A failing unrelated test, warning, or nearby defect is not part of the task. Report it; do not fix it unless it blocks the requested result and the user approves the expansion.
- Preserve every file dirty before task start. Never include an initially dirty path in task scope or a task commit unless the user explicitly assigns that work and it has been isolated safely.
- If the proposed fix crosses a new module, changes public behavior/API, or changes dependency or binary ownership, narrow it to the smallest useful unit. Ask the user only when that unit expands the authorized behavior or paths.

## 3. Search and diagnosis must earn their cost

- For a bug, follow: reproduce or establish evidence -> inspect the exact path and callers -> form one falsifiable cause -> make the smallest fix -> run one targeted verification.
- Search locally first with exact symbols/errors. Internet, upstream history, papers, broad issue searches, and whole-repository archaeology are escalation steps, not defaults for `quick-fix`.
- Expand search only when the current evidence cannot decide the next action. Use at most two query reformulations for a quick fix and three for one material upstream question; prefer primary code/docs/issues over repetitive posts.
- Capture a command's full output once and filter/read that saved result. Do not rerun the same test or build with different output filters.
- Run each final syntax/lint/test check once. Repeat it only after a relevant edit or when the first result is inconclusive; never re-check an unchanged successful result for reassurance.
- After two failed attempts with the same hypothesis, discard it and choose a materially different path. Repetition without new evidence is prohibited; task completion remains required.

## 4. Verification is risk-based and minimal

- Start with the cheapest deterministic check that can falsify the change: focused unit test, compilation target, static check, or one representative device scenario.
- One conclusive pass is enough unless the change concerns flakiness, concurrency, performance variance, native lifecycle, ABI, or device-specific behavior.
- Do not run full Gradle matrices, all ABIs, native rebuilds, broad device suites, fuzzing, or unrelated tests unless the changed contract requires them or the user requests them.
- Do not weaken a failing gate. Classify the failure as regression, environment, or stale expectation. Expand work only if fixing it is within the declared scope.
- Quality floor: do not trade away existing behavior, correctness, security, compatibility, material performance, or task completion merely to meet the clock. Time pressure removes redundant work; it never authorizes an unverified shortcut.

## 5. Context recovery

- Create a durable checkpoint only when a task is genuinely likely to cross a session/compaction boundary, before a risky long-running operation, or when changing functional stage/repository. Never require it because of elapsed minutes, cycle count, or changed-file count.
- A checkpoint records objective, lane/scope, branch/HEAD, protected dirty paths, completed evidence, files changed, verification/result, unresolved risk, rollback anchor, and exactly one next action.
- For long-lived work, update the relevant tracked document with `apply_patch`; the task guard does not own or force progress checkpoints.
- For a task expected to survive session/compaction, create one task-owned `docs/<task-id>-<slug>.md`. Keep a short top or tail `Recovery anchor` containing the objective and acceptance criteria, plan status, current files/symbols, completed actions and results, unverified worktree edits, unresolved risks, and exactly one next action.
- Update the recovery anchor when material state changes or immediately before a likely compaction/long-running operation. The conversation plan is transient; the task document is the durable execution state.
- After compaction or resumption, recover in this order: workspace and Git evidence (`git status`, branch/HEAD, scoped diff) -> the latest recovery anchor/task document -> the compaction summary -> older conversation plan. Reconcile discrepancies and preserve existing unverified changes.
- Once recovery is reconciled, execute the documented single next action. Do not restart planning, repeat completed repository-wide searches, reread completed code paths, or discard partial edits unless the workspace or task document proves they are invalid. Never reconstruct exact hashes or decisions from memory.

## 6. Commit and recovery tag are part of code completion

- One task guard session equals one atomic logical change. If a task needs another logical commit, finish this unit and start a new guard session.
- A code change is not complete until its targeted verification is recorded, its task-owned files are committed atomically, and that commit has a unique annotated local recovery tag.
- **Explicit closure fast path:** when the user confirms the tested behavior is acceptable (for example, “没问题”, “通过”, or equivalent) and asks to commit or create a tag, that confirmation closes optional verification for the approved scenario. Stop immediately; do not capture more logs or screenshots, run adjacent-media/device cases, start another build/test, inspect upstream, perform reassurance checks, or broaden documentation before closing.
- After an explicit closure request, the next tool call must perform the closure unless one concrete unknown safety fact makes that impossible. The normal sequence is at most: one minimal patch to the existing task document only when required to record already-known results, then one `task_guard.sh finish` call that commits and tags, then report the commit and tag. Do not run a separate guard `check`, status/diff review, or tag verification because `finish` already enforces scope and tag creation. If the verified change is already committed, create the requested annotated tag immediately without reopening documentation.
- Treat this as a latency-critical execution target: normally complete the requested local commit/tag within 60 seconds and start the tag-producing command in the first possible tool round trip. A direct command failure or genuine safety blocker is the only reason to exceed this target; report and fix only that direct cause. User confirmation never authorizes skipping an actually failed required gate, but a missing optional or neighboring test is not a blocker after the user accepts the observed behavior.
- When the same message also authorizes a push, push only the named/current branch and newly created tag after local closure. Do not fetch, compare, or revalidate first unless the push itself reports a conflict or rejection.
- Finish with `bash .codex/scripts/task_guard.sh finish --verified <evidence> --commit-message <message>`. The script must stage only task-owned paths and create `recovery/<task-id>/<timestamp>`; never hand-stage unrelated dirty work.
- After the commit succeeds, create its recovery tag immediately with one local, non-interactive `git tag -a` command with tag signing disabled. The tag phase should normally finish within 5 seconds; do not insert builds, tests, searches, network calls, repeated diff reviews, or redundant tag checks between commit and tag.
- Treat a successful tag command as sufficient. If it fails, capture the error once, fix its direct cause, and retry only after the command materially changes; never loop an unchanged tag command.
- If verification fails, scope overlaps pre-existing dirty work, or a safe atomic commit cannot be formed, do not commit/tag. Record durable state when needed and report the exact blocker.
- Assessment-only work does not authorize production edits. Tags point only to committed, verified states. Never push commits/tags, move a published tag, rewrite history, or publish artifacts without explicit authorization.

## 7. Best-practice design research

The mandatory design-research gate below applies repository-wide to every material new feature, optimization, architecture, compatibility, security, dependency, or cross-module requirement, as well as every upstream merge candidate. Use the relevant domain Skill when one exists. Simple text/configuration changes and narrowly localized fixes with an already established design are exempt unless the user explicitly requests research.

## 8. Upstream and player dependency work

For FFmpeg, media/Media3/nextlib, Exo, mpv, mpv-android, libplacebo, JNI, native binaries, locks, patches, or binary packaging, read and follow `.codex/skills/upstream-integration-governor/SKILL.md` and only the references it marks for the current lane.

`.codex/skills/upstream-integration-governor/` is the runtime canonical copy for this repository. `webhome-devkit/skills/upstream-integration-governor/` is a reference/distribution mirror for other developers and is not an additional instruction authority or loading path. Whenever the canonical Skill changes, update the mirror in the same governance-maintenance task and validate that the mirrored files are byte-identical; never let the two copies evolve independently.

### New-session routing for the upstream merge plan

- The user does not need to paste a bootstrap prompt. Treat short requests such as `下一个任务`, `继续上游播放器依赖合并计划`, `梳理新的上游更新`, or equivalent wording as sufficient invocation of the upstream Skill. The expected branch for this repository plan is `fongmi-sync`; inspect the actual branch/HEAD and preserve every pre-existing dirty path before any mutation.
- Unless the newest user message explicitly approves implementation, the first response is assessment-only and read-only. Fully read `README.md`, this file, the active `docs/upstream-player-dependency-merge-assessment-*.md`, and only the task documents directly related to the candidate. Use the assessment ledger, Git history, and current code to choose the next genuinely unfinished and unimplemented task in the order `Exo -> MPV -> common`.
- Before proposing work, determine whether another commit already implemented it, partially covered it, superseded it, or made it irrelevant. Recommend `忽略` directly when it is already covered or has no current product value; do not relabel it as new implementation work.
- Explain the user-visible playback capability in ordinary language. Include all related repositories and full 40-character commit IDs, cross-repository relationships, benefits, disadvantages and risks, effects on existing behavior, compatibility, performance and package size, best-practice status, required WebHTV adaptation, a clear `实施`/`暂缓`/`忽略` recommendation, and the smallest staged implementation and verification plan.
- Use this first-response shape and then stop for explicit approval: `任务编号和名称`, `所属分类`, `要实现的实际能力`, `当前项目已有实现`, `涉及仓库及完整 commit ID`, `收益`, `缺点与风险`, `与现有功能的关系`, `建议`, `最小实施步骤`, `预计需要的验证`. Do not modify code, locks, patches, artifacts, or runtime behavior during this selection response.
- A later explicit `开始实施` authorizes only the unambiguous currently recommended task. Treat all existing behavior, compatibility and material performance as acceptance contracts: the implementation must not knowingly cause direct or indirect regressions or reduce current performance. Adapt or reject the upstream design when needed, use the narrowest risk-proportionate verification, and do not claim completion while a material regression risk remains unresolved.

- Assessment and implementation are separate authorities. Enumerate every in-scope upstream commit with a full 40-character ID and a disposition; never implement an unapproved stage.
- Group related cross-repository behavior into independently reversible `Exo`, `MPV`, or `common` stages. Preserve the decision/implementation order `Exo -> MPV`.
- Continue the active assessment from `docs/upstream-player-dependency-merge-assessment-2026-08-20.md`; do not redo completed analysis unless source heads or material evidence changed.
- Resolve every actionable stage to the stable task ID recorded in the assessment index before creating or updating task documentation. Use the existing families `E*`/`E*-*` for Exo, `E-SP*` for Exo performance, `P*`/`P*-*` for MPV, and `C*`/`C*-*` for common work. Never renumber, recycle, or replace an assigned ID with a generic sequence.
- One started upstream task has exactly one durable file named `docs/<TASK-ID>-<slug>.md`. That file owns the task's best-practice research, local-code review, decision, implementation history, repeated fixes, verification, commits/tags, rollback, current status, and next action. Append later work to it; do not create parallel `plan`, `assessment`, `implementation`, `fix`, or dated follow-up files for the same task.
- The assessment document is the sole exception: it remains the cross-task index and exhaustive upstream commit ledger. It links each active task to its unique document but does not replace that document's implementation record.

### Mandatory design-research gate

Before changing code, locks, build scripts, patches, artifacts, or runtime behavior for any upstream candidate or material new requirement, complete a decision-ready best-practice review. A material requirement includes a new user-facing capability or a change to architecture, performance, playback behavior, startup/seek, decoder or renderer selection, network/proxy handling, compatibility, native/binary packaging, public API, security, data ownership, or a cross-module contract.

- Before implementation, create or update the task's unique `docs/<TASK-ID>-<slug>.md` and link it from the assessment index. Its best-practice section is the durable plan; do not create a second `plans/` document or treat a chat response as the record.
- Search every applicable evidence class: exact upstream source/commits/tests; official specifications and platform/project documentation; upstream PRs, issues, reverts, and maintainer discussions; mature related-project code and tests; and relevant papers, technical posts, blogs, benchmarks, or field reports. If a class is genuinely inapplicable, record the reason in the plan rather than silently skipping it.
- Read the actual sources, not search-result snippets. Record URL or repository path, revision/commit, access date, evidence grade, the supported claim, WebHTV applicability, caveats, and the decision impact. Use the configured proxy when network access is needed; if required research cannot be obtained, mark the stage incomplete and do not claim a best-practice conclusion.
- Review the current WebHTV implementation and call/data flow at concrete file and symbol locations. Identify existing equivalent or partial behavior, local safeguards, consumers, build reachability, and any later local fixes before selecting a design.
- The plan must compare at least `no change`, the unmodified upstream approach, and a narrow WebHTV-adapted approach (plus other credible alternatives when relevant). Explicitly decide whether to optimize, correct, supplement, or reject the upstream proposal for this project, and explain the tradeoffs for correctness, compatibility, performance, quality, lifecycle, ABI, security, provenance, maintenance, validation, rollout, and rollback.
- Do not implement until the plan contains a recommendation, acceptance criteria, and rollback path and the user explicitly approves the proposed stage. Keep research bounded to one decision-shaped question at a time: stop when additional sources no longer could change the design or decision, and record the unresolved gate instead of browsing aimlessly.

## 9. Enforcement boundary

`AGENTS.md` is an instruction layer, not a security boundary. The task guard only enforces declared paths, protected initial dirty files, branch/HEAD stability, atomic task commits, and recovery tags when invoked. It does not enforce elapsed time, cycle counts, changed-file limits, checkpoints, replans, or state archives. Git hooks are not the primary mechanism because ordinary clones do not reliably enable tracked hooks and client hooks can be bypassed. CI remains the correct place for organization-wide mandatory checks.


## 10. 构建脚本错误清单（每轮强制预检，不许重犯）

本节记录本仓库 CI 已经踩过的坑。任何改动构建脚本、签名、资源目录或发版步骤之前，必须先逐条对照；dispatch 之前必须完成本节的预检。一条不满足就不许触发构建。

1. **v1 签名只能从「完全未签名的 APK」一次签成**。apksigner 无法给已经带 v2/v3 签名的 APK 追加 v1（即使加 `--v1-signing-enabled true --min-sdk-version 21` 也无效）。minSdk 24 + AGP 9 默认只出 v2/v3；`signingConfigs { enableV1Signing = true }` 实测无效。正确做法：CI 工作副本把 `signingConfig` 置空，让 Gradle 输出未签名 APK，再用 apksigner 一次签 v1+v2+v3。
2. **校验口径**：`apksigner verify --verbose` 在 minSdk 24 的 APK 上会报 `Verified using v1 scheme (JAR signing): false`，即使 META-INF 里确实有 v1 签名。必须用 `apksigner verify --min-sdk-version 21 --verbose`，并且客观统计 `META-INF/*.RSA|DSA|EC|SF` 数量 ≥ 1。不能只看默认的 scheme 行。
3. **失败 annotation 必须单行化**。多行输出写成 `::error` 时只会显示第一行（曾只显示一个「Verifies」，白耗一轮构建）。所有诊断先把换行压成空格再输出，并把 apksigner 全文与 META-INF 数量一起打进 annotation。
4. **新增 Java 引用先查 import**。曾因 `leanback SiteDialog.java` 用了 `R.string.script_reloaded` 却漏 `import com.fongmi.android.tv.R`，编译直接失败。改动后必须逐个核对新增 R 引用与 import。
5. **drawable 禁止跨变体重名**。曾把 `ic_action_check.xml` 同时放进 main 与 mobile 导致资源冲突；新建图标一律用带用途前缀的唯一名（如 `ic_script_check`、`ic_popup_import`），并确认 main / mobile / leanback 三处不重名。
6. **资源目录改名必须同步 workflow 门禁路径**。曾把 `values-zh-rCN` 改成 `values-zh`，workflow 里两行 grep 还指旧路径，`set -e` 直接退出。凡移动/改名 `res/values-*`，同步搜索 `.github/workflows/` 里的硬编码路径。
7. **Gradle 日志要留得下来**：`tee` + `set -o pipefail` + 独立的失败诊断步骤；失败先读 annotation 与日志定位，再改，不盲目重跑。
8. **不检查就出包、重复构建一律禁止**。dispatch 前固定预检：该 HEAD 已有 run 数必须为 0、4 个签名 secrets 齐全、workflow YAML 可解析、源码门禁断言本地模拟全过、tag 不冲突、门禁/校验步骤的关键字齐全。一次 HEAD 只 dispatch 一次；修复构建也不许在同一 HEAD 上重复触发。CI `completed/success` 且 8 个资产齐全之后，才允许报「构建成功」。
9. **CI 全绿 ≠ 用户工具通过**。曾把「apksigner verify 通过 + v1:true + CN 正确」当成验收完成，而测试人员用 MT 管理器看到「签名状态：校验不通过」——方案显示 V1+V2+V3 齐全，说明缺 v1 已修好，但 MT 会逐个校验 `META-INF/` 里的 JAR 签名文件；Gradle 未签名包可能残留第三方 `*.SF/.RSA/.DSA/.EC` 与旧 `MANIFEST.MF`，apksigner 只追加不清理，MT 遇到对不上当前证书的那套就判不通过。**补签前必须先 `zip -d` 清干净所有旧签名条目**；断言从「≥1 个签名文件」收紧为「恰好 1 个签名块 + 无 apksigner WARNING」。
10. **端到端验证不能只靠 CI 自述**。沙盒出网被网关挡住，Release 资产与 Actions artifact 均下载不了（403），所以每轮都必须如实写明「未做端到端验包」。修复方式：CI 生成 `dist/signature-report.txt`（每个 APK 的 META-INF 清单、apksigner verify 全文、证书、sha256）并作为 Release 资产上传，让用户能直接对照 MT 显示的指纹；不得在未声明这一限制的情况下说「签名已验证/完成」。用户工具报错时先问清具体文字与签名方案，不要凭 CI 结果反推「已经修好」。
11. **收紧门禁前先看正常输出长什么样**。曾为「更严谨」加了「拒绝任何 apksigner WARNING」，结果 run `42268600502` 倒在验签步骤：AGP 打的包必然对 `META-INF/app-metadata.properties`、`version-control-info.textproto`、`androidx.*.version` 报 `WARNING: ... not protected by signature`，这是标准提示，与签名有效性无关，MT 也不会因此判不通过。断言必须带白名单（只拦截 `not protected by signature` 之外的 WARNING），不能一刀切。新增任何「全量拒绝型」断言前，先跑一次看真实输出。



12. **搬入上游 Java 必须做「方法级可达性」检查，不能只查 import**。`import` 可达 ≠ 方法存在：第七/八批搬入 44+31 个类后，本地只校验了 import 与 R 引用，漏掉 若干公共类方法 / `Setting.resolveTmdbDetailLightTheme(int,boolean)` / `Util.resetApp()` 三个静态调用，以及 `Setting.clampTmdbDetailTheme(int)` 这个被调私有方法——这些只有到 CI 编译才会报 `cannot find symbol`。规定：每批搬入后，扫描新增 Java 对 `Setting` / `PlayerSetting` / `Prefers` / `Util` / `App` / `ResUtil` / `Notify` / `ImgUtil` / `VodConfig` / `SiteApi` 等公共类的 `Cls.method(` 调用，逐一对齐**当前分支状态**（含同批已补成员）里定义的方法；同时核对跨变体同名类（如 `VideoActivity.start(...)`）的重载参数在两个变体都存在。曾踩：`TmdbPersonDialog` 调 5 参 start、`TmdbNavigation` 调 6 参 start，两变体重载不齐就会编译失败。
13. **APK 变体数与校验断言不得硬编码**。曾把构建任务写死四行 `assemble*Release`、验签步骤写死 `test "$count" -eq 4`；用户只要「手机 v8a + TV v7a」两个包时就必须改 workflow，否则会多构建两个、或倒在该断言上白耗一轮 Actions。构建任务由 `workflow_dispatch` 输入 `apk_variants` 拼装（`all` = 四包，或逗号分隔指定），`expected_apk_count` 写进 `steps.build_apks.outputs`，验签步骤按输出值断言。collect / 补签 / 验签 / manifests / 发版全部用 `dist/*.apk` glob 动态遍历，增减变体不再需要改脚本。
14. **批量删除代码块后必须检查「类闭合 } 之后是否残留方法」**。第十批清 TMDB 时把 `onPersonal` 从类内删走，却在两个设置页文件的 `}` 之后留下 3 行孤儿方法；javac 21 把它当 unnamed class，报 `unnamed class should not have package declaration` / `unnamed classes are a preview feature and are disabled by default`，构建直接失败。每次删除/搬移方法后，全库扫一遍「顶层类闭合行之后仍有非空代码」，并与成功构建的 main 对比排除误报（字符串里的 `{`、多个顶层类、匿名内部类都会让裸括号计数误报）。


### 每次出包前的固定预检清单

- 当前 `main` HEAD 与预期一致，且 `actions/runs?head_sha=<HEAD>` 返回 0
- secrets：`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEY_ALIAS` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD`
- workflow YAML 可解析；关键步骤计数正确（未签名构建、补签、v1 校验、REST 发版）
- 源码门禁断言逐条本地模拟，失败项为 0
- 预计 tag 不存在
- 正式签名 `CN=xbox-re`、applicationId `.xbox` 共存、APK 数量与本次 `apk_variants` 输入一致（默认 4，可指定 2）
- 本轮只 dispatch 一次；轮询到 completed 才下结论
- 断言若用「拒绝任何 WARNING / 任何差异」这种全量口径，必须确认过正常输出里没有该类内容
- `signature-report.txt` 已生成并上传；若资产下载受限，明确告知「未端到端验包」，附证书 SHA256 指纹供用户对照

13. **资源 / import / 方法级可达性校验必须只用「当前分支 tree」**，禁止把上游缓存混进资源池。
    前九批多次报「R 引用 0 缺失」，实为把上游 webhtv 缓存算作已定义；改用分支自身 zipball
    tree 重算后才发现 188 项缺失。每批搬入后必须核对「新增文件 + modified 文件 diff 新增行」
    的全部 `R.x.y` / `@x/y` 引用。另：GitHub compare API 对大文件不返回 patch，
    必须直接取 main 基线内容做本地 diff，不能只看 API patch。
