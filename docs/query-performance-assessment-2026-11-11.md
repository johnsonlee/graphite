# Graphite 查询性能与优化空间评估

评估日期：2026 年 11 月 11 日，Asia/Seoul。本报告分为两个证据阶段：初始目标 `58215b98` 的 PR211 历史 CI 评估，以及随后新增的真实 64 图复杂查询实验。已实现的原生候选为 Attempt315（`94c7d3b9`）和累计 Attempt316（`3498c8ea`）；不能再用初始 CI 的整树一致性代表开发分支 HEAD。

**目标：优先解决慢查询、复杂聚合的 P95。P50 仅作诊断，不能抵消 P95 退化。** 正确性、可用性、稳定性以及 CPU/RSS 各自的 5% 限制保持不变。用户报告生产 50+ 图 P95 经常达到几十秒；四组真实 JAR 的 64 个分片不能完整代表这些生产项目。

**最新结论：新增复杂工作负载已暴露几十秒级尾延迟，且 Attempt316 的三轮配对观察显示明确的增量收益。** 原 main 在复杂查询 pilot 的预热阶段超时，没有压力 P95；它不能作为完整压力比较基线。首轮 315→316 配对中，dependency co-use P95 从 **38,919.123 ms 降至 2,264.064 ms**，绝对减少 **36,655.059 ms**；反序 BA 第二轮为 **37,186.954→2,268.983 ms**。三轮全部六类 P95 均下降，混合压力 CPU 与查询进程峰值 RSS 也下降。目前三轮 pilot 已完成，独立 100 样本确认和原 73 类回归尚未全部完成，不能宣布最终验收或原 main 回归恢复。

## 本地复杂查询新证据与当前状态

使用 [六条复杂查询 catalog](../benchmark/complex_aggregation_queries.json)，覆盖多阶段聚合、全局去重、两跳 DATAFLOW、度数分布、OPTIONAL sinks 和依赖共用组合。每个请求实际选择全部 64 个真实持久化图；并发 4，完整消费 HTTP body，不在必要聚合前插入 LIMIT。具体采样、失败保留、资源窗口和后续配对轮次见 [本地协议及 Attempt316 修订](query-performance-evidence/2026-11-11/complex-p95/PROTOCOL.md)。这是带校验间隙的闭环压力，不是饱和吞吐测试。

| 阶段/版本 | 已有事实 | 可支持的结论 |
| --- | --- | --- |
| 原 main `58215b98`，测量构建 checkout `a911529a` | 六条 first-use 均完成，其中 co-use 46,300.031 ms；随后 pilot 预热 co-use 超过 70 秒客户端期限，另一在途请求在失败停止时中断；压力样本为 0 | 保留可用性失败；first-use 不是 P95，不能构造原 main 压力 P95 或完整压力 CPU 基线 |
| Attempt315 `94c7d3b9` | 六条 first-use、12 条 warmup、120 条 pressure 共 138 请求完成；每类 pressure N=20 | 有当前固定复杂 workload 的每类 rank19 P95；尚不是与原 main 的完整配对恢复证据 |
| Attempt316 `3498c8ea` | 紧凑字符串 pair 计数、有限字典 bitset 与 bounded winners；441 项模块测试、专项差分、显式真实双源 fixture、lint 和 release 构建通过；AB、BA、AB 三轮各276请求全部成功并匹配 | 已验证三轮增量收益，保留继续验证；不以现有轮次代替后续确认及累计验收 |

Attempt315 的单轮压力观察如下，均为完整请求的毫秒；不能与后文 Linux CI 73 类查询的分布拼接：

| 用例 | pressure P95 ms | min–max ms |
| --- | ---: | ---: |
| dependency co-use | 44,828.904 | 26,450.328–47,823.741 |
| OPTIONAL DATAFLOW sinks | 20,374.777 | 10,662.687–23,483.253 |
| DATAFLOW degree histogram | 12,253.272 | 5,681.263–14,460.736 |
| caller dependency rollup | 5,835.735 | 2,495.155–6,288.885 |
| global target support | 5,289.413 | 2,790.456–9,482.654 |
| two-hop DATAFLOW | 4,688.115 | 4,037.084–4,794.966 |

来源：[独立本地证据审计](query-performance-evidence/2026-11-11/complex-p95/historical-summary.md)、[完整纠正审计 JSON](query-performance-evidence/2026-11-11/complex-p95/historical-cpu-corrected-audit.json)及同目录归档原始报告。20 个样本的 P95 是第 19 个顺序统计量，不能把单轮细小变化当作精确结论。

**CPU 单位已纠正，原始记录保留。** 早期 macOS 快照把 Mach tick 直接当作纳秒；同机 timebase 已独立验证为 `125/3`，旧快照在审计中派生纠正，并以 wait4/getrusage 交叉核对。控制器修复为 `3d1d19c1`；[timebase 证明](query-performance-evidence/2026-11-11/complex-p95/darwin-timebase-proof.json)可复查。此修正没有改写 HTTP 延迟、原始报告、wait4 CPU 或 kernel RSS。

纠正后的 Attempt315 完整混合压力 CPU 为 **1,185.129 秒**；查询进程生命周期 kernel peak RSS 为 **32,085.484 MiB**，包含加载及全部执行阶段，不是可清零的压力窗口峰值。原 main 失败进程的生命周期 peak RSS 为 **29,907.828 MiB**，完成工作量不同，不能拿二者直接判断 5% 限制或收益。原 main 没有完整压力 CPU/P95；原始基线失败不会因为有可工作的增量参考而消失。配对资源比较仍须来自同范围、完整完成的工作负载。

### Attempt316 配对轮次：AB、BA、AB 三轮已完成审计

第一轮为 AB 顺序，A=保留的315、B=累计316；每臂六类查询各20个压力请求，4个 worker，另有首次请求与两次预热。共276份完整响应的字段类型、顺序、重复、缺失/null及 provenance 均独立核对通过，图及查询身份一致，无失败请求。以下表格只描述这一轮，不与315先前单独 pilot 或其他轮次混合。

| 查询 | 315 P95 ms | 316 P95 ms | 绝对变化 ms | 变化 |
| --- | ---: | ---: | ---: | ---: |
| caller dependency rollup | 3,949.706 | 2,536.270 | -1,413.436 | -35.786% |
| global target support | 4,388.318 | 2,776.441 | -1,611.877 | -36.731% |
| two-hop DATAFLOW | 4,265.109 | 4,061.495 | -203.614 | -4.774% |
| DATAFLOW degree histogram | 8,959.953 | 5,650.064 | -3,309.889 | -36.941% |
| OPTIONAL DATAFLOW sinks | 16,655.825 | 10,696.064 | -5,959.761 | -35.782% |
| dependency co-use | 38,919.123 | 2,264.064 | -36,655.059 | -94.183% |

| 首轮资源与完整压力阶段 | 315 | 316 | 绝对变化 | 5% 资源上限观察 |
| --- | ---: | ---: | ---: | --- |
| loading CPU，秒 | 17.560 | 18.262 | +0.702（+3.995%） | 在界限内 |
| loading kernel peak RSS，MiB | 3,804.578 | 3,799.484 | -5.094（-0.134%） | 在界限内 |
| 完整混合 pressure CPU，秒 | 1,130.778 | 545.562 | -585.216（-51.753%） | 在界限内 |
| query-process lifetime kernel peak RSS，MiB | 32,915.859 | 26,668.109 | -6,247.750（-18.981%） | 在界限内 |
| 完整 pressure 阶段 wall，ms | 329,730.649 | 141,781.215 | -187,949.434 | 非资源限制项 |

CPU 是 server user+system，pressure 为固定六类混合窗口；RSS 是各自新进程生命周期的内核峰值，包含加载、first-use、warmup及pressure，**不是单查询或可重置 pressure 窗口峰值**。pressure wall 包含客户端验证间隙，不等于整个构建/加载/查询流水线端到端时间。首轮四项资源观察均在各自5%界限内，但不能替代其他轮次、其他工作负载或单用例资源证明。此轮已直接记录 Mach ticks/timebase，无须历史 CPU 派生纠正。

来源：[首轮独立审计](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-01-audit.json)、[逐项摘要](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-01-summary.md)、[原始归档及 SHA manifest](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-01-manifest.json)。20个样本的 P95 仍是第19个顺序统计量；其他查询可能受 co-use 减少资源竞争影响，不能把所有变化直接归因于它们各自的执行实现。

第二轮独立采用 BA 顺序，仍是每类每臂20样本、并发4。另276份完整类型响应通过独立审计，图、查询、二进制及输入身份与第一轮一致。结果如下，**不把两轮样本合并计算 P95**：

| 查询 | 第二轮315 P95 ms | 第二轮316 P95 ms | 绝对变化 ms | 变化 |
| --- | ---: | ---: | ---: | ---: |
| caller dependency rollup | 3,785.510 | 2,537.866 | -1,247.644 | -32.958% |
| global target support | 4,146.769 | 2,787.224 | -1,359.545 | -32.786% |
| two-hop DATAFLOW | 4,148.833 | 4,061.434 | -87.399 | -2.107% |
| DATAFLOW degree histogram | 9,363.787 | 5,660.043 | -3,703.744 | -39.554% |
| OPTIONAL DATAFLOW sinks | 15,986.252 | 10,619.568 | -5,366.684 | -33.571% |
| dependency co-use | 37,186.954 | 2,268.983 | -34,917.972 | -93.898% |

| 第二轮资源与完整压力阶段 | 315 | 316 | 绝对变化 | 5%资源上限观察 |
| --- | ---: | ---: | ---: | --- |
| loading CPU，秒 | 17.894 | 17.671 | -0.224（-1.251%） | 在界限内 |
| loading kernel peak RSS，MiB | 3,796.625 | 3,797.672 | +1.047（+0.028%） | 在界限内 |
| 完整混合 pressure CPU，秒 | 1,111.713 | 546.922 | -564.791（-50.804%） | 在界限内 |
| query-process lifetime kernel peak RSS，MiB | 32,680.109 | 25,998.078 | -6,682.031（-20.447%） | 在界限内 |
| 完整 pressure 阶段 wall，ms | 319,971.137 | 142,334.671 | -177,636.466 | 非资源限制项 |

第二轮继续满足四项独立资源界限；边界及不确定性与第一轮相同。两跳的绝对改善只有87.399 ms，远小于co-use，不能用它的小幅百分比作为主要优化成果。见[第二轮审计](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-02-audit.json)、[摘要](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-02-summary.md)和[原始归档manifest](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-02-manifest.json)。

第三轮为预声明的 AB 顺序，仍是每类每臂20样本、4并发。另276份完整响应的类型、顺序、provenance及所有样本通过审计；二进制、查询、fixture和完整结果与前两轮保持一致。

| 查询 | 第三轮315 P95 ms | 第三轮316 P95 ms | 绝对变化 ms | 变化 |
| --- | ---: | ---: | ---: | ---: |
| caller dependency rollup | 2,636.761 | 2,522.399 | -114.362 | -4.337% |
| global target support | 4,041.096 | 2,787.069 | -1,254.026 | -31.032% |
| two-hop DATAFLOW | 4,125.299 | 4,070.277 | -55.021 | -1.334% |
| DATAFLOW degree histogram | 7,466.682 | 5,649.511 | -1,817.172 | -24.337% |
| OPTIONAL DATAFLOW sinks | 13,816.374 | 10,554.603 | -3,261.771 | -23.608% |
| dependency co-use | 31,239.094 | 2,274.597 | -28,964.497 | -92.719% |

| 第三轮资源与完整压力阶段 | 315 | 316 | 绝对变化 | 5%资源上限观察 |
| --- | ---: | ---: | ---: | --- |
| loading CPU，秒 | 17.708 | 18.185 | +0.477（+2.695%） | 在界限内 |
| loading kernel peak RSS，MiB | 3,797.281 | 3,796.859 | -0.422（-0.011%） | 在界限内 |
| 完整混合 pressure CPU，秒 | 1,081.894 | 543.809 | -538.085（-49.735%） | 在界限内 |
| query-process lifetime kernel peak RSS，MiB | 32,301.609 | 26,852.344 | -5,449.266（-16.870%） | 在界限内 |
| 完整 pressure 阶段 wall，ms | 297,177.209 | 140,917.865 | -156,259.344 | 非资源限制项 |

第三轮全部六类P95继续下降，四项资源各自在5%界限内。315的 co-use P95 从第一轮38,919.123 ms降至第三轮31,239.094 ms，其他慢项也存在跨轮波动；这些绝对值变化必须保留，不把首轮更大的差值当成唯一结论。三轮是各自独立的20样本观察，不产生合并P95，也不替代下一份100样本确认。

来源：[第三轮审计](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-03-audit.json)、[摘要](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-03-summary.md)、[原始归档manifest](query-performance-evidence/2026-11-11/complex-p95/paired-pilot-03-manifest.json)。

| 后续预声明验证 | 当前证据状态 |
| --- | --- |
| 第二轮 BA，20样本/类/臂 | 已独立审计，单独结果见上表 |
| 第三轮 AB，20样本/类/臂 | 已独立审计，单独结果见上表 |
| 独立 BA 确认，100样本/类/臂 | 正在执行，完成后独立审计；不与20样本轮次池化 |
| 原 main→316，原73类查询回归 | 待执行/审计，单独结论 |

当前决定是保留这个已显示正收益的增量并继续验证，最终验收尚未成立。**原 main 的复杂查询 pilot 失败仍保留：不存在可与316构成压力 P95/CPU/RSS 恢复比较的原 main 完整样本。**

源代码和实测二进制的对应关系见[身份映射](query-performance-evidence/2026-11-11/complex-p95/README.md#source-and-executable-identities)及[provenance.json](query-performance-evidence/2026-11-11/complex-p95/provenance.json)。315/316先构建并冻结二进制、后提交源码；提交映射依据当时的会话构建记录，原始run report只记录binary SHA和输入，不能声称它含有独立的产品构建manifest或二进制内嵌源码证明。

[PR212](https://github.com/johnsonlee/graphite/pull/212) 在 `f6d2c565` 的十项CI检查均已成功，包括[必需benchmark gate](https://github.com/johnsonlee/graphite/actions/runs/42960174517)和[JVM检查](https://github.com/johnsonlee/graphite/actions/runs/42960174879)。这是该提交的正确性/完整执行/OOM证据，benchmark时延仍为report-only；之后新增报告内容形成的新HEAD需要对应检查，不能沿用旧HEAD状态冒充。

## 剩余优化空间：以复杂查询实际路径为中心

以下是源码支持的待测假设，不是收益预测。316首轮中，尾延迟重点已转向 OPTIONAL sinks（P95 10,696.064 ms）、degree histogram（5,650.064 ms）和两跳 DATAFLOW（4,061.495 ms）；需用后续轮次确认这一排序，不能仅凭首轮就启动新的优化或扩大本轮范围。

| 路径 | 已有实现与剩余成本 | 下一项可测假设及限制 |
| --- | --- | --- |
| OPTIONAL sinks | [`pipeline.rs`](../backend/cypher/src/engine/pipeline.rs) 的 `exec_match` 收集全部匹配/null 行，然后 WITH 聚合 | 限定 OPTIONAL→WITH count 融合，用原 Matcher 直接喂聚合状态，完成 admission 后逐个消费初始 seed。消除 `M=Σmax(outdegree,1)` 条中间行，但初始 MATCH 和约 N 个分组仍存在；多数节点为 sink/度数 1 时内存改善可能有限。保持 `count(b)` 的 null 语义，不改为 `count(*)` 或直接 CSR degree |
| degree histogram | [`partition.rs`](../backend/cypher/src/engine/partition.rs) 的 Hop 规划拒绝 Content 级节点身份；[`pipeline.rs`](../backend/cypher/src/engine/pipeline.rs) 的 `fused_rows` 已逐匹配聚合 | 先记录每边 Row/Key 分配及每节点 group state 的占比，再评估受限的紧凑度数状态。它已经流式执行，不能重复计算 OPTIONAL 的中间行消除收益；有效 endpoint、重复节点/平行边和错误边界仍须证明 |
| rollup/global support/co-use 的第一层分组 | [`partition.rs`](../backend/cypher/src/engine/partition.rs) 的 `scalar_column_groups` 只优化单列；两列 `caller_class/callee_class` 在 `node_rows` 中仍为每节点创建 `Vec<StrId>` | 专用 `(StrId,StrId)` 分区计数可避免重复 pair 的临时 Vec，只给 distinct pair 构造通用 key；保持首次出现顺序、权重、取消与坏输入回退。优先验证该分配是否占主要绝对时间 |
| 两跳 DATAFLOW | [`matching.rs`](../backend/cypher/src/engine/matching.rs) 的 `edges_of` 每次展开 collect 邻接；路径行仍需 clone、构造 group key 与 DISTINCT 状态 | 用真实多图 profile 区分邻接复制、Row 和去重成本后再做局部改造。迭代邻接可能改变提前终止时损坏尾部的错误可见性，不能无证明地普遍替换 |
| 完成聚合后的排序 | 通用路径先完成全部 group，再稳定排序 | 若排序占比显著，可在证明值域和所有 group 已 finalize 后做 bounded selection；不跳过 loser 的表达式、溢出或 provenance，也不把排序优化当成上游扫描已经恢复 |

316 仍枚举每个 caller 的全部 `d²` target 配对，且保留每个 distinct pair 的完整计数状态；**只为 K 个赢家物化 Row，不等于全部状态空间只有 O(K)**。输入数、唯一 pair 数和必须输出的数据量仍有下界。后续 profile 应记录输入节点数 N、匹配/扩展数 M、分组数 G、caller fan-out 分布和分配量，不从源码推断固定提速百分比。

### Attempt316 的 64/65 边界与生产适用范围

[`unwind_groups_compact.rs`](../backend/cypher/src/engine/unwind_groups_compact.rs) 的两个字典分别限制为一次查询输入中至多 64 个 DISTINCT carried 字符串值、至多 64 个 provenance 字符串。它不是 target 类名最多 64，也不是每个 pair 分别最多 64。

- 当前 catalog 的 carried 值是 graphId。50 个参与图通常在范围内；65 个不同 graphId 且贡献输入行的图通常触发回退。
- 加载超过 64 图不必然回退：没有贡献输入行的图可能不进入字典。反之，若 DISTINCT 对象换成 caller，即使图数少也可能超过 64。判断依据是实际输入字典，不是服务器注册图数量。
- 超过任一上限、出现空或非规范 provenance，均在累计 pair 前回退到 Attempt315 streaming；不会截断图或结果，但也不会得到 316 的紧凑状态收益，并会付出一次有限预处理成本。
- 64/65 测试只证明正确性和回退。当前真实 64 图测量不能证明 65、96 或更多生产图的延迟/CPU/RSS边界。若要扩展 bitset，需在相应真实持久化多图工作负载上独立比较，不以合成 fixture 的时延代替。

实验过程与限定见 [Attempt315/316 历史](webgraph-optimization-attempts.md)。三轮配对结果见上文；其余轮次与累计验收仍待完成。目前不宣称生产事故恢复、全系统倍数提升或性能验收通过。

---

下文保留初始 PR211 历史评估及原始数据，版本范围固定为 `58215b98`/对应 CI tree。其百毫秒查询、缺少资源字段和“当时未修改生产代码”的说明不适用于后续本地候选；旧建议不覆盖上文已声明的新实验协议。

## 历史评估：PR211 的 73 类查询

### 历史被测版本的身份

初始评估复用 [PR 211 的完整 CI 运行](https://github.com/johnsonlee/graphite/actions/runs/42912084315)，而非重跑未变化的代码与输入：

| 项目 | 身份或结果 |
| --- | --- |
| 被测 base | `907bc9adf24dbd238d72c08127827e6481cc6fe7` |
| 被测 head | `2275a50661a26cbcc397d8881e50a976571bee36` |
| 初始目标 `58215b98` 与被测 head 的共同 Git tree | `90b0c0f7fbedbce64850457eaad221d28b2436d0` |
| base 与 head 的共同原生二进制 SHA256 | `b2691765c9b24c1da0db603c220642160ffc2173b86c9c7f05bfdbec521a4d94` |
| 原报告 | `passed: true`，`performanceAcceptance: false` |
| 完整报告 artifact | [13809622988](https://github.com/johnsonlee/graphite/actions/runs/42912084315/artifacts/13809622988) |
| 请求、响应及收据 artifact | [13809902215](https://github.com/johnsonlee/graphite/actions/runs/42912084315/artifacts/13809902215) |

初始核验时，固定提交 `2275a506` 与 `58215b98` 的整树差异为空，因此该被测 head 的结果可作为 `58215b98` 的环境限定观察。此身份结论不指向随后包含 Attempt315/316 的开发分支 HEAD。两臂原生二进制完全相同，**下文 base/head 差值仅展示波动，不能归因成查询实现的改善或回退**。图 manifest 文件哈希不同，不能因此直接断言输入文件逐字节相同；控制器验证了真实源 JAR、分片身份、查询与完整结果的匹配。

PR 评论仍显示“No complete report was produced”，但下载的 report artifact 实际包含完整通过报告。这里以 artifact 内容和原始请求为依据，不以评论摘要替代证据。

针对这份历史 CI 证据的独立验证完成：

- 两臂各 4,399 请求，共 8,798 请求；每臂包括 73 次首次 oracle、146 次 warmup、1,460 次 pressure、2,720 次 isolated。
- 全部请求记录 passed，全部响应收据 HTTP 200 且 body 完整；逐份核对 body 字节数与 SHA256。
- 全部响应 `graphCount=64`，`rowCount` 与实际行数一致；73 类查询的解析后完整响应在重复请求及两个版本之间一致，保留数组顺序及字段缺失语义。
- 从原始记录重算全部 pressure/isolated 分位数，与报告一致，未剔除慢样本。范围及语义断言也由原控制器通过。

归档的[独立审计结果](query-performance-evidence/2026-11-11/audit.json)、[完整响应审计](query-performance-evidence/2026-11-11/body-audit.json)、[原报告](query-performance-evidence/2026-11-11/ci-report.json)及压缩请求记录可复查。这里的正确性成立范围是对应查询、输入和检查，不是全部 Cypher 语义的形式证明。

### 测量协议及限制

数据来自 Android-all、Tika、Hive、Kotlin compiler 四组真实 JAR，各分 16 片，共 64 个持久化图。每条被测请求范围是全部 64 图，不是加载多图后只查单图。分片之间仍属于四组来源，不能直接代表 64 个任意独立项目、跨项目调用连接或任意生产图规模。

生产 Rust HTTP 服务执行 73 类查询。每类先 oracle，再两次 warmup；pressure 为四个 worker、按 round 交错、每类 20 次。每类 pressure p50 小于 100 ms 才追加并发 1 的 isolated 40 次；本次有 68 类符合条件。分位数逐类计算，不将不同查询混成一个 p95。20 次 nearest-rank p50/p95 分别是排序后第 10/19 个样本，尾部估计较粗。

请求时延覆盖发送请求到**完整 HTTP body 消费**，包括每次 `Connection: close` 的连接成本；JSON 校验、哈希和写盘在 client-cycle 中。worker 完成校验后才发下一请求，因此这是**带验证间隙的闭环混合负载**，不是持续饱和负载、固定到达率测试或系统最大 QPS 证明。客户端完整读完的边界也不同于服务端完成响应构造、释放 permit 的时刻。

isolated 位于 pressure 后，缓存与热状态不同；首次 oracle 不是每个用例独立冷启动，文件系统缓存未受控。单批 20 次的 p95 不应承载精确的小幅回归结论。本报告列出 min/max 展示实际变异，不补造置信区间；独立多轮和固定采样设计留给候选验收。

该历史 CI 报告没有查询窗口总 CPU 和进程峰值 RSS 数据；新本地控制器的 CPU/RSS 观测见上文，二者不能混为同一证据。OOM gate 通过不能替代 CPU/RSS 各不超过基线 5% 的证据。协议详见 [benchmark README](../benchmark/README.md) 及 `benchmark/src/native.rs:482,574,1144`。

## 历史 73 类查询中的慢项

下表单位全部为 ms，按 head p95 降序。Δ 为 head 减 base，先列绝对变化，不将百分比作为优先级依据。这些 base/head 是相同原生二进制的两臂观察。

| 查询 | base p50 | head p50 | Δp50 | base p95 | head p95 | Δp95 | head 最小至最大 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 关系类型与两端标签统计 | 157.419 | 143.606 | -13.813 | 290.189 | 310.032 | +19.843 | 129.879–315.913 |
| callee_class 分组计数 | 190.034 | 182.226 | -7.808 | 248.669 | 250.179 | +1.510 | 147.377–282.826 |
| WITH 分组后过滤 | 184.041 | 191.033 | +6.992 | 239.027 | 208.443 | -30.584 | 136.681–233.875 |
| 关系遍历总数 | 152.165 | 153.119 | +0.954 | 173.334 | 167.937 | -5.397 | 133.266–170.480 |
| callee_name 等值搜索 | 108.841 | 108.999 | +0.159 | 129.808 | 122.533 | -7.276 | 84.731–125.328 |
| targeted 四属性搜索 | 57.945 | 60.485 | +2.540 | 90.198 | 103.815 | +13.617 | 1.294–109.123 |
| 过滤后的关系遍历 | 64.039 | 69.052 | +5.013 | 97.745 | 89.612 | -8.133 | 50.278–91.110 |
| targeted class pair 搜索 | 2.796 | 5.954 | +3.157 | 53.230 | 61.413 | +8.183 | 0.750–81.694 |
| callee_class DISTINCT | 20.973 | 18.114 | -2.858 | 38.521 | 38.350 | -0.172 | 14.097–43.468 |

对应的主要查询形状如下；完整 catalog 在 [native_queries.json](../benchmark/src/native_queries.json)。

```cypher
MATCH (a)-[r]->(b)
RETURN labels(a) AS a, type(r) AS t, labels(b) AS b, count(*) AS c
ORDER BY c DESC LIMIT 40

MATCH (n:CallSite)
RETURN n.callee_class, count(*) AS c ORDER BY c DESC LIMIT 20

MATCH (n:CallSite)
WITH n.callee_class AS c, count(*) AS k WHERE k > 5
RETURN c, k LIMIT 50

MATCH (a)-[r]->(b) RETURN count(*)

MATCH (n:CallSite) WHERE n.callee_name = 'valueOf'
RETURN n.caller_class LIMIT 200
```

全 catalog 的 head 分布为：p50 小于 10 ms 的 64 类、10–100 ms 的 4 类、至少 100 ms 的 5 类；p95 对应为 56、11、6 类。这说明投入应聚焦慢项及其对其他请求的影响，不能用低基数查询的大百分比变化取代绝对用户收益。

在该历史版本和 CI 环境中，`ORDER BY caller_class LIMIT 200` 的 p50/p95 为 **3.451/11.261 ms**，`collect(...)[0..10]` 为 **1.615/5.991 ms**，`count(*)` 节点计数为 **1.386/3.418 ms**。旧记录中的秒级 ORDER BY/collect 已不是该历史 CI 版本的观测现状；旧单图 JMH 数据也不用于本评估的优化收益或性能验收。

### 并发干扰比快查询本体更值得研究

| 查询 | 四 worker p50/p95 | 后续 isolated p50/p95 |
| --- | ---: | ---: |
| targeted 四属性搜索 | 60.485 / 103.815 | 0.627 / 0.653 |
| targeted class pair | 5.954 / 61.413 | 0.632 / 0.669 |
| 过滤后的关系遍历 | 69.052 / 89.612 | 0.932 / 0.989 |
| DISTINCT | 18.114 / 38.350 | 6.943 / 8.683 |

差异说明混合负载环境值得优先诊断。不能把这些差值全算为可回收优化空间：阶段顺序、缓存、宿主噪声、内存带宽和执行任务竞争尚未拆开。前五个最慢用例因历史协议的 p50≥100 ms 筛选规则，没有 isolated 数据，不能将“无数据”解释为无并发影响。后续针对慢查询的诊断必须显式包含这些用例，不能沿用 P50 筛选来决定哪些尾延迟问题值得研究；历史样本及原协议保持不变。

另有本机 M3 Max 的旧 PR 211 证据，其原生源码闭包与 HEAD 相同，但 JVM producer/build 有变化，且存在背景任务和固定 AB 顺序限制。该环境 group-count p50/p95 约 42.688/46.017 ms，关系统计 14.613/15.036 ms。它提示绝对延迟强依赖环境；不与 Linux 样本混合，也不冒充当前整树验收。原资料位于 `/private/tmp/graphite-pr211-final-benchmark/fresh-run/` 与相邻独立审计文件。

## 初始版本的实现审计（历史范围）

初始目标 `58215b98` 已有解析缓存、索引聚合、CSR、字符串字典/trigram、单跳锚定、分区加权聚合、流式 LIMIT、bounded top-k、紧凑投影与标量直接 JSON 序列化。注册表锁只覆盖快照获取，多图扫描已使用 Rayon。继续做“加索引、加缓存、加并行”这种笼统改造，容易重复已有能力或突破资源约束。

### 第一优先级是关系聚合的重复扫描

按当前查询形状与源码分派，`engine/pipeline.rs:421` 可选择 `PartitionPlan`；`engine/partition.rs:981` 对各图并行执行 `sweep_edges`。`partition.rs:1635,1675` 每次构造目标节点 tag 数组，扫描 CSR，再按 source tag、关系 label、target tag 统计并记录首次出现位置。本次没有运行路径 telemetry，后续 profile 需确认实际分派与耗时占比。

关系 histogram 需要这些分类，但纯 `MATCH (a)-[r]->(b) RETURN count(*)` 也会做分类后再加权聚合。两者直接对应 p95 310.032 ms 与 167.937 ms 的已测慢项。

**候选一：为严格匹配的纯 directed 单跳 count 提供有效边总数特化。** 可避免不必要分类，先保留原扫描而减少工作，再考虑经验证的摘要复用。不能直接返回 `Graph::edge_count()`：后者是底层 targets 长度，不证明所有端点都满足 matcher 的可见标签与有效性语义。

**候选二：不可变图的有效边统计摘要。** 缓存足够小的类型 histogram、有效总数及必要的 first encounter 信息，避免重复请求重扫全部边。键必须包含图实例/内容版本、方向及适用语义，不能只用可复用的图名称。失配形状继续走原路径。

这是初始 73 类工作负载审计识别的结构性空间，仍需测量扫描占总时间的比例。懒建摘要的首次成本必须记在冷查询中；持久化摘要必须单列 build/save、load、CPU、RSS，不能把查询工作移到启动后宣布消失。节点 tag 数组常驻可能比小 histogram 更占 RSS，不建议先缓存全量节点级结构。

### 第二优先级是跨图分组的重复字符串合并

`engine/fastpath.rs:157,225` 和 `engine/grouped_with.rs:112,160` 均复用 `bounded_call_site_groups`。`engine/fastpath/bounded_groups.rs:67` 建全局 HashMap，遍历各图的 CSR property values，用字符串 hash 合并计数；`:108` 已用 heap 选 K，`:137` 只给获选项构造 Row。

因此这两条 p95 分别为 250.179 ms、208.443 ms 的查询形状已有 top-k 与 CSR 快路径；当所有 source 的索引证明成功时，无需解码每个 CallSite。本次未记录运行时路径，仍需排查索引不可用或证明失败导致的回退。在快路径命中的前提下，值得研究的是跨图所有 distinct 字符串在每次请求上的重复 hash、合并和 provenance 工作。

候选包括按字符串有序的多路合并、减少字符串重复处理、按不可变 source 集合缓存受限的合并摘要。索引已提供 posting length，单纯缓存单图计数未必有收益。全局摘要缓存需限制总量，并覆盖 source 增删替换、相同图名不同内容、并发构建、取消与淘汰；固定热 key 的好成绩不代表变化工作集收益。

`Graph::validated_ordered_call_site` 在 `backend/storage/src/graph.rs:721` 已用 OnceLock 缓存验证结果，不能把热路径慢归因为每次重验整份 CSR。合并 admission 中的小重复检查可做后续整理，不作为首轮收益目标。

### 第三优先级是并发调度与等值查询归因

`engine/scan.rs:760,882,1320` 已按 source 批次并行准备、按原序消费并支持 LIMIT 提前停止；`partition.rs:981` 也使用并行 source 扫描。这些任务共享 Rayon pool，并不是每请求创建一套线程池。

需要验证四个请求叠加时的任务排队、内存带宽和临时分配。可研究每请求 fan-out、批次大小和公平性，但不得靠减少已接受请求、提高 429 或提前释放 permit 改善 p95。默认并发门限 4 已存在，增加线程也不能自动解决带宽竞争。

等值搜索 `callee_name='valueOf'` 已可在 `scan.rs:1494` 用字典 binary seek，再走 postings 与紧凑投影。它仍出现 108.999/122.533 ms，必须先区分实际快路径命中、source pruning/准备、匹配分布、冷 mmap、并发任务等待和响应构造。不能从延迟大就推断“等值搜索没用索引”。若 profile 证明准备成本突出，再比较单 equality leaf 的借用式 sid/posting 迭代，减少 memo、Vec 和计划重建。

### 正确性与可用性优先检查

`backend/explore/src/query_job.rs:15` 与 `guard.rs:211` 已正确将 permit 保留到执行和响应构造完成。`routes.rs:1476,1598` 对复合值仍递归物化，并构造完整响应 String；`materialize.rs:24` 的物化和 serializer 内未见取消轮询。

由此存在需要验证的风险：大 list/path/node 返回可能在执行完成后长时间序列化，超时或取消难以及时停止，延长容量占用。**这是源码识别的风险，尚未在初始目标版本复现该故障**。先补针对性行为验证，再决定序列化检查点与直接 serializer 设计，不提前释放 permit。初始审计的网络探针也不能证明 disconnect cancellation 或 native work-budget 支持；README 已明确限定这些能力。

标量已经直写 JSON，复合值则按字段临时物化，不是整个响应 JSON 树全部同时常驻。可先移除 wrapper Vec、减少复合对象分配，再研究完整流式输出。需保留 null 字段省略、float 格式、行序、属性覆盖与 provenance；不能用截断大结果获得时延改善。

### 其他值得保留的方向

| 方向 | 具体剩余成本 | 优先级与边界 |
| --- | --- | --- |
| 通用多 pattern / OPTIONAL | `matching.rs:159,225` 按源顺序枚举；`pipeline.rs:668` 某些回退将匹配全部收集 | 真实慢查询命中后再升优先级；保留 LIMIT、首次遇见顺序、异常可见性和关联变量 |
| Hop 锚点选择 | `hop.rs:38` 优先取第一个可下推 conjunct，而非最小候选 | 可利用已有 posting 大小；不能重做 ScanPlan 已有成本优化或引入更贵估计 |
| Row 与邻接分配 | `matching.rs:202,347,414,476,572` clone Row、collect 邻接 | 大量匹配、多跳更可能受益；先测分配占比，再评估可回滚 slot 或迭代器 |
| 通用 ORDER BY LIMIT | `pipeline.rs:1217` bounded 入场受限；`:2754` 普通路径全量排序 | 只扩展可证明安全的类型与形状；当前 catalog 的 ordered 快项不值得优先重做 |
| 冷索引与相同 term 并发 miss | `graph.rs:983`、`columns.rs:232` 懒建；`scan.rs:3494` cache→计算→remember 可重复计算 | 有界 singleflight 可研究，需新 term、淘汰、取消后重试和冷请求；已有每列 32 项/512K IDs 限额 |
| parser cache | `parser.rs:67` 全局 Mutex 下 Vec 线性查找和移动 | 已有 1024 项 dialect/text 缓存；高 QPS 短查询 profile 证明占比后再换 O(1) LRU |
| registry snapshot | `registry.rs:275` 每请求构建 source 快照 | 锁持有区很短，无争用证据；高图数下才考虑 generation 快照复用及热替换 RSS |

通用变长路径的组合爆炸和必须输出的数据量仍有下界；不能承诺靠微优化消除全部复杂查询成本。当前 catalog 对 OPTIONAL、复杂相关多 pattern、深路径、大复合输出及参数工作集覆盖不足，源码机会不能冒充这些形状的性能实测。

## 优化空间如何量化

在该历史 CI 测量环境，310 ms 级 histogram 和 200 ms 级分组比 1–5 ms 搜索具有更大的绝对收益预算。但“当前耗时”不是“可回收耗时”。建议用 profile 的可优化占比 f 与局部提速 s 计算条件估计：

`新总耗时 / 旧总耗时 = (1 − f) + f / s`

例如，只有实测证明某项占 60%，且该项确实加快两倍，总时间才有约 30% 下降的理论估计；若占 20%，即使该项完全消失，总改善也至多 20%。这是假设计算，未测得本项目的 f 或 s；排队、缓存与争用还可能使实际结果偏离。

研究顺序以慢查询的绝对 P95 和复杂聚合的实际成本为依据。以下为重点工作队列；共享扫描路径的优化可同时覆盖第一项和第四项。未指定业务 SLO，也没有候选实测，不预设统一的下降百分比。

| 优先顺序 | 查询 | 当前 P95 ms | 优化重点 |
| --- | --- | ---: | --- |
| 1 | 关系 histogram | 310.032 | 重复边扫描、分类统计和并发尾部成本 |
| 2 | group-count | 250.179 | 跨图字符串分组合并及竞争 |
| 3 | WITH aggregate | 208.443 | 分组后过滤的重复合并工作 |
| 4 | count-traversal | 167.937 | 纯总数查询避免冗余分类扫描 |

候选结论必须列出相同代表性多图负载下 baseline P95、candidate P95、绝对降低毫秒数、样本量及不确定性。P95 未改善的候选不能仅凭 P50 改善宣布达到目标；P50 仍原样记录，避免隐藏行为变化。慢请求对应的执行阶段和并发竞争需要单独定位，不能用平均 profile 占比直接预测 P95，也不能把不同阶段的 P95 相加。

这种量级足以值得针对性实验；对几毫秒查询重复追逐百分比改善，收益优先级更低。不存在可用的生产请求频率、SLO 和瓶颈 profile，因此不计算全站平均节省、最大吞吐或总体加速倍数。若摘要复用显著减少 O(E) 扫描，个别热聚合可能有更大收益，但冷首次成本与资源限制可能成为决定因素。

## 验证路径与反馈周期

### 初始历史评估为何复用 CI 流水线

初始评估已获取与固定目标 `58215b98` 整树相同的完整测量，并独立复核全部样本、响应收据及结果。最新 CI 的主 benchmark job 从 22:11:44 至 22:46:56 UTC，约 **35 分 12 秒**，harness job 另约 58 秒。仅一臂 real64 构建/保存约 443 秒，两臂还包含 whole-JAR 正确性、编译、启动及传输，不能用总时间除以 worker 数估算。

初始阶段读取已有 report、下载约 20.3 MB raw artifact，再进行秒级离线复算，形成短反馈周期。当时未修改生产代码，未创建性能候选。随后已新增真实多图复杂查询控制器和 Attempt315/316，不能把这段历史说明解释为本分支仍然只有文档变更。两阶段都没有使用单图或合成性能数据。

### 初始建议与持续有效的边界

第一步先做稳定性行为检查和最小 producer→consumer 正确性 smoke，包括准备产物、传输/恢复、实际加载消费；合成 fixture 只用于正确性。确认路径和输入可消费后，再启动昂贵测量。现有本机真实 64 图在 `/private/tmp/graphite-pr211-final-benchmark/fresh-run/results/frontend/{base,head}/graphs/`，可用于准备查询诊断，但必须先核实 manifest、图格式及 producer 身份；其旧 producer 不能替代最新完整构建验收。

初始建议以 `58215b98` 为基线；实际本地测量中它在预热失败，Attempt316 的增量比较以完整完成的 Attempt315 为工作参考，不能代替原 main。后续仍以明确固定的提交身份为基线；若主张恢复某历史回归，最终还需与该回归前的接受基线比较，不能只比父提交。每个假设独立 commit，按既有 `docs/*-optimization-attempts.md` 续写实验历史；有效增量保留组合，最终累计候选单独验收。

以下保留初始建议；实际已声明的本地配对轮次和采样量以本文顶部链接的 PROTOCOL.md（含 Attempt316 amendment）为准，不用下表或旧示例替换实际协议：

| 阶段 | 预先固定的工作 | 产出 |
| --- | --- | --- |
| 正确性和可用性 | 相关模块测试、lint、两个 dialect 的相关语义；大输出取消、超时、permit 释放与恢复 | 先通过行为门，不用时延掩盖错误 |
| 路径定位 | 真实多图慢项记录所选路径、source 数、扫描节点/边、候选数、聚合 key 数、输出字节；低扰动计时分开 | 解释扫描、合并、调度和序列化占比；profile 运行不混入验收样本 |
| 增量 A/B | 固定真实 64 图、4 并发、完整 body；单个候选对 HEAD；匹配 CPU/内存/缓存/构建设置 | 全部样本与失败、P95 绝对毫秒改善及不确定性、CPU、峰值 RSS；P50 为辅助记录 |
| 冷热与变化工作集 | 同 term 热查询、新 term、淘汰后查询、首次索引；固定顺序或预声明交替顺序 | 防止热缓存专用优化及工作转移 |
| 累计验收 | 原有 73 类覆盖加具体 changed-path 用例；在受控机器多轮交替 baseline/candidate | 各用例独立结论，不能用一种工作负载抵消另一种退化 |
| 容量稳定性补充 | 如评估饱和能力，独立固定到达率/持续负载协议，记录 accepted/rejected、错误、超时及资源 | 不与现有带间隙闭环数据混算，也不只报成功请求 p95 |

诊断先聚焦关系 histogram、group-count、WITH aggregate 和 count-traversal；等值搜索作为后续慢查询归因及混合负载保护项，避免 73 类全部重跑后才发现数据不可用。最终仍保留完整相关覆盖。可在专用诊断协议中预声明每类每臂 200 个请求、三个独立交替批次、并发 4；这个样本量是后续建议，不是本次已执行。固定规则逐轮以 nearest-rank P95、绝对毫秒变化、原始范围及预声明的区间估计评价候选，另保留 P50 辅助数据，不能将不同查询池化。需要扩大官方协议时先按仓库 trusted harness 流程审查，而不是通过临时开关绕过。

CPU 按各操作窗口记录 user+system，RSS 记录独立进程峰值；冷启动与热查询分开，考虑峰值指标不能在同一进程随意重置。控制器、客户端和 server 资源区分；严格比较同环境、同图、同结果的完整消费边界。JAR JVM 固定 4 GiB，任何 JVM 不超过 8 GiB，不能靠增堆重试 OOM。Rust RSS 与 JVM heap 是不同约束。

### 历史 CI 证据能与不能支持的结论

| 维度 | 该历史 CI 证据的结论 |
| --- | --- |
| 结果正确性 | 对 73 类真实多图请求、两臂全部响应一致和原 gate 检查成立；HEAD 的 Rust/TCK 等 CI 也通过 |
| 可用性与稳定性 | 原 native 并发/非法输入/恢复检查和 OOM gate 通过；长时间饱和、断连取消、大结果序列化取消仍无完整结论 |
| 查询 p50/p95 | 当前环境的实测观察已验证；无新候选，未声称优化或回归恢复 |
| 加载 | head registry-ready 47,739.270 ms；启动至首次 oracle 完成 49,290.181 ms，各一次、文件缓存未受控 |
| build/save | head real64 443,173.160 ms，base 448,747.267 ms，差 -5,574.106 ms；单次报告观察，不能代替查询结论 |
| CPU | 该 CI 报告未测原生查询 CPU，不能证明 ≤5% 约束；后续本地资源证据见顶部 |
| 峰值 RSS | 该 CI 报告未测，不能证明 ≤5% 约束；OOM 通过不等价，后续本地证据见顶部 |
| 端到端 | 有各阶段观察，没有新的 matched 累计候选 E2E 恢复证据 |

按用户本次明确的目标，优先级为正确性 > 可用性与稳定性 > 慢查询和复杂聚合查询的 P95 > build/save。P50 保留为辅助观察，不与 P95 并列为优化目标。后续同时满足 CPU 与 RSS 各不超过匹配基线 5% 的约束，不能互相抵消。既有 declared-type native64 加载约 1–2 秒及 CPU 的用户批准例外仍是限定的功能开销，不作为待消除 blocker；本次未重新测量该例外，也不将其扩展到查询或 RSS。

## 可复现证据与全部查询

初始报告阶段新增文件只包含文档、保留证据和离线复算脚本。随后新增的 Attempt315/316 已分别记入实验历史；它们的正确性与本地配对测量状态见本文顶部。原始 CI 和历史单图数据均保留，不改写为新候选的证据。

复算归档请求及全部分位数：

```bash
python3 docs/query-performance-evidence/2026-11-11/verify.py
```

复算完整响应需先下载上述 raw artifact，再传入解压后的根目录：

```bash
python3 docs/query-performance-evidence/2026-11-11/verify.py /path/to/extracted-evidence
```

脚本会核对 8,798 个请求、全部 pressure/isolated 统计；带原始 artifact 时进一步核对完整 body、receipt SHA256、结果一致及 64 图范围。SHA256 保存在 `audit.json`。原始请求完整保留于同目录两个 `.json.gz`，没有丢弃慢样本或失败。历史源代码定位以目标 SHA 为准；新增报告会改变工作区 tree，不应与被测产品 tree 混淆。

下表为历史 CI 被测 head `2275a506`（与初始目标 `58215b98` 整树一致）的全部 73 类观察，全部为 ms。每类 pressure N=20；有 isolated 数据者 N=40。破折号表示按协议未采样，不是零。按 pressure p95 排序，不代表生产流量权重。

| 用例 | pressure p50 | pressure p95 | isolated p50 | isolated p95 |
| --- | ---: | ---: | ---: | ---: |
| schema-relationship-histogram | 143.606 | 310.032 | — | — |
| shape-group-count | 182.226 | 250.179 | — | — |
| shape-with-aggregate | 191.033 | 208.443 | — | — |
| shape-count-traversal | 153.119 | 167.937 | — | — |
| shape-equality | 108.999 | 122.533 | — | — |
| targeted/global-wide-four-properties | 60.485 | 103.815 | 0.627 | 0.653 |
| shape-traverse-filtered | 69.052 | 89.612 | 0.932 | 0.989 |
| targeted/global-wide-class-pair | 5.954 | 61.413 | 0.632 | 0.669 |
| shape-distinct | 18.114 | 38.350 | 6.943 | 8.683 |
| dense/global-wide-wrapped-case-insensitive-distinct | 10.225 | 19.335 | 4.791 | 5.230 |
| shape-label-scan-field | 3.495 | 18.995 | 0.642 | 0.813 |
| dense/global-wide-four-properties | 5.132 | 13.583 | 1.386 | 1.449 |
| shape-all-nodes | 5.162 | 12.195 | 1.298 | 1.329 |
| schema-key-histogram | 5.604 | 11.321 | 1.398 | 1.447 |
| shape-function-calls | 5.787 | 11.268 | 1.503 | 1.522 |
| shape-order-by | 3.451 | 11.261 | 0.693 | 0.714 |
| shape-label-scan-method | 4.424 | 11.163 | 0.686 | 0.807 |
| shape-labels-fn | 4.644 | 9.807 | 1.064 | 1.088 |
| dense/global-wide-wrapped-case-insensitive | 4.474 | 9.480 | 1.565 | 1.634 |
| shape-label-scan-callsite | 4.871 | 9.478 | 0.735 | 0.770 |
| shape-wide-contains | 4.495 | 9.119 | 0.573 | 0.601 |
| dense/global-wide-parameterized-as-literal | 4.927 | 8.893 | 1.382 | 1.723 |
| dense/global-wide-aliased | 4.583 | 8.801 | 1.522 | 1.873 |
| dense/global-wide-provenance | 4.073 | 8.750 | 1.391 | 1.453 |
| shape-inequality | 2.304 | 8.710 | 0.665 | 0.703 |
| shape-coalesce | 4.093 | 8.582 | 1.058 | 1.079 |
| dense/global-wide-class-pair | 4.626 | 8.404 | 1.337 | 1.417 |
| dense/global-wide-caller-class | 3.807 | 8.266 | 1.234 | 1.282 |
| shape-cartesian | 3.034 | 8.234 | 0.445 | 0.475 |
| shape-label-scan-constant | 3.936 | 8.169 | 0.728 | 0.781 |
| shape-skip | 4.836 | 7.783 | 1.390 | 1.480 |
| shape-order-by-desc | 3.313 | 7.200 | 0.597 | 0.648 |
| shape-with-filter | 2.609 | 7.010 | 0.688 | 0.710 |
| dense/global-wide-callee-class | 3.890 | 6.948 | 1.234 | 1.344 |
| shape-case-expr | 1.819 | 6.905 | 0.623 | 0.665 |
| localized-middle/global-wide-four-properties | 2.938 | 6.874 | 0.829 | 0.933 |
| shape-in-list | 2.109 | 6.580 | 0.522 | 0.561 |
| broad-all-64/global-wide-four-properties | 3.978 | 6.352 | 1.366 | 1.437 |
| shape-regex | 2.659 | 6.054 | 0.654 | 1.094 |
| shape-union | 1.626 | 6.015 | 0.618 | 0.641 |
| shape-collect | 1.615 | 5.991 | 0.406 | 0.450 |
| shape-one-hop | 2.940 | 5.921 | 1.355 | 1.828 |
| shape-path | 2.767 | 5.849 | 0.904 | 0.954 |
| shape-null-check | 2.435 | 5.823 | 0.684 | 0.707 |
| zero/global-wide-class-pair | 1.528 | 5.799 | 0.422 | 0.453 |
| zero/global-wide-aliased | 0.812 | 5.790 | 0.401 | 0.431 |
| shape-two-hop | 2.105 | 5.662 | 0.774 | 0.973 |
| dense/global-wide-name-pair | 3.436 | 5.398 | 1.272 | 1.319 |
| shape-var-length | 2.021 | 5.314 | 0.686 | 0.711 |
| zero/global-wide-provenance | 1.167 | 5.293 | 0.405 | 0.433 |
| localized-late/global-wide-four-properties | 3.165 | 5.156 | 0.862 | 0.912 |
| zero/global-wide-wrapped-case-insensitive-distinct | 1.490 | 5.061 | 0.441 | 0.468 |
| shape-one-hop-typed | 2.768 | 4.999 | 1.251 | 1.556 |
| shape-id-lookup | 2.445 | 4.994 | 0.514 | 0.543 |
| targeted/global-wide-provenance | 1.354 | 4.887 | 0.638 | 0.660 |
| shape-incoming | 2.667 | 4.862 | 0.803 | 1.765 |
| zero/global-wide-caller-class | 1.237 | 4.570 | 0.422 | 0.447 |
| localized-early/global-wide-four-properties | 1.791 | 4.550 | 0.646 | 0.661 |
| schema-label-histogram | 2.419 | 4.529 | 0.854 | 0.920 |
| targeted/global-wide-name-pair | 2.108 | 4.520 | 0.620 | 0.656 |
| targeted/global-wide-parameterized-as-literal | 1.374 | 3.969 | 0.628 | 0.649 |
| zero/global-wide-parameterized-as-literal | 0.988 | 3.827 | 0.406 | 0.430 |
| targeted/global-wide-caller-class | 1.613 | 3.708 | 0.619 | 0.645 |
| targeted/global-wide-callee-class | 1.184 | 3.508 | 0.617 | 0.631 |
| zero/global-wide-four-properties | 1.306 | 3.506 | 0.452 | 0.546 |
| targeted/global-wide-aliased | 1.461 | 3.435 | 0.629 | 0.656 |
| shape-count-label | 1.747 | 3.421 | 0.380 | 0.412 |
| shape-count-all | 1.386 | 3.418 | 0.384 | 0.420 |
| zero/global-wide-callee-class | 1.194 | 3.406 | 0.416 | 0.447 |
| targeted/global-wide-wrapped-case-insensitive-distinct | 1.686 | 3.246 | 0.714 | 0.745 |
| zero/global-wide-name-pair | 1.067 | 2.985 | 0.422 | 0.454 |
| targeted/global-wide-wrapped-case-insensitive | 1.840 | 2.383 | 0.642 | 0.668 |
| zero/global-wide-wrapped-case-insensitive | 0.845 | 1.917 | 0.396 | 0.423 |
