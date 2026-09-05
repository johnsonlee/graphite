# CallSite 工作交给现有图调度：只读可行性审计

**有真实的接入点，但当前没有可以直接调用的嵌套调度能力。** 应复用图级任务的请求所有权、顺序归并和取消边界，将现有图内 Callable 交给同一个通用图调度域；不能仅把 `splitCallSiteExecutor` 换成 `directStringExecutor` 后继续阻塞等待。普通固定池被父任务占满时，子任务无法执行。此处是源码推导出的设计约束，不是已实现、已测量或已接受的新候选。

本次未修改生产/测试代码，未运行 Java、构建、测试或测量。当前 HEAD `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`；检查的 8 个源文件与冻结 main `4e328b0109e13c896b74004823fb049fcb19251a` 逐字节相同，core/cypher/webgraph 全部 production diff 为空，见 [source-receipt.json](source-receipt.json)。下文源码位置相对该工作区根目录；准确文件绝对路径和 hash 在收据中。

## 1. 两类池实际承接什么

| 位置 | 当前工作和执行方式 | 去池必须保留的内容 |
|---|---|---|
| `MappedWebGraphBackedGraph.kt:83–97` | `callSiteScanExecutor` 固定池，`graphite-callsite-scan-*`，并行度受 processors/属性约束 | 不能改名后保留同一类专用池 |
| `MappedCallSiteStringIndex.kt:1973–2000` | `splitCallSiteExecutors` 按 backgroundParallelism 缓存多个固定池，`graphite-callsite-segment-*` | 是共享 background budget，不是每图额外乘一份 worker 数 |
| `MappedWebGraphBackedGraph.kt:481–669` | raw DISTINCT：准备一次匹配/投影状态，分连续 node ranges，首任务 caller 执行，其余投到 segment 池，再按 workerIndex 合并 | source order、每段 DISTINCT、最终 LIMIT；selectedValues/null、String→ID 与 membership 调用内缓存、exact sets、共享 lazy match states、flush/abort/join |
| 同文件 `1095–1320` | raw rows：split 路径 caller+background；非 split 路径全投 scan 池；可按有序 waves 截断 | 不完整扫描不能发布完整索引；split 冷图不自动为所有图构建 retained 索引；任务顺序/结果合并保持 |
| 同文件 `1461–1606` | 大图完整索引扫描及四属性构建阶段使用 scan 池 | reservation admission、完整扫描确认、property phase barrier、发布锁、失败释放；不能把并行能力删除成另一种 storage route |
| `MappedCallSiteStringIndex.kt:292–335, 430, 581, 2015–2050` | 多 predicate 的 posting ranges、候选等复用 `executeSplitCallSiteTasks`；caller 做第一项，其余有状态 Future+exit latch | 顺序拼接、原始异常、取消排队任务也完成 latch、所有已开始任务退出后才返回 |
| 同文件 `632–697, 2304–2375, 2581–2618` | trigram metadata/population、8 轮 radix sort 的 count/scatter 阶段使用 scan 池 | 阶段间 barrier、各 worker 独占写区、临时数组预算归还、取消/失败后的状态清理 |

**不能只接 DISTINCT。** `GraphStore.kt:956` 在准备持久化图时直接调用 `prepareCallSiteStringIndex()`，并没有 QueryPipeline；`MappedWebGraphBackedGraph.kt:2094` 的默认 consumer 允许并行。`sortCallSiteTrigramPostings`（index 文件 `2269`）当前只有 reservation/观测 callback 参数，没有 workConsumer，并且大排序直接进入并行 radix 路径。因此只给 Cypher 查询注入调度器，仍会留下 startup/独立 storage 的 CallSite 池调用。

## 2. 图级已有能力，缺少哪一层

`QueryPipeline.kt:156` 的 `directStringExecutor` 是现有通用图扫描池，线程为 `graphite-cypher-scan-*`。它的三个任务协调器已经具备可保留的工作：

- `runDirectStringTasks`（`2409`）：bounded in-flight graph tasks，完成顺序与结果源顺序分离，失败后 cancel 并等待真实退出。
- `runDirectStringTasksInOrderUntil`（`2479`）：只有连续 source prefix 推进才补交下一个 task，达到 LIMIT 后取消并 join 有界 suffix；远端图提前失败不能覆盖已足够的前缀结果。
- `runDirectStringTasksWithFixedWorkersInOrderUntil`（`2572`）：固定数目 worker 从队列消费 source index，避免每个小 retained lookup 都创建 Future；中断后 outcome 用非阻塞 add 发布，避免 coordinator 永久等待。

这些函数的 Future、started CAS、completion latch 都是**本次调用的所有物**，不是全局任务列表；ThreadLocal `directStringWorkerActive`（`103`）抑制部分再次图级并行入口（`1099, 1994, 3459`）。这是可利用的所有权基础，但还不是“父任务可以帮助完成子任务”的 scheduler。三个函数内部仍使用普通 `take/get/await`，固定 worker 还可能在 `taskIndexes.take()` 上等待。

最小的语义接入点是 `stringStorageWorkConsumer`（`4680`）→ `directStringStorageWorkConsumer`（`210`）。这里已有显式捕获的 `CypherWorkTracker`，以及每次存储调用的 parallel/split/serial/raw/preferred-mapped/preferred-persisted 能力；并非只能依赖隐式 ThreadLocal 传递请求信息。

`graphite-core/.../Graph.kt:61–139` 目前只定义 consume、consume(workUnits)、parallel 许可及 segmentWorkerCount，**没有 executor/task-group/submit/help-join API**。consumer 类型同时决定 storage 路径：

- `SplitGraphWorkBatchConsumer` 继承线程安全 parallel，含额外 background 数量。
- `PreferredMappedStringIndexViewGraphWorkBatchConsumer` 继承 split，保留 mapped view 路由。
- `PreferredPersistedStringIndexGraphWorkBatchConsumer` **同时**继承 serial 和 parallel：允许一次并行 raw build，后续保留索引 lookup 串行。不能简单改成 serial consumer 来消除线程。
- sourceCount=1、少量多图、balanced 多图的 consumer 分支不同（`QueryPipeline.kt:218–239`）；调度能力应与这些路由标记正交，不能用替换类型偷偷改变 admission/retain/raw/mapped 选择。

依赖也限定了接法：cypher 和 webgraph 的 production 都依赖 core，webgraph 对 cypher 只在 tests/JMH 有依赖（两个 build.gradle.kts）。存储不能直接 import Cypher 私有池。可行的边界是 **core 中的可选通用图任务能力，由现有图调度实现并向下传递**；不要给主 Graph 接口添加必须实现的新方法、强制第三方图适配器升级。独立 GraphStore 准备也需能进入同一通用图调度域；不能为它另建一个 CallSite 专用池。

## 3. 可以继续具体化的单一调度方向

**让现有图调度拥有一组请求内父/子任务，调用线程在等待时能够执行本组尚未完成的存储任务。** 保留原来的等分大段 Callable 和状态初始化，迁移提交/等待归属，不先改谓词、posting、range 粒度、索引或缓存。这个方向与 132 的“所有 CallSite 图任务及内部任务都 inline”不同：图间及需要的图内工作仍可并行，线程只来自同一个已有通用图执行资源。

从源码看，`executeSplitCallSiteTasks` 是最集中、最容易审查的小入口；raw rows/DISTINCT 和 trigram 还有自己的 completion loops，不能认为改这一处就完成去池。可借现有有界任务组的 started/cancel/join 模式，让原 Callable 在通用图 worker 或参与工作的 caller 上执行，结果仍回到原 owner 归并。若选择将子任务完全拆成图调度的顶层工作，也必须额外表示同一图的 prepare→segments→ordered merge→publish 依赖；当前同步 storage API 没有现成可供 scheduler 展开的 work plan，这条路改动面更大。

必须先解决的具体约束：

1. **父任务占满线程的进度保证。** 四个 graph worker 同时处理不同请求，每个提交子段后等待，即使平时一条 balanced query 只使用两图，也可能耗尽现有 4-thread pool。不能以单请求“还有两个空线程”证明安全；仅队列优先级也不能使已阻塞父任务腾出线程。需要帮助执行或等价的非阻塞依赖推进，不能原样 pool substitution。
2. **帮助执行的所有权边界。** `callSiteStringIndex`（mapped graph `2228`）和 `ensureTrigramMetadata`（index `671`）有锁；父线程持锁等待时，不应任意窃取另一请求对同一图的整个 graph task，使其等待自身未完成任务。优先执行所属组的 leaf work，atomic claim 保证只执行一次；异常、取消和预算始终归到发起请求。不能因为帮助执行而覆盖原 ThreadLocal/evaluator 状态。
3. **受控消费能力。** `GraphScanParallelismPlan`（core `97`）是 additive graph+segment，不是乘积；迁移后需要真实的请求内子任务限额和共享可用 worker 约束，防止每图都自造 NCPU 个并发。不能只改线程名称/统计标签来假装预算保持。首图 probe caller 可参与；prepared 小 lookup 保留现有固定 graph workers/串行 storage，不强行切段。
4. **返回即全部退出。** 延续 `SplitCallSiteTask` 的 NEW/RUNNING/FINISHED 与 exit latch（index `1912`），包括 queued-before-start cancellation；保留 graph coordinator 的有界 speculative suffix、source-order failure 和 interruption 恢复。所有 worker accounting flush 后才能释放 reservation/返回请求。Future.cancel(true) 本身不等于任务已退出。
5. **不把调度改动变成算法候选。** raw 分段前的一次 selected-string/membership 缓存、exactMatches、match-state 复用保持原位；多图 raw scan 的 indexReservation 条件（mapped graph `1142`）和 complete-index publication 条件（`1296`）不改。trigram sort 的每阶段 barrier、snapshot reservation 与 initialized 标志回滚也要原样保持。

这是一处可行边界与必要能力清单，**不是已有实现或性能承诺**。在有限源码范围和已读历史中没有找到一个已实现的“现有 graph pool + request-owned cooperative child tasks”被拒绝的同样候选；这不证明它一定更快，也不授权开始实现。应先让该 task-group 协议成为可审查的具体设计，再以一项调度候选验证，而不是同时叠加 posting/validator/JIT 微调。

## 4. 132 为什么慢，哪些并行性应保留

Attempt 132（chronology `4218–4303`，commit `1882efe41756583d34ebabf3150b75e91f351ed2`）同时删除两类池，并把 **CallSite/unlabeled 的图任务也全部改为 inline**。它不只减少 Future 开销，也取消了两层独立进度。真实 hosted reverse-order 复核中，dense discovery `0.9553→2.0711 s/op`、zero-hit `0.2355→0.4236`、36-graph zero-hit `1.8251→3.5729`；兼容 CPU 多项失败，capacity CPU `4.9533→6.3333 s`。这个方向已经永久拒绝。

源码能够说明它丢掉了原并行工作；数据证实整体调度替换退化。**现有记录没有分开测量“只保留图并行但取消段并行”，也没有 profiling 把其 CPU 增量归到某个组件。** 因而不能把这些数值写成每一个内部任务都必须并行的因果证明，更不能解释成“Future 开销反而增加了 CPU”。132 的 real64 global P95/routing 测量当时停止，不能补写缺失结果。

正确性本身不要求多个线程；这里的“需要保留并行”是满足已见真实性能与消费能力的保守要求：

- 多图 zero/targeted 扫描、COUNT、DISTINCT 全来源补全：有必须检查到尾部的独立图工作，保留 graph 并发；不能为满足 LIMIT 丢弃 selected tuple 的完整来源。
- 单图大 raw scan、全图 index preparation、较大 trigram metadata/population/radix 阶段：图数为一时 graph-level 并行无可分对象，图内等分任务是现成并行资源。先迁移执行归属，不以 132 数据为理由把它们全部变串行。
- 有序 dense 前缀、已准备的低工作量 retained lookup：并非越并行越好。主线已经首图探测、LIMIT 早停、固定 graph workers，及在 serial 标记下保留索引 lookup。不能为了去池再拆成大量小段或 Future。

最新原生 JFR 仍显示 targeted/dense 的 callback Java 快照 29/31、27/31、22/25 与 12/29、10/29、7/22，但样本不是 CPU 时间，也不能证明调度开销是主瓶颈。晚发生的 deopt 不能解释之前的约 37 ms。因此本方案没有给预计加速倍数或声称能达到 10x。

## 5. 历史排重

所有位置来自 `docs/wrapped-case-insensitive-query-optimization-attempts.md`；早期倍率参考的是当时 base，不能移植成相对冻结 main 的收益。

| 已有方向 | 历史位置/结论 | 本次边界 |
|---|---|---|
| 081/082：有序小 chunk / posting count gate | `2534/2563`；sparse 仍全扫时多个小任务明显增加耗时，均拒绝 | 不把搬迁 scheduler 变成再次缩小 chunk/增加任务个数 |
| 065/105：每图一个 prepared lookup Future | `2075/3231`；任务开销压过小 lookup，拒绝 | 复用已有 fixed-worker primitive，不能当新优化 |
| 106→111：prepared 固定 graph workers | `3267/3415`；先前 head 尚有失败，后续修正最终进入现有路径 | 现有能力是基础；保持源顺序、首图短路、prepared storage 语义 |
| 108：selected K64 串行图、内部 split | `3337`；tiny range 任务引入开销，拒绝 | prepared selected sets 不因去池重新内部并行 |
| 132：所有 CallSite 图/段 caller-thread | `4218`；已重复确认真实回归，完全回退 | 不重开全串行，不隐瞒 CPU 失败/缺失 P95 |
| 133/135/138：selected tuple/posting feasibility 与 mapped projection | `4354/4755/5160`；均拒绝 | 不把 posting 减工叠加到此次 scheduler 方向；逻辑 posting 数不是收益 |
| 136/140：OR loop/property indexes | `4887/5427`；均拒绝 | 不是此次调度改动；不由 JIT 状态差异重开 |
| 137/139：validator callback 专门化 | `5005/5282`；均拒绝 | 完整 validator、格式与缓存不改 |

需要保留的既有 deterministic correctness 边界包括：共享 graph pool 被占满时取消排队请求仍必须完成（132 revert follow-up `4313`）；固定 graph worker 的先开始/未开始取消都归还 completion；完整结果值/顺序/provenance；共享 budget；真实取消和失败传播；索引 admission/recovery。这里仅识别覆盖要求，未运行或新增测试。后续候选仍须遵守当前每组严格 P95 进步、逐 query 回归、纯四词 OR 单图/多图、资源与 exact-head CI 门槛，不能因 scheduler 目标而降低。
