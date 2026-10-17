# GitHub Actions workflow

[English](README.md) · 简体中文

Workflow 负责组织检查、产物和发布流程。benchmark 执行逻辑位于
[`benchmark/`](../../benchmark/README.zh.md)；TypeScript CI 辅助脚本及其 Bun
版本配置位于 [`.github/scripts/`](../scripts/README.zh.md)。

## Workflow 索引

| Workflow | 触发条件 | 职责 |
| --- | --- | --- |
| [`build.yml`](build.yml) — Run unit tests | 匹配 `*` 的分支 push、目标分支匹配 `**` 的 PR、`repository_dispatch: test` | 检查 TypeScript 辅助脚本，测试 Explorer 网页状态，运行 Gradle 检查，在 PR 上要求应用代码行覆盖率达到 98%，更新覆盖率和 CI 状态评论。 |
| [`rust.yml`](rust.yml) — Rust | 匹配 `*` 的分支 push、目标分支匹配 `**` 的 PR | 检查 Rust 格式、lint、构建和测试；校验 Linux 静态二进制与容器启动；验证真实 JVM 构图、持久化图及 Kotlin/Rust 响应一致性。同时在 Windows 上进行 workspace 编译检查。 |
| [`benchmark.yml`](benchmark.yml) — Benchmark correctness and OOM | 目标分支匹配 `**` 的 PR | 在同一个 runner 上比较产品 merge-base 和精确 PR head。以正确性、执行完整性和 OOM 为 gate，计时差异只生成报告，不设阈值。 |
| [`benchmark-comment.yml`](benchmark-comment.yml) — Update benchmark report comment | Benchmark correctness and OOM 执行完成；仅为 PR 触发的运行发布评论 | 校验报告来源，更新当前 PR head 对应的唯一 benchmark 评论。 |
| [`publish.yml`](publish.yml) — Publish | `v*` 标签 push、`repository_dispatch: publish` 或手动触发 | 构建并发布 release 产物，或通过手动 dry-run 模式演练发布。Job 仅在仓库 owner 为 `johnsonlee` 时运行。 |

上表中的分支模式与 YAML 一致：`*` 不匹配分支名中的斜杠，PR workflow 使用
`**`。Rust workflow 不设路径过滤，因为 JVM/frontend 和 fixture 的变更也可能
影响其集成检查。

## PR 检查与 benchmark 报告

各 correctness workflow 独立运行，job 名称如下：

| Workflow | Check 名称 |
| --- | --- |
| JVM 与 CI 脚本 | `run-unit-test` |
| Rust | `rust-build-test`、`rust-check-windows` |
| Benchmark | `benchmark-harness-tests` → `benchmark-base-head` → `benchmark-regression-gate` |

`rust-check-windows` 运行的是 `cargo check`，不是 Windows 执行测试套件。
`benchmark-regression-gate` 为兼容分支保护而保留原名，但性能变化不决定其结果。
哪些 check 必须通过，由仓库的分支保护或 ruleset 设置管理，不由这些 YAML 文件决定。

`build.yml` 为每个 PR 维护一条带固定标记的 CI 状态评论。首次失败时创建，后续失败
更新其中的日志摘要和运行链接；之后运行成功时，也在同一条评论上标记已恢复。
从未失败的 PR 不会新增状态评论。迁移时会复用已有的 GitHub Actions 失败评论。
发布前会检查当前 PR head 和 run/attempt 顺序，忽略人工评论。同一事件和 PR 的
过时运行会被取消，以减少并发写入。

benchmark workflow 包含三个 job：

1. **Harness：** 解析用于比较的 merge-base，然后从可信目标分支 tip 构建并测试
   控制器，将生成的二进制上传给比较 job。
2. **比较：** 检出 base、head 和同一个可信 harness，再调用一次
   `graphite-benchmark run`。Rust 负责准备、JMH build/save、生产 Rust server
   load/query、源代码身份及产物路径。独立的 `always()` 报告步骤会重新汇总已有
   证据，即使控制器进程被杀死也能执行。
3. **Gate：** 要求两个上游 job 均成功。缺少准备结果、执行不完整、正确性检查失败
   或 OOM 证据异常都不能变成通过的报告。

build/save 使用 JVM frontend 的 JMH harness 验证；load/query 使用生产 Rust
server 和真实 64 图负载。JAR benchmark 的最大 JVM 堆固定为 4 GiB。APK 策略为
8 GiB，但 APK fixture 集成尚未实现，当前四个 fixture 都是 JAR。`rust.yml`
中另有生产 CLI 默认参数检查，通过 Java MXBean 验证其 8 GiB 默认值以及用户
显式指定的 2 GiB 覆盖值。

托管 runner 上的计时是观测结果，不是性能验收。报告比较 base/head 数值，并明确
保留不可用或失败的测量。采样、负载和堆配置的详细说明见
[benchmark 协议](../../benchmark/README.zh.md)。

## 为什么 benchmark 评论使用独立 workflow

`benchmark.yml` 以 `contents: read` 权限执行候选代码。后续 `workflow_run`
评论发布流程使用可信 workflow 代码，具备 `actions: read` 和
`pull-requests: write` 权限。它将下载的报告作为数据处理，不会带着写权限 token
执行 PR checkout 中的代码。独立的发布步骤也支持来自 fork 的 PR。

发布程序在更新带标记的机器人评论前，会检查 run/attempt、比较使用的 merge-base
以及当前 PR 的 head/base。新提交会更新同一条评论，而不是新增评论；过时的运行
不能覆盖较新的结果。产物缺失时会明确显示失败或不可用。评论发布按顺序执行，
不会取消正在发布的任务；同一 PR 的新 benchmark 运行则会取消过时的运行。

评论 workflow 必须先存在于默认分支，才能接收完成事件。初次迁移控制器时，
由于 PR #179 的 base 尚无 Rust 控制器，因此使用范围严格限定的固定快照作为
启动例外。具体条件见 `benchmark.yml`；正常 PR 使用可信目标 base 中的控制器。

## 结果与失败诊断

| 产物或输出 | 内容 | 保留时间 |
| --- | --- | --- |
| Actions job summary | 精简 Markdown benchmark 报告 | 随 workflow run 保留 |
| `benchmark-report-<PR>-<attempt>` | `summary.md`、完整 Markdown/HTML 和 `report.json`；由评论发布程序读取 | 30 天 |
| `benchmark-evidence-<PR>-<attempt>` | 原始 suite/request 数据、日志、堆校验收据、准备阶段身份信息和 `execution.json` | 14 天 |
| `benchmark-controller` | 可信控制器二进制，下游 job 重试时复用 | 30 天 |
| `failed-test-diagnostics` | JVM 测试 XML 与 Gradle 构建输出 | 7 天 |

先查看失败的 job 和报告中的错误，再追查保留的证据。benchmark 被中断时，
缺少完成记录本身就是失败；成功生成报告并不意味着检查通过。本地复现和重新
生成报告的命令见 [benchmark README](../../benchmark/README.zh.md#运行)。

## 发布与 dry-run 行为

`publish.yml` 在发布 job 运行前，先验证以 `v` 开头的 SemVer 标签。验证后执行
Maven 发布及四个平台的 Linux/macOS 二进制构建。release job 收集二进制、构建
JVM JAR、校验产物并生成 Homebrew formula。Docker 和 Homebrew job 使用这些
release 产物。

标签 push 和 `repository_dispatch: publish` 是真实发布路径，会更新 Sonatype、
GitHub Releases 和容器镜像仓库。正式版本还会更新 Homebrew tap；预发布版本
不会更新 tap 或 Docker 的 `latest` 标签。

手动 `workflow_dispatch` 会基于所选 ref 执行 dry-run。例如：

```sh
gh workflow run publish.yml --ref <branch> -f tag=v2.11.4
```

提供的标签用于标记版本，无需实际存在。dry-run 只将 Maven 产物发布到 runner
本地仓库，以 Actions artifact 保存 `release-<tag>`，在本地安装和测试生成的
formula，并以 `push: false` 构建两个镜像架构。它不会发布到 Sonatype、创建
GitHub Release、更新 tap 或推送容器镜像。这些远端发布步骤不在 dry-run 的验证
范围内。

## 维护 workflow

负载配置和验收规则应放在 benchmark 控制器/JVM 代码中，不应作为 workflow 输入。
可复用的 CI 逻辑放在 TypeScript 辅助脚本中；workflow 负责描述依赖关系、环境和
产物传递。

[CODEOWNERS](../CODEOWNERS) 覆盖整个 `.github/`。在仓库设置中保持 owner 审查
和 required check 规则启用，并保留这些规则引用的 check 名称。新增、删除或调整
workflow 职责时，应同步更新本索引。
