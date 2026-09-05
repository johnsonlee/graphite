# CallSite executor 调用图与行为边界

本次仅审查源码和既有采样，没有运行 Java、构建或新测量，也没有修改生产实现。审查时 HEAD 为 `d14bc77f625c515c2e5416728e1b07e0554aa67a`；下述三个主要生产文件逐字节等于冻结 main `4e328b0109e13c896b74004823fb049fcb19251a`。这是一份移除池时的影响清单，不是已证实收益的优化提案。

源码简称（以下行号相对这些文件）：

| 简称 | 仓库路径 | SHA-256 |
| --- | --- | --- |
| Q | `graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt` | `9522dff099e2843f32115ae52f01adfb29c14d147dd3b9919daf907181c4f4ae` |
| W | `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedWebGraphBackedGraph.kt` | `9133289c4382b59f4bc880f9de3d8aee0930d449a32f9a487893dabb56255cb7` |
| I | `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndex.kt` | `c1c4f07d818a4a25f712ad25094b30723fa25fa7531f424b2391bba792938a77` |

## 三种池不是同一个层次

| 池 | 构造与容量 | 使用范围与生命周期 |
| --- | --- | --- |
| `directStringExecutor`，`graphite-cypher-scan-N` | Q:152–162，lazy 固定池。容量取 legacy graph 容量与计划 graph worker 数的较大者；查询自己的并行度另受 Q:126–150 限制。 | 跨图 direct-string 工作；不仅限于 CallSite。进程级共享 daemon 池，没有查询结束或 graph close 时的 shutdown。 |
| `callSiteScanExecutor`，`graphite-callsite-scan-N` | W:82–96，lazy 固定池；`graphite.webgraph.callSiteScanParallelism` 限制在 1..NCPU，默认 NCPU。 | 单图 CallSite raw 扫描、索引构建和 trigram 构建/排序。进程级共享 daemon 池，没有 shutdown。 |
| `splitCallSiteExecutors[p]`，`graphite-callsite-segment-N` | I:1973–2000，按 background parallelism 缓存固定 `ThreadPoolExecutor`，无界队列、无 core timeout。 | 多个 graph caller 共用同一个容量键对应的后台池；每次 lookup 的第一个分段通常由 graph caller 执行。不同容量键是不同池，不能把该 map 描述成永远只有一个 executor。没有 eviction/shutdown。 |

`Graph.kt:76–129`（`graphite-core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt`）定义加法分配：默认 graph=floor(NCPU/2)、segment=剩余部分；NCPU=1 时为 1+0。共享 segment 容量不乘以 graph worker 数。这个计划不是整个进程所有查询、legacy 池与 Method 池之和的全局 semaphore。

Q:211–244 的 consumer 同时携带工作计费能力和路径偏好：单 source 通常允许 legacy parallel；小于 40 个 source 且无 override 通常请求 serial storage；大集合/override 使用 split；另外还有 preferred raw、preferred persisted、preferred mapped view。`PreferredPersistedStringIndexGraphWorkBatchConsumer` 同时实现 Serial 与 Parallel（Graph.kt:138）。因此直接替换 consumer 类型可能改变索引加载、预热、缓存保留及 raw/index 选择，远不只是改变执行线程。

另外两个池不属于 CallSite segment 池：`MethodQueryExecutor.kt:24–33` 的 `methodGraphScanPool` 是 Method 专用 ForkJoinPool；`graphite-explore/.../CypherQueryGuard.kt:42–58` 的查询/超时池是 HTTP 查询保护层，后者在 :117–123 才有真正的 shutdown。名称 `graphite-cypher-N` 与 `graphite-cypher-scan-N` 也不同。

## 从查询到每个提交入口

```text
CrossGraph / QueryPipeline 请求线程
  ├─ Q:1060 filtered count → Q:1101 runDirectStringTasks
  ├─ Q:1640 direct rows → Q:1807 fixed workers ordered-until
  │                        Q:1809 ordered-until / Q:1819 ordinary waves
  ├─ Q:2081 parallel DISTINCT → Q:2112 discovery / Q:2129 selected provenance
  ├─ Q:2146 indexed DISTINCT → leading source inline
  │                            Q:2252 ordered discovery / Q:2261 waves
  │                            Q:2286 selected provenance
  └─ Q:3442 ordered rows → Q:3470 runDirectStringTasks
       ↓ Q:2409 / 2479 / 2572 helpers
     directStringExecutor（graph-level；只有普通 helper 在单任务时直接执行）
       ↓ storage lookup + consumer capability
     W / I 的 raw、retained index 或 mapped view 路径
       ├─ caller 执行一个分段 + splitCallSiteExecutors[p] 执行其余分段
       ├─ callSiteScanExecutor 执行 legacy 扫描/构建任务
       └─ serial / 小图 / 不适用路径直接执行
```

上图不是每个查询都会经过的固定链。Q:1986–2019 的 capability、source 数、node type 和 `directStringWorkerActive` 决定是否跨图并行；嵌套 worker 查询与 Q:3458–3459 的 ORDER BY 分支避免再次占用同一 graph pool 而死锁。无标签查询可包含 CallSite 与其他 node type，不能只按显式标签判断影响范围。

下表穷举 W/I 中实际提交到两个 CallSite 池的生产入口（包含被 helper 隐藏的提交）：

| 入口 | 提交位置与线程 | 扫描/归并职责 |
| --- | --- | --- |
| W:481 `parallelRawDistinctCallSiteStringProjection`，由 :406、:442 调用 | :545–550 选池，:626–627 第一个 task inline、其余 tracked 后 submit 到 segment 池。segment=0 时虽构造 legacy completion service，只有一个 inline task，不提交后台任务。 | 至少 4096 nodes，有限 LIMIT 小于 node count；按物理范围扫描，保留每段 DISTINCT，:665–673 按 worker index 合并、全局去重后 LIMIT。 |
| W:1095 `parallelRawCallSiteStringDisjunction`，由 :948、:968 调用 | :1149–1154 选池，:1255 起逐 wave 提交。Split 第一个 inline、其余 segment；非 Split 全部交 legacy 池。 | 原始节点候选，精确字符串集合或确定性匹配状态，物理有序范围/可选 prefix waves；非 Split 还可能捕获完整索引数据。 |
| W:1461 `prepareCallSiteStringIndexInParallel` | :1473 legacy completion service，:1521 `tasks.forEach(completion::submit)`。 | 完整 CallSite node/string-id capture，先预留内存，所有结果完成才 build/publish。入口 W:2094；`GraphStore.kt:956` 在准备配置允许时调用并持久化。 |
| W:1562 `forEachCallSiteStringProperty` | :1571–1584 legacy，每个四属性一个 task；非 Parallel 或单 CPU 则 inline。 | W:1330/:1369 的 CSR property 构造阶段；阶段之间等待完成，不能交叉使用未填完数组。 |
| I:292 `matchingRangesInParallel` | :335 → :2015 `executeSplitCallSiteTasks` → :2025 后台 submit；第一个 inline。 | 字符串精确候选先解析，按 predicate 分段收集 posting ranges，再按 task 顺序组合。 |
| I:411 `matchingCandidateStringIdsInParallel` | :430 → :1958 `executeSplitCallSiteCandidateTasks` → 同一 split helper。 | 分段验证 candidate string IDs；按分段顺序拼接。 |
| I:532 `intersectCandidateStringIdsInParallel` | :581 → 同一 split candidate helper。 | 保留最短候选集合中同时满足其他 trigram ranges 的 IDs，之后仍有精确匹配。 |
| I:2483 `populateCallSiteTrigramMetadataInParallel` | :2520 → :2581 `awaitCallSiteTrigramTasks` → :2583 legacy submit。 | 按 string ranges 生成 signature/count，聚合 posting count。 |
| I:2523 `populateCallSiteTrigramPostingsInParallel` | :2571 → 同一 legacy helper。 | 写入已分配、不重叠的 posting 区间；核对填充总数。 |
| I:2358 `runCallSiteTrigramSortPhase` | :2370 → 同一 legacy helper；:2304 radix sort 每轮调用。 | 8 个 byte pass 的计数及 scatter，各阶段等待完成；临时数组预留和归还在 :2269–2301。 |

最后一个入口不能漏掉：I:652 的 `sortCallSiteTrigramPostings(result, reservation)` 不按 consumer marker 禁止并行；大 posting 仍进 legacy pool。因此仅把查询 consumer 换成 Serial，不足以证明所有 CallSite 池使用都已消失。映射视图的文件验证本身也不能仅凭线程名称归为 executor 调度成本。

## 查询完成与资源释放

Q:2409 的普通 helper 按任务 index 返回结果，并滚动补充 worker；Q:2479 的 ordered-until 只在连续 source 前缀完成时推进，错误也等到该 source 成为下一个结果才传播；Q:2572 的 fixed workers 通过 index/outcome 队列复用 worker。到 LIMIT 后停止的是多余 discovery，不能据此停止仍需合并来源的 DISTINCT provenance。

这三处取消路径使用 Future cancel 加独立完成 latch。取消成功但 task 尚未启动时，必须由 coordinator 完成 latch（Q:2455–2463、:2521–2529、:2623–2629），不能等待一个永远不会执行的 finally。fixed worker 用非中断的 `outcomes.add`（:2600–2605），确保 storage 保留 interrupted flag 后仍能向 coordinator 报告失败。`awaitDirectStringTasks`（:2664）等待所有已启动任务退出，并保留等待阶段的新中断；RuntimeException/Error 直接传播，checked failure 包成 `IllegalStateException("Parallel graph scan failed", cause)`。普通 helper 的单任务 inline 分支本来就没有同样的包装层，不应把所有路径说成一个异常契约。

I:1907–1954 的 split task 有 NEW/RUNNING/FINISHED 状态，取消未启动任务也释放 exited latch；失败后取消后台 Future 并等待 task 真正退出。I:2002 的 active counter 在 Callable finally 中减少，早于 Future 完成通知，避免方法已返回但 metrics 尚显示活跃。

W 的两种 raw scan、完整 index prepare、property phases，以及 I:2581 的 trigram helper采用另一种方式：共享 abort 标志，继续 drain 所有已提交结果；它们不是统一调用 `Future.cancel(true)`。等待期间中断也继续 join，结束时恢复 interrupt 并报告取消；尽量保留原始非 cancellation 失败。未启动任务仍须运行至检查 abort 才完成。串行重写若取消掉这些任务结构，仍必须保证请求返回后没有遗留工作、没有未发布或半发布索引。

`W:1986 close()` 只清索引/cache，映射内存按 JVM 生命周期回收；:2005 `releaseStringPropertyDisjunctionCache()` 与 :2010 `closeCallSiteStringIndex()` 管理 query cache、持久化索引保留、reservation 归还和必要时持久化，不关闭上述池。Q:2230 的零命中 source 释放与 provenance 阶段跳过已知零命中图相互配合。不能把删除 pool 和删除缓存释放混为一项修改。

## 若去掉池，必须保持的行为与现有测试

测试路径简称：G=`graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/GraphStoreTest.kt`；C=`graphite-cypher/src/test/kotlin/io/johnsonlee/graphite/cypher/CrossGraphCypherExecutorTest.kt`；P=`graphite-cypher/src/test/kotlin/io/johnsonlee/graphite/cypher/QueryPipelineTest.kt`。

| 必须保留 | 现有精确测试与位置 |
| --- | --- |
| 图内物理 encounter order、跨图 source order、重叠去重、LIMIT/SKIP 和 ORDER BY 稳定性；物理顺序不是 node id 排序。 | G:2515 `mapped broad disjunction preserves stored order while deduplicating property streams`；G:3392 `bounded mapped CallSite scan uses ordered intra graph workers before index admission`；C:3160 `parallel string scans preserve source order limit and complete provenance`；C:3527 `ordered filtered rows scan graphs in parallel and preserve global order and skip`。 |
| 四个独立关键词跨四属性 OR，完整 miss、selected/null-selected、原值投影、transform/term 状态隔离。 | `ParallelDistinctDisjunctionTest.kt`:21 `four keyword OR keeps every exclusive term and deduplicates overlap in stored order`；:44 `selected tuples filter the OR result without changing its physical order`；:62 `raw and lowercase predicates do not share a match state for the same string`；:83 `different lowercase terms keep separate match states and preserve a complete miss`。该文件独立遍历 graph.nodes 作 oracle；raw-path 和 pool metrics 断言是机制约束，串行方案需明确修改它们，不能删除语义断言。 |
| DISTINCT 填满 LIMIT 后仍收齐 selected rows 的 graphIds；COUNT 的 residual/空图来源；unlabeled 非 CallSite 候选不能消失。 | C:2646 `balanced distinct projection probes only the leading graph before provenance`；C:3114 `wrapped lowercase distinct limit merges provenance across graphs`；C:2842 `parallel residual string predicates keep graph local bindings`；P:799 `parallel indexed distinct projection reuses known rows from every graph`；P:841 `parallel indexed distinct projection injects and verifies graph ids`；P:1195 `parallel filtered counts evaluate residual predicates and distinct expressions`。 |
| shared work budget、按实际访问计费、buffer final flush、预算失败不重放；取消/预算失败与数据不支持的 fallback 分开。 | C:3406 `work tracked filtered counts use budget aware storage aggregation in parallel`；C:2512 `cross graph execution shares one work budget`；G:4238 `buffered graph work does not replay a failed batch`；G:3392 内 :3742 起 budget failure 与 :3776 起 request interruption 子段。W:3106 buffered consumer 先清 pending 再调用 delegate，1024-unit batch/finally flush 不可丢。串行减少未必需要的投机访问时，总 work 可减少；不能保留固定线程倍数的 work 断言冒充语义要求。 |
| 原始失败可见、请求取消及时生效、返回前所有工作结束、排队未启动不会死锁；嵌套查询可完成。 | G:129 `split candidate failure joins an interrupted background worker before returning`；G:169 `split candidate interruption cancels and joins the background worker`；C:837 `prepared global wide interrupted worker publishes cancellation and joins peers`；C:914 `prepared global wide interrupted request cancels workers queued behind another query`；C:1074 `prepared global wide checked worker failure is wrapped after joining workers`；C:3641 `parallel scan callbacks can execute nested cross graph queries without deadlock`；C:3722 `parallel scan failure interrupts peer tasks`。 |
| LIMIT 不能把后来 source 的错误提前泄露，也不能任意扩大 speculative discovery；索引/caches 生命周期与 memory admission 保持原能力。 | C:735 `prepared global wide limit stops the fixed workers in source order`；C:1709 `global wide row limit does not initialize speculative later graph scans`；P:563 `empty parallel distinct waves release rebuildable storage caches`；P:603 `targeted parallel distinct waves release only zero hit source caches`；P:647 `selected provenance pass does not rebuild a released zero hit cache`。 |
| 持久化数据验证、排序完整性、预算不足回退、临时内存归还不能因线程改动绕过。 | G:583 `mapped posting merge falls back before limit when a checksum-valid range is out of order`；G:2870 `large trigram posting sort preserves every key in ascending order`；G:2888 `large trigram posting sort stays on bounded workers and stops when request is interrupted`；G:2933 `trigram posting sort accounts temporary memory and declines when the index budget is full`。 |

G:113 `split worker metrics can reset immediately after every completed execution`、G:211 `split candidate executor enforces one shared background budget across graph callers`、C:65 `direct string override shares one additive NCPU budget` 等直接约束当前并发实现。删池后应以真实 caller-thread、无残留任务和不影响非 CallSite 路径的测试替代对应机制断言；不能把“并发测试不再适用”解释为结果、预算、取消义务不再适用。当前队列饱和测试 C:914 同时验证未启动 suffix 永不执行、取消返回时另一请求仍占满池、另一请求最终 COUNT/graphIds 完整，不能简单改成只断言抛异常。

还有两个不能用现有线程测试替代的审查点。第一，Graph.kt:66–70 的接口既允许 `consume()` 也允许 `consume(Long)`；若改 consumer 的具体实现，必须验证两种调用都可用，不能只让测试 adapter 改走批量调用而隐藏兼容问题。第二，graph-level executor 还承载非 CallSite direct-string 查询；删除整个 Q pool 和仅让 CallSite 改在线程内执行，影响范围不同。当前测试覆盖多个语义，但尚不能独立证明“所有 CallSite 入口完全不提交任务”；这一性质需要覆盖查询、单图预热以及大 trigram sort 的针对性检查。

## 既有采样能说明什么

旧 34 查询 frozen-main 的 `global-wide-wrapped-case-insensitive-distinct-dense` 在 `cpu-{3,4,5}-analysis-v1` 中：所有 CPU 样本分别 196/252/334，应用样本 105/136/178；graph worker 70/87/115，segment worker 27/40/54，request 8/9/9。其中 `parallelRawDistinctCallSiteStringProjection` 的 inclusive stack union 为 54/69/90，`exactMatchingStringIds` 为 45/60/82，两组不重叠，共 99/129/172。这些是 worker 执行的实际扫描/字符串查找工作，不是 99/129/172 个 executor 调度开销样本。

原始依据保留于 `/private/tmp/graphite-main-profiling-n50joikp/cpu-{3,4,5}-analysis-v1/analysis.json` 及同目录 dense 查询 collapsed；这是三个录制的离散 CPU 样本，不是函数精确耗时。inclusive 与 leaf 不能相加，线程总数也不能与函数 union 再相加。编译线程分别 90/113/148，不应归给某个 query 阶段。

纯四 OR 冷 profile 的请求线程确有 `runDirectStringTasks → CompletionService.take` 等待栈，后台也有大量验证/扫描 CPU；等待包含“正在等有用工作完成”，单凭 parked wall 或 pool 名称不能推断调度是主因。现有证据也没有量化 submit/queue/latch 的独立 CPU 占比，不能从此宣称删池就能达到 10x。删线程可能减少调度及投机扫描，也可能失去有用并行；哪一种支配旧 34 的 P95 仍需要后续经授权、同协议的独立测量。本文不据采样重判 Attempt 133/135/136 的既定失败结果。
