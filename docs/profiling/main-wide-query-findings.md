# main 宽查询：已测到的优化机会

Attempt 137 已拒绝并回退：纯四关键词 OR 冷索引校验改成原始类型回调后，局部 DISTINCT 观测约下降 23%–30%，但 CI 的 Method4 count／middle CPU 均连续两次超过 15% 门槛。[完整配对结果](attempt137/v3-pairs/README.md) 和失败报告均保留；没有接受生产优化。下面保留 profiling 与覆盖范围。

最明确的机会是让混合布尔条件使用已有字符串候选筛选，减少全图节点上的通用表达式求值。
Attempt 134 已验证这项局部收益：三组真实数据配对中，混合 DISTINCT 约从 38–39 秒降到 211–215 ms。但旧 34 条查询出现重复延迟差异及 CPU 超限，本轮被拒绝并回退；没有接受任何生产优化，也未证明整体 P95 达到 10x。详见 [失败比较](attempt134-old34-global-wide-report.md) 和 [混合查询配对数据](attempt134-v3-paired-latencies.json)。

原先临时目录的链接未能在用户端打开，因此这里将结论、查询清单和可独立打开的 HTML 火焰图放进工作区。关键数字全部列在正文，无需打开附件才能理解结论。

## 已观测事实

数据是 frozen main `4e328b0109e13c896b74004823fb049fcb19251a`，64 张真实持久化分片图，来自 Android、Tika、Hive、Kotlin compiler 四个语料，不是 64 个独立应用。

慢查询的逻辑是 `(A AND B) OR (C AND D)`，每个关键词分别搜索 caller/callee 的 class/name 四个属性，使用 `toLower(coalesce(..., '')) CONTAINS`。无标签 `MATCH (n)`，四字段 DISTINCT，LIMIT 200。

| 观测 | 结果 |
| --- | ---: |
| 完整命中图数 | 2 / 64 |
| LIMIT 前匹配节点 | 229 |
| 去重后返回元组 | 71 |
| 无 profiler 单次控制耗时 | 38.375816 秒 |
| CPU / wall 录制查询耗时 | 42.642898 / 40.549959 秒 |
| 记录的图访问工作量 | 19,431,891 |
| 独立 fixture provenance 中全部节点数 | 19,431,891 |
| CPU 样本归于请求线程 | 42,516 / 44,322，95.93% |
| 记录的顶层 GC 暂停 | 25.962 / 54.765 毫秒 |

CPU 叶子样本主要包括 `ExpressionEvaluator.evaluateStringOp`（7,514）、`ExpressionEvaluator.evaluate`（5,068）、`StringLatin1.toLowerCase`（3,086）。这是通用表达式求值和字符串处理的运行时证据，不能据此认定线程调度是主要瓶颈。

实际诊断计数为 `filteredNodeLimitFastPathExecutions=1`、`generalFallbackExecutions=0`。这条查询在 filtered-node-LIMIT 路径内部走逐节点通用求值；不能把它称为 general fallback。

对同一 frozen JAR、同一查询 AST 调用现有编译器，得到：

```text
DirectStringFilter = null
DirectStringDisjunction = null
DirectStringConjunction = null
DirectStringCandidatePlan = null
```

源码与此一致：[候选计划编译器](https://github.com/johnsonlee/graphite/blob/4e328b0109e13c896b74004823fb049fcb19251a/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt#L3215) 能从 AND 分支提取必要条件，但不支持顶层 OR 连接的两个复合子树。因此这条查询进入 [逐节点求值循环](https://github.com/johnsonlee/graphite/blob/4e328b0109e13c896b74004823fb049fcb19251a/graphite-cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/QueryPipeline.kt#L1480)，工作量与完整节点数相等。

## 第一项应验证的假设

对 `(A AND B) OR (C AND D)`，如果左右子树分别能提取不漏结果的必要条件 `A` 和 `C`，则候选集可以取 `A OR C`。只对候选执行原始完整条件，仍由现有逻辑完成投影、去重、LIMIT 和来源图汇集。

这个方向针对 profile 中已经观察到的全节点通用求值成本。不能用 229 个最终匹配节点假冒候选数量，也不能把这条查询可能的收益当作所有宽查询的 P95 收益。

进一步核对独立目录发现，`or-few-early-late` 恰好就是同一组 `A OR C`：完整扫描统计为 **962 个候选节点、两图命中**。因此可以验证一个具体假设：把这条查询原本对 19,431,891 个节点进行的完整布尔求值，缩小到 962 个候选。这个数字是独立参考扫描得到的候选集合大小；实际索引读取工作量和耗时仍需测量，不能按节点比率直接宣称加速倍数。

正确性边界必须保持：OR 的每个分支都要有完整候选覆盖；无法证明时保持原路径；保留 null/三值逻辑、结果顺序、预算取消及跨图 DISTINCT 来源。不会仅因当前 fixture 没有某类节点就把无标签查询硬改为 CallSite 标签查询。

重复大小写转换、函数参数列表分配也有采样依据，但它们属于后续可单独验证的方向。本轮不把这些改动与候选计划混入同一次尝试。CallSite 线程池移除及相对起点 main 的全图查询 P95 10x 目标仍须独立兑现。

## 四关键词纯 OR：不同的路径和机会

同样四个关键词改成 `A OR B OR C OR D` 时，必须保留四个词的并集；`A OR C` 会漏掉只满足 B 或 D 的结果。独立参考扫描确认，该条件命中 **55 张图、50,461 个节点、18,915 个不同元组**，不是原混合条件的两图和 71 个元组。

同一 frozen JAR 的编译器成功生成包含 16 项的 `DirectStringDisjunction`（4 关键词 × 4 属性）。控制运行实测：普通投影 34.421916 ms，DISTINCT 149.975958 ms；两者都返回前 200 行，完整返回值和来源图均与独立参考一致。这是单次冷索引诊断，不是 P95 或优化收益。

每种投影又分别采集了 40 次冷索引执行的 CPU 和 wall profile，全部 160 次结果均核对通过。DISTINCT 的 13,830 个 CPU-mode 样本中，11,183（80.86%）包含 `PersistentIndexViewValidator` 调用栈；原先的 `ExpressionEvaluator.evaluate` 为零。普通投影对应占比为 32.49%。不能把混合条件的热点套用到纯 OR 上。

DISTINCT 的采样分配权重中，94.24% 的叶子归于 `Long.valueOf` / `Integer.valueOf`。对应源码是 [索引校验的 Int/Long 循环与泛型回调](https://github.com/johnsonlee/graphite/blob/4e328b0109e13c896b74004823fb049fcb19251a/graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt#L465)。因此这组纯 OR 冷查询的另一项可验证机会是减少完整校验过程中的装箱和数据转换开销，同时保留 CRC、范围检查、取消和工作预算语义。该方向必须作为独立尝试验证。

CPU 工作主要发生在四个现有扫描线程，请求线程会等待这些任务。它们承担的是实际校验工作；不能把线程上的 CPU 样本当成线程池调度自身的开销。DISTINCT 取满 200 个元组后，还须确认其他图是否包含这些元组，以完整汇集来源图；前 200 行只来自一图并不允许直接跳过其他图。

逐栈重算、装箱边界、64 个 sidecar 头部推导的回调次数及必须保留的校验语义，见 [冷索引校验证据](cold-four-or-index-validation.md)。这项证据只支持独立验证减少泛型回调装箱的假设，尚未证明候选收益。

这组数据每条查询前都清理索引，反映冷加载开销。随后另采一组只在整轮开始清理索引的 control / CPU / wall 录制，每组 80 次，仍逐次核对完整结果。预先排除第一对冷查询后，保留每种投影 39 次热索引查询：

| 同一 JVM 内热索引控制运行 | 中位数 | 经验 P95 | 样本数 |
| --- | ---: | ---: | ---: |
| 普通投影 | 3.018250 ms | 5.871833 ms | 39 |
| DISTINCT | 8.163958 ms | 11.321750 ms | 39 |

这里按固定查询计算 nearest-rank 经验 P95。这些是同一 JVM 内的连续诊断样本，不是独立 forks，也不证明稳定性或最终目标；JIT 没有被人为重置或充分预热。

热索引 CPU 采样中，`PersistentIndexViewValidator` 为零，冷态校验热点消失。普通投影共有 598 个样本，其中应用查询线程 283 个；DISTINCT 共 1,528 个，其中应用查询线程 659 个。其余包含较多后台编译活动。应用样本分布在原始 CallSite 匹配、集合成员检查、trigram posting 查找、工作预算计数和结果处理，尚不能据此认定某一项是普遍的热态瓶颈。

因此，混合条件的候选计划缺失、纯 OR 的冷索引校验、纯 OR 的热态匹配应分别判断。不能把冷转热的差异当作新优化收益，也不能假定调整混合条件计划会加速已经走专用路径的纯 OR。

## 冻结 main 的 20 个独立 JVM 观测

v2 的 26 条查询已完成 20 个独立 JVM 的重复运行，共 520 次查询，全部通过完整返回值、顺序和来源图核对。运行前后持久化图内容哈希一致。每条查询之前清理字符串索引；JIT 和 OS 页缓存不重置。

| 固定查询 | 中位数 | 经验 P95 | 样本数 |
| --- | ---: | ---: | ---: |
| 四关键词纯 OR，55 图，普通投影 | 35.725229 ms | 38.741083 ms | 20 |
| 四关键词纯 OR，55 图，DISTINCT | 151.273355 ms | 154.172875 ms | 20 |
| 四关键词混合条件，两图，普通投影 | 349.089416 ms | 767.150583 ms | 20 |
| 四关键词混合条件，两图，DISTINCT | 39,108.900042 ms | 39,716.441166 ms | 20 |

这些分位数分别取每个查询 20 个耗时的第 19 个顺序统计量；不混合不同查询，也不能用 20 个样本保证稳定性。它们是未修改 frozen main 的诊断基线，既不是新代码收益，也不是 CI 接受结果。原始凭据为 `main-v2-repeated-20/run.json`。

## 纯四关键词 OR 的单图／多图覆盖补齐

v3 保留 v2 全部 26 条查询和预期结果，增加 5 组纯四关键词 OR，每组包含普通投影和 DISTINCT，共 36 条。冻结 main 的完整 36 条控制回放已通过返回值、顺序、来源图核对，运行前后持久化图内容哈希一致。

| 纯四关键词 OR 条件的完整命中分布 | LIMIT 前节点数 | 全量 DISTINCT 元组数 |
| --- | ---: | ---: |
| 单图，位置 0 | 704 | 444 |
| 单图，位置 31 | 2,646 | 2,356 |
| 单图，位置 63 | 299 | 206 |
| 两图，位置 0 和 63 | 972 | 626 |
| 原四词，55 图 | 50,461 | 18,915 |
| `get OR set OR read OR write`，全部 64 图 | 2,455,554 | 1,771,173 |

每个关键词分别查四个 caller/callee 属性，仍为无标签查询，没有用 graphId 过滤人为限定命中范围。所有六组纯四词 OR 都验证了每个词至少有一个只命中该词的节点，因此任何分支都不能直接删掉。目录中的 `termExclusiveMatchCounts` 保留四个精确独立贡献计数。

另外新增 4 项持久化图正确性测试，核对四分支独立命中、重叠命中与去重、物理返回顺序、所选元组过滤和查询间缓存隔离；在冻结 main 上随模块共 187 项测试及 detekt 全部通过，见 [测试凭据](pure-four-or-test-receipt.json)。这些合成图只验证正确性，不用于性能结论。

表中命中图数是完整条件在 LIMIT 前的分布。两图用例可以由首图填满前 200 行；全 64 图的 DISTINCT 返回前缀只涉及两图。这些都不会把完整条件变成单图或两图查询。单图位置变化也必须保留，用来观察源扫描顺序和提前结束的影响。

v3 的 36 条查询现已完成另外 20 个独立 JVM 的完整回放，共 720 次查询，均通过完整值、顺序、来源图和前后图内容哈希核对。每条查询有 20 个观测，按各自第 19 个顺序统计量计算经验 P95。没有据此把 55 图用例的采样热点推广为所有新增分布的热点。可读取 [v3 全 36 条的 20 轮统计](main-v3-repeated-20-summary.json)、[最初控制回放](main-v3-control-summary.json) 和 [先前 v2 的 20 轮统计](main-v2-repeated-20-summary.json)。

| 纯四关键词 OR 的完整命中范围 | 普通投影经验 P95 | DISTINCT 经验 P95 |
| --- | ---: | ---: |
| 单图，位置 0 | 17.438 ms | 152.652 ms |
| 单图，位置 31 | 94.437 ms | 151.192 ms |
| 单图，位置 63 | 151.125 ms | 150.579 ms |
| 两图，位置 0 和 63 | 17.361 ms | 151.402 ms |
| 55 图 | 39.582 ms | 152.804 ms |
| 全部 64 图 | 16.458 ms | 185.749 ms |

这是一组冻结 main、每条查询清理字符串索引的诊断基线；不是新优化收益，也不是 production P95 或稳定性保证。不同命中分布使用不同关键词，不能将全部耗时差异只归因于图的位置。普通投影受 LIMIT 提前结束影响；DISTINCT 还需补全所选元组的来源。第一轮相应 work units 从前部普通投影约 116 万增加到末尾约 5770 万，各组 DISTINCT 约 5770–5808 万；计费单位不是 CPU 指令数。旧 34 条的 cold-on-replay 协议与此处 per-query-cold 不同，不能直接混成一张加速表。

## 覆盖与附件

最初的 24 条查询覆盖单图位于前/中/后、两图、全 64 图、零命中，两关键词 AND/OR 和四关键词混合条件。v2 保留原有用例，追加纯四关键词 OR 的普通投影与 DISTINCT，共 **26 条**，均已通过完整返回值、顺序与来源图独立核对。v3 再追加单图前／中／后、两图和全 64 图的纯四词 OR，当前共 **36 条**。完整命中集合在 LIMIT 前统计，另行核对实际返回前缀的来源。

- [完整关键词和查询清单](main-multi-keyword-queries.md)
- [CPU 火焰图](main-mixed-distinct-cpu.html)
- [采样分配火焰图](main-mixed-distinct-allocation.html)
- [请求线程 wall 火焰图](main-mixed-distinct-wall.html)
- [纯四关键词 OR DISTINCT CPU 火焰图](main-four-or-distinct-cpu.html)
- [纯四关键词 OR DISTINCT 分配火焰图](main-four-or-distinct-allocation.html)
- [纯四关键词 OR 普通投影 CPU 火焰图](main-four-or-rows-cpu.html)
- [纯四关键词 OR 热索引 DISTINCT CPU（仅应用线程）](main-four-or-warm-distinct-cpu.html)
- [纯四关键词 OR 热索引普通投影 CPU（仅应用线程）](main-four-or-warm-rows-cpu.html)
- [可重复执行和核对的工具](../../.github/scripts/wide-query-profile/README.md)

这些 profile 是单次诊断。原有 34 条的 P95 是不同查询各一次的分布，不能替代固定查询重复运行的 P95。新工具保留每个 JVM 的全部原始观测，按查询分别计算经验 P95；不足 20 次时不报告 P95，20 次也不是稳定性保证。

原始 JFR、精确命令、独立导出及校验凭据保留于执行机器 `/private/tmp/graphite-main-profiling-n50joikp`。本工作区复制的 HTML 可独立打开。分配采样权重不是精确分配量；inclusive 样本不可相加；idle 线程 wall 时间不是请求阻塞；完整 safepoint 时长因未记录结束事件而未知。

## 旧 DISTINCT P95 的进一步定位

三份已有 profile 一致指向选中元组的跨图来源补全：字符串候选发现和 raw 投影链占应用线程样本的 94–97%。独立参考探针证明，`get` 查询完整命中 64 图，但选定的前 200 个 DISTINCT 元组可用既有四属性必要条件排除其中 62 图。当前实现先算谓词候选，再检查选中元组是否可能存在；Attempt 135 实测减少了该查询的工作量，但定向 DISTINCT 两轮退化，未通过验收，生产改动回退。详见 [完整 64 图计数和证据](dense-selected-feasibility/README.md)。

Attempt 135 的旧 34 条 P95 分别改善 1.30×、1.50×、2.09×，但这不抵消定向查询的回归；它没有成为已接受的优化。详见 [完整比较](attempt135-old34-global-wide-report.md)。纯四词 OR 等 36 条的候选控制回放通过值、顺序和来源核对，仅各一次，不报告 P95。

对已拒绝 Attempt 135 的后续三组配对 CPU/分配采样，没有重现定向 DISTINCT 的原耗时退化。该查询仅返回 12 行，小于 LIMIT 200，不进入 selected tuple 来源补全；两版工作量和 raw scan 数相同，主要节点循环与 worker 字节码在归一常量池编号和生成方法名后相同。新增 callback 的 inclusive 样本全部与候选发现重叠，不能当作 callback 自身耗时。采样不足以解释此前退化，也不会推翻原失败判定。见 [独立采样核对](attempt135-targeted-diagnostic/independent-targeted-comparison.zh.md)。


随后 Attempt 136 仅移除 raw DISTINCT OR 节点循环的 range/iterator 构造。全部正确性与模块检查通过，但三组 P95 加速比为 0.934×、1.185×、1.142×，未满足每组均有进步，因此拒绝并回退。采样中的分配热点不足以保证整体收益，见 [原比较与拒绝记录](attempt136/global-wide-report.md) 和 [判定](attempt136/local-progress.json)。


进一步的 [初始选择／来源补全阶段诊断](distinct-phase-boundaries/README.md) 用三份冻结 main 记录区分两条慢路径：密集 DISTINCT 的来源补全区间约 31–48 ms，定向 DISTINCT 没有该阶段，其初始选择约 25–30 ms。独立真实导出显示定向条件只有 12 个匹配节点，而命中两图合计 104,566 个 CallSite 仍被 raw 扫描。旧 133 的三组、135 的前两组候选 P95 已移到定向查询，因此不能只优化密集查询。线程池全部入口和不能丢失的语义见 [调用图](callsite-executor-map.md)。这些新增诊断没有形成生产候选或已接受收益。


Attempt 137 只把持久索引完整校验的逐元素回调改为 `IntConsumer`／`LongConsumer`，保留全部校验、CRC、取消检查及工作量计数。字节码确认移除了该回调边界的装箱。真实四关键词 OR 冷索引 DISTINCT 三组配对中，单图／两图约 143–151 ms → 101–108 ms，全 64 图约 175–183 ms → 134–136 ms；这不是 P95。原 34 条 gate 本地三组 P95 约改善 2.61×、1.02×、1.22×，第二组仅 0.88 ms 且定向查询单次变慢。见 [完整 36 条配对表](attempt137/v3-pairs/README.md) 与 [原 gate 审计](attempt137/independent-old34-audit.md)。随后完整 CI 出现 Method4 count／middle CPU 双次退化，这轮已拒绝并回退；10x 与线程池移除目标均未完成。


[Attempt 137 机制复核](attempt137/mechanism/README.md) 确认校验逐元素回调的装箱热点大幅减少，但 [Method4 count](attempt137/ci/method4-aggregate/method-compatibility-4-aggregate-cpu-report.md) 和 [middle](attempt137/ci/method4-position/method-compatibility-4-position-cpu-report.md) 的重复 CPU 退化阻止验收。局部热点改善没有成为可保留的整体优化。
