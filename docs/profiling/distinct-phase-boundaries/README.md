# DISTINCT 的初始选择与来源补全

冻结 main 的三份新记录确认了两个不同瓶颈：密集查询主要花在来源补全，定向查询主要花在初始结果选择。只改善前者，会让后者成为 P95；这已经发生在被拒绝的 Attempt 133 和 135 中。

这里没有新生产改动，也没有重新接受此前失败的候选。使用原来的真实 fixture64、原 34 条查询顺序、同一冻结 JAR。每份录制运行一个独立 JVM，所有 102 条结果通过原 oracle；持久化图及 JAR 在采集前后内容一致。64 图是四份 corpus 的 class shards，不是 64 个独立应用。

## 阶段时间

单位 ms。初始选择、来源补全各自取跨线程调用区间的并集；不累加并发调用时长。两类区间不重叠，剩余时间单列。

| 密集 DISTINCT | execute 窗口 | 初始选择 | 来源补全 | 其余窗口时间 |
|---|---:|---:|---:|---:|
| 1 | 55.217 | 5.080 | 47.672 | 2.465 |
| 2 | 39.490 | 5.700 | 31.285 | 2.504 |
| 3 | 42.791 | 6.081 | 33.777 | 2.932 |

每次都是一次初始 `projectSource`，然后 63 次来源补全调用。前一图已经给出选定的 200 个不同元组，后续图仍须核对这些元组的完整来源。

| 定向 DISTINCT | execute 窗口 | 初始选择 | 来源补全 | 其余窗口时间 |
|---|---:|---:|---:|---:|
| 1 | 30.230 | 29.332 | 0 | 0.898 |
| 2 | 31.118 | 30.260 | 0 | 0.858 |
| 3 | 25.924 | 25.316 | 0 | 0.608 |

每次都是 64 次初始 `projectSource`，没有来源补全调用。它只得到 12 行，小于 LIMIT 200，因此在初始阶段返回。这也说明仅对 selected-values 非空的路径做优化，不会直接解决这条查询。

这些是带方法 tracing 的诊断时间，不是生产 P95、候选收益或无 tracing 时的性能下限。不能据此精确预测最大加速比。方法区间内的 CPU 和分配按事件时间戳分区；后台编译或 GC 恰好发生在该区间，并不等于由该阶段引起。

## 定向查询的具体冗余工作

对已认证的 5,046,935 条真实 CallSite 导出进行独立全量参考扫描，定向条件只命中 **12 个节点、12 个元组**，分别位于 `fixture-kotlin-compiler-11`（11 个）和 `fixture-kotlin-compiler-15`（1 个）。这两图共 **104,566 个 CallSite**。

现有 TSV 对每次查询记录两个 raw parallel scans、106,706 work units，结果只有 12 行。源码先通过 mapped view 求出匹配字符串 ID，然后在 `exactMatches != null` 的 split 路径无条件尝试 `parallelRawDistinctCallSiteStringProjection`。每图结果均不足 LIMIT，其 raw 扫描不能提前结束。候选节点很少，但仍扫描整张命中图，是这里需要验证的算法机会。

这不是说只需要 12 个工作单位：已有索引完整性校验、所选 posting 的全范围物理顺序验证、合并、投影、去重、预算和取消仍然需要执行。参考节点数不能替代真实索引访问成本或性能测量。见 [全 64 图计数](targeted-reference-census.json) 和 [参考脚本](targeted-reference-census.py)。

值得继续评估的是使用现有已验证的 mapped postings 完成 DISTINCT 投影，避免对稀疏候选做整图扫描，并审查同一投影路径如何处理 selected tuple 来源查找。这里尚未选定或实现候选策略；不能把 Attempt 133 的 selected-only 查找直接重包装成新收益。

## P95 会移到另一条查询

原 gate 在每轮 34 条不同查询中取第 33 个顺序统计量。各轮最慢的是首次冷零命中查询，它不在该 P95 位置。

- Attempt 133 的三组候选 P95 全部由定向 DISTINCT 决定，分别为 57.049、61.360、63.982 ms；密集查询虽降低，整体收益受定向查询限制。
- Attempt 135 的前两组候选 P95 由定向 DISTINCT 决定，分别为 33.787、31.796 ms；第三组仍由密集查询决定（29.107 ms，定向为 23.679 ms）。

因此不能只看密集查询的局部加速。最终 10× 仍需要按原完整 gate 验证所有查询、来源、资源和 CPU；新增多关键词冷查询也要独立保留覆盖。

## 可复核证据与边界

通过启动参数增加三个方法 trace：原 `CrossGraphCypherExecutor.execute`、`QueryPipeline.executeIndexedDistinctStringProjection$projectSource` 和来源补全的 `$lambda$155$lambda$154`。这些名称先从冻结 JAR 的 javap 核对；多 trace 参数依据 [async-profiler 方法 tracing 说明](https://github.com/async-profiler/async-profiler/discussions/1497)。没有重建或改写运行 JAR。

原外层分析器保留精确方法签名、请求线程及 34 条目录的顺序绑定。阶段分析器使用精确 owner 和方法名；当前三份数据均另行核对为 192 个阶段调用，密集为 1/63、定向及零命中为 64/0。所有 34 条查询的 CPU 与采样分配权重按三类区间分区后均与原分析器总数守恒。

首次原始零命中查询在 TSV 与 execute trace 之间有约 7.7–8.0 ms 未覆盖时间；密集和定向查询仅有 52–70 µs。该差值明确保留，没有归入任一阶段。内部 tracing 也会改变执行成本和 JIT 行为，不能用这些时长重判旧候选。分析器开发期间的目录与正差值断言修正记录在 [分析器凭据](analyzer-receipt.json)，没有因此重新采集。

[阶段摘要](summary.json)、[离线分析器](DistinctPhaseWindows.java)、[原命令生成脚本](capture.py)、[输入记录](input-receipt.json)、[采集后核对](input-after-receipt.json)。原始 JFR、完整按线程分区及 outer analysis 保存在 `/private/tmp/graphite-distinct-phase-profiling.by0z0asb/`。

独立 [二次审计](independent-phase-audit.md) 核对了 102 个完整结果签名、区间并集、全部事件数和按线程采样权重，均守恒，缺失及截断栈为零。作为单独的条件敏感性分析，固定历史 main 的其余 33 条耗时、把密集 DISTINCT 整条设为零，整体 P95 也只改善 1.197–2.277×；这不是新测量，也不预测实际优化收益。
