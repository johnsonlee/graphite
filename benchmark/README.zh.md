# Graphite benchmark 控制器

[English](README.md) · 简体中文

`graphite-benchmark` 是 Cargo 工作区中仅供开发使用的二进制程序。它在同一台机器上对 base/head
执行相同的工作负载，并将统一的结果模型输出为 JSON、Markdown 和 HTML。
正确性、覆盖完整性和 OOM 失败会阻止 PR 通过；耗时差异不会。
夹具身份、堆上限、采样次数、并发度和验收规则均不支持通过 CLI 或环境变量覆盖；
修改协议必须修改经过审查的 Rust/JMH 源码。

## 执行流程

`run` 负责完整流程；workflow 无需拼装各阶段输入，也不在命令之间传递源码 SHA。
控制器会在安装可信测试框架之前读取两侧已提交的身份。内部包含以下阶段：

1. 准备阶段使用同一套可信前端测试框架，构建产品的 merge-base 和精确的 PR head。
   Gradle 解析四个固定版本的真实 JAR；控制器校验其 SHA256 和文件大小。
   编译器及构建工具的耗时属于准备开销，不计为图构建性能。
2. JVM 阶段通过真正的 JMH 执行 `Fixture64BuildBenchmark`：每个版本启动一个 fork，
   不预热，执行一次 single-shot 调用。该调用将四个 JAR 各分成 16 片，构建并保存 64 个独立图，
   保留原有的资源、语义、持久化索引和回读断言。它从不恢复已保存图的缓存。
   独立的 JMH fork 还会构建并保存完整的 Tika、Hive 和 Kotlin 编译器 JAR，
   保留历史节点数、边数、方法数、调用点数以及映射回读断言。
   这些完整 JAR 用例仅用于正确性和 OOM gate；其单图耗时不是性能证据，不出现在对比表中。
3. 原生阶段用各版本自己保存的图启动对应版本的生产 Rust server。
   它检查源文件及分片身份一致，完整读取并校验响应，然后执行固定的 real64 压测方案。
   查询及完整结果必须一致，除非可信控制器中已事先批准一项精确的语义变更。
   不使用 JVM 查询 benchmark 代替 Rust server 的测量。
   另一个独立的 real64 server 检查四个同时执行的请求、第五个请求被拒绝、受上限约束的服务端超时、
   许可释放、非法输入及精确的恢复结果。这些正确性探针不贡献任何耗时样本。
4. 报告阶段要求同一对 base/head 恰好有一份前端结果和一份原生结果。
   缺失、取消或失败的工作不能视为 PASS。耗时条目始终为 `REPORT_ONLY`。

JMH 启动器及其 fork 对 JAR 夹具明确使用 G1 和 4 GiB 堆。
Java agent 检查实际的 `MemoryMXBean.getHeapMemoryUsage().getMax()`，而非只检查命令行文本。
APK 的固定策略是 8 GiB；当前 real64 工作负载由 Android-all、Tika、Hive 和 Kotlin 编译器的
**JAR** 组成，不包含 APK。APK 夹具发布及 APK 工作负载接入不属于本次变更。
Linux cgroup OOM 计数器及进程退出状态补充 JVM 堆检查。宿主机 OOM 表示运行失败，
不代表配置的 Java 堆上限被突破。发生 OOM 后不会增大堆重试。
`-XX:+ExitOnOutOfMemoryError` 有意使 benchmark 比生产环境的 SootUp 恢复处理更严格：
即使某次 OOM 在生产环境中可以被捕获并跳过，也会终止 benchmark 并使 gate 失败。
固定使用 G1 可让启动器及 fork 的有效堆检查保持一致。

## 运行

使用三个专用 checkout：产品 base、产品 head，以及可信控制器/测试框架。
`run` 将可信 JMH 源码及共享的完整 JAR 断言覆盖安装到两个产品 checkout 中，
产品实现仍分别来自各自的版本。源码 commit/tree、产品内容身份、可信测试框架版本及已安装文件的哈希
记录在 `<output>/prepared/preparation.json` 中。
从可信 checkout 构建 CLI，在它的父目录运行准备过程，确保输出不会修改可信源码树。

```sh
cd /work
cargo build --release --locked --manifest-path trusted/Cargo.toml -p graphite-benchmark
/work/trusted/target/release/graphite-benchmark run \
  --base-source /work/base --head-source /work/head --harness-source /work/trusted \
  --output /work/benchmark-run

# 从保留的同一份证据重新生成报告，也适用于运行中断之后。
/work/trusted/target/release/graphite-benchmark report --run /work/benchmark-run
```

托管验收环境为 Linux、cgroup v2、JDK 17 和 Rust。
其他平台的本地诊断运行不能提供 Linux 宿主机 OOM 证据。
失败时会保留输出；再次运行必须使用全新的输出目录。
目录包含 `prepared/`、`results/` 和 `report/`；`execution.json` 记录完成状态及编排错误。
准备失败、完成证据缺失或任一 suite 失败，都会使最终报告失败。
workflow 始终在单独的步骤中从保留的证据重新生成报告，避免控制器被终止后意外生成通过报告。
不需要签名密钥、服务或独立 benchmark 机器。

## 计时协议

当前主分支的查询目录包含 73 个多图用例。每个版本都使用全新 server，
执行一轮完整的 oracle 校验，每个用例预热两次，再以四个 worker 为每个用例执行二十次测量请求。
请求按轮次交错执行。压测 p50 低于 100 ms 的用例会额外执行四十次独立请求；
这是固定的采样规则，不是通过阈值。独立请求与并发结果分开报告。
如果只有一个版本符合独立采样条件，报告不会编造配对差值。

每个用例分别报告按 nearest-rank 方法计算的 p50/p95，不合并不同查询用例的样本。
延迟边界止于完整 HTTP 响应体读取完毕；响应校验、哈希计算和产物写入保留为该延迟之外的客户端周期诊断数据。
分别报告 registry-ready 耗时，以及从启动到首轮完整 oracle 校验结束的耗时，让延迟初始化成本保持可见。
JMH 构建结果包含完整的可用图保存及回读边界。

每项对比都展示 base/head 毫秒值、绝对差值、百分比、方法及样本数。
基准值为零时，百分比变化没有定义。缺失或不可比较的值标为 `UNAVAILABLE`，绝不记为零。
托管 runner 的耗时是受环境噪声影响的观测值，不能证明性能回归验收通过或性能已经恢复。

## GitHub 集成

`benchmark.yml` 从 PR 目标分支已记录的 base tip 构建并测试控制器，
包括该版本的 Cargo manifest/lockfile、查询目录、夹具哈希及前端测试框架。
产品对比使用 `git merge-base(targetBaseTip, head)` 与精确 head，
避免 main 上无关的已合入变更在尚未 rebase 的 PR 中被误认为回归。
这些身份彼此不同：报告发布器通过 GitHub compare API 独立获取 merge-base，
仅使用目标分支的 base tip 排除过时运行。

PR #179 有一项范围受限的引导例外，因为其目标 base 早于此控制器：
workflow 固定一个经过审查的 snapshot SHA 字面值，并要求仓库、PR 编号及精确的旧 base SHA 均匹配。
它绝不将不断变化的 PR head 选为控制器来源。迁移之后，普通 PR 使用其目标 base 的控制器。
候选版本的测试框架变更由常规 Rust/JVM CI 检查，只有合入 base 后才会在必需的对比中生效。
这防止功能 PR 通过修改 `benchmark/` 或前端测试框架来改变自己的 benchmark 策略。
workflow 变更仍需所有者审查。这不是针对恶意构建脚本的沙箱，也不声称提供执行过程的密码学证明。

workflow 执行配对流程。现有必需检查名 `benchmark-regression-gate` 保持不变，以兼容分支保护，
但它仅依据正确性和 OOM 作出判断。现有 JVM 单元测试、原生测试及差分检查仍由
`build.yml` 和 `rust.yml` 执行。

`benchmark-comment.yml` 从可信默认分支 workflow 使用固定版本的 Bun 运行时执行
`.github/scripts/benchmark/comment.ts`。CI 辅助脚本按职责组织在
[`.github/scripts/`](../.github/scripts/README.zh.md) 下；benchmark 执行仍由 Rust 负责。
它将产物当作数据读取，并更新一条由隐藏标记识别的 bot 评论。
更新前会检查当前 PR head/base 以及 run/attempt 身份，避免过时运行覆盖最新报告。
持有写入 token 时不执行任何 PR checkout 的代码。
PR 评论使用 `summary.md`；`report.md`、`report.json`、`report.html`、原始请求及日志均保留为产物。

原有的定时、主分支及 release tag 性能 workflow，以及它们的比较脚本均已退役。
`backend/bench/` 中的历史测量及优化日志保持不变；原脚本可从 Git 历史中获取。
最近发布版本的对比、本地证明及私有夹具分发暂不实施。

共享 JVM 校验代码位于 `benchmark/jvm/`：`src/main/kotlin` 包含前端 JUnit 和 JMH
复用的完整语料断言，`src/main/java` 包含 fork JVM 的堆检查及 CLI 默认配置检查。
两项 Java 检查都通过进程内的 MXBean 读取实际生效的 JVM 配置。
CLI 检查通过 `Launcher-Agent-Class` 附加到一个临时前端 JAR，
保留真实的 main 方法及干净环境下的默认执行路径；无需生成 Java 包装器或观察脚本。
`jmh-validation.init.gradle` 将可信的共享 Kotlin 源码接入被测产品版本，包括旧版本。
前端 JMH 适配器仍与前端实现放在一起。这些校验源码不会被打包进应用 JAR。

## 有意的语义变更

默认要求查询及完整的带类型响应精确相等，包括顺序、重复项、字段缺失与 null 的区别，以及来源信息。
有意的节点计数、schema 或查询修复可以通过先批准、再复测的审查流程接入：

1. 执行功能变更的对比，保留完整响应体和 `semantic-transitions.json`。
   独立核验预期行为，记录两个产品内容身份、case ID，以及两侧查询和响应的 SHA-256。
2. 先在可信目标分支的 `src/transitions.rs` 中合入带理由的、按字面值精确指定方向的预期。
   对于完整 JAR 的图结构变化，在可信共享 Kotlin 测试框架的 `LargeCorpusBaselines` 中
   添加精确的 `(productIdentity, corpus)` 计数预期。
   夹具哈希、类数量、回读及语义断言仍为强制要求。
3. 按严格分支保护要求，将功能分支更新到最新目标分支后重新运行对比。
   仅含策略变更的 merge/rebase 会保持两个产品身份不变；
   修改产品内容则需要新的、经过独立审查的预期。

`prepare` 在任何覆盖安装之前，基于已提交的 Git 对象计算产品身份：
对域 `graphite-product-identity-v2\0` 及已排序、以 NUL 结尾的 `git ls-tree` 记录
（路径、文件模式、对象类型及对象 ID）计算 SHA-256。
仅排除两个承载策略的路径：`benchmark/src/transitions.rs` 和
`benchmark/jvm/src/main/kotlin/io/johnsonlee/graphite/webgraph/LargeCorpusValidation.kt`。
它们始终由可信控制器/测试框架提供，而不是来自候选产品；
必需的 JAR 校验器也会阻止共享 Kotlin 断言进入应用 JAR。
它们的完整 Git 身份及安装哈希仍保留在准备阶段证据中。
产品源码、构建文件及 lockfile、夹具身份、查询目录，以及所有其他路径都纳入内容身份。
将纯批准性提交限制在这两个路径内，可避免批准记录引用自身哈希；
目标分支的无关变更则需要重新批准。

产品身份 v2 使用上述迁移后的策略路径。历史 v1 报告保留原身份，
不得将其重新解释为 v2 身份。旧前端策略路径现在与其他非策略文件一样纳入身份计算。

两张批准表初始均为空。通配符、CLI/环境变量覆盖，以及候选版本持有的预期文件都不能授权差异。
额外变化的用例，或预期查询/结果发生变化，都会导致失败。
已批准的语义变更可以通过正确性检查，但查询或响应不同会使原生耗时差值标为 `UNAVAILABLE`；
原始值及原因仍然可见。完整的 base/head commit 和 tree 身份另行保留，用于追溯来源。

## 取消与工作预算的正确性

生产 Rust server 实施并发限制和截止时间；已废弃的工作预算选项会被忽略，
客户端断连也尚未接入 Cypher 取消机制。原生探针测试实际的 Rust 契约，不断言其并不具备的能力。
现有 JVM 契约仍由必需的 `build.yml` 单元测试 job 覆盖：

- `CypherClientCancellationTest`：真实 TCP 断连会停止工作并恢复容量。
- `CypherCancellationContractTest` 和 `CypherQueryGuardTest`：执行及序列化期间的取消、
  负载下的拒绝、许可释放，以及后续执行的所有权。
- `MappedCypherBudgetTest`：持久化映射图的扫描及索引操作会耗尽预算。
- `ExploreCommandTest`：真实 HTTP 路由返回 `429/cypher_work_budget_exceeded`，随后接受恢复请求。

已删除的 `cypher-capacity` JMH job 原本仅供参考。这些行为测试仍为必需检查；
JVM 查询耗时不能替代 Rust 多图报告。
