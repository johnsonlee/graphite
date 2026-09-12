# Method gate 的测量边界

`MethodDiscoveryCompatibilityBenchmark.methodScenarioGate` 测量整个 HTTP 兼容性验证场景。
它包含查询引擎，也包含参考结果计算、客户端解析和校验。以下记录源码边界；另已完成六份冻结 main 的本地采样，见
[独立样本核对](method-gate-samples/independent-method-sample-summary.zh.md)。参考结果构造占全部 CPU 样本的
31–33%（4-count）和 41–51%（36-or），并非全部 CPU 都在查询引擎内。不能据此解释 Linux CI
失败或将失败认定为噪声。本次没有修改 gate 或阈值。

采样前后的 64 个图文件及运行 JAR 内容哈希已由主任务复核一致，见
[前后输入核对](method-gate-samples/input-after-receipt.json)。独立审计文字中的未补做 posthash
描述其审计范围；主任务随后完成了该检查。

## 哪些工作在计时内

- [ExplorerMemoryBenchmark.kt:376](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L376)
  使用 JMH `SingleShotTime`；[445](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L445)
  的 benchmark 方法包含完整 `measure` 调用。一次操作依次执行 N 个 scoped 请求，再执行四个 corpus 各一个 root 请求，
  所以是 N+4 个 HTTP 请求的总 wall time，不是单个查询延迟。
- [786](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L786)
  的 CPU 起点在读取 before-RSS 之前，终点在 action、after-RSS 和部分计数操作之后。
  [839](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L839)
  读取 JVM 进程 CPU 时间，包含客户端、服务端、引擎、校验，以及同进程中同期执行的 JIT/GC 等线程工作。
- 每个 scoped 请求先在 [521](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L521)
  构造 expected；每个 root 请求在响应之后通过
  [558、645](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L558)
  对全部 services 重算 expected。
  [689](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L689)
  每次都执行 `fixture.all.filter(case.predicate)`，然后 normalize。总计 N+4N=5N 次完整参考列表过滤：
  N=4 时 20 次，N=36 时 180 次；COUNT 也先过滤全列表，再读取命中列表大小。
- [724–747](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L724)
  的 normalize 包含结果规范化、按场景排序、字符串拼接和 SHA256。
  [591、658](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L591)
  的客户端 JSON 解析、schema 校验和结果摘要，以及
  [750–783](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L750)
  的 URL 编码、HTTP 连接、响应读取和断开，也在 action 内。
- Javalin 在同一 JVM 启动。服务端包含请求处理、guard、查询执行及 JSON 响应序列化：
  [ExploreRoutes.kt:468](../../graphite-explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreRoutes.kt#L468)
  调用单图 `CypherExecutor`，
  [664](../../graphite-explore/src/main/kotlin/io/johnsonlee/graphite/cli/ExploreRoutes.kt#L664)
  调用 `CrossGraphCypherExecutor`。这些均在同步 HTTP 客户端等待的范围内。

因此现有指标可描述该完整场景的进程 CPU、总 wall time 和响应字节，不能直接拆成引擎独占 CPU。
RSS 在 [843](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L843)
读取 Linux `VmRSS` 的前后快照，并非峰值；负增量被截为零。无 `/proc/self/status` 时退到
`Runtime.totalMemory()`，该后备值甚至不是进程 RSS。

## 计时外准备与数据复用

[setup:398–432](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L398)
负责图加载、四份完整方法参考列表构造、服务注册、拓扑及 HTTP 服务启动、legacy 路由探测和可选 manifest 写入。
这些工作在 benchmark 计时外；manifest 也会算 expected，但没有缓存 action 中的 expected 计算。
计时外不意味着它们不会影响后续缓存或编译状态，这里没有量化这种影响。

services 按 android、tika、hive、kotlin-compiler 循环注册，同 corpus 共享同一个 fixture 对象，
并加载同一个 persistedGraph 路径。N=36 时是每份 corpus 九个服务 ID，不能称为 36 份独立数据。
[GraphRegistry.kt:129](../../graphite-explore/src/main/kotlin/io/johnsonlee/graphite/cli/GraphRegistry.kt#L129)
仍为每个 ID 分别加载图对象；共享的是输入文件，不是一个注册图对象。

## 可复用的本地四份 corpus

首先核对旧 `graphite-mapped-tuple-evidence.t2461mo1` 的命令：它指向
`pr113-attempt131-ascii.JqgmHw/fixture64/graphs.tsv`，其 manifest 明确是 64 个 class shards，
不能把其中四个 shard 当成 Method gate 的四份完整 corpus。

随后在现有 Method 证据 `/private/tmp/pr113-method-position-local/main-r1.json` 中找到
该 benchmark 实际使用过的四个 JVM 参数。该文件 SHA256 为
`21ed575696918f33d7e34c73bfc1880040dab1e33eb0d9e1a259b2b3e5736d4d`。

| JVM 属性 | 已存在的目录（原命令使用等价的 `/tmp` 路径） |
|---|---|
| `android.graph.path` | `/private/tmp/pr113-method-fixtures/android` |
| `tika.graph.path` | `/private/tmp/pr113-method-fixtures/tika` |
| `hive.graph.path` | `/private/tmp/pr113-method-fixtures/hive` |
| `kotlin.compiler.graph.path` | `/private/tmp/pr113-method-fixtures/kotlin-compiler` |

路径解析定义见 [ExplorerMemoryBenchmark.kt:1716](../../graphite-explore/src/jmh/kotlin/io/johnsonlee/graphite/cli/ExplorerMemoryBenchmark.kt#L1716)。
只读确认四个目录均含已有 nodedata、metadata、nodeindex 等持久化文件；未重建图、未启动 Java。
这次定位提供复用入口，不是对全部文件内容、源 JAR 哈希或与 hosted fixture 等价性的重新认证。
