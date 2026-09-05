# Attempt 139 显式回退 CI 终态审计

Exact head `aede4c82f66a925ba9df3fc8588c6e1399c17f61`：unit
[33992947613](https://github.com/johnsonlee/graphite/actions/runs/33992947613) **success**，benchmark
[33992947567](https://github.com/johnsonlee/graphite/actions/runs/33992947567) **failure**。
40 个 benchmark jobs 中 33 success、5 failure、2 legacy-disabled skipped。
失败 job 为 `method-compatibility-4-string`、`method-compatibility-gate`、
`graph-routing-pressure-evidence`、`global-wide-pressure-evidence`、`benchmark-regression-gate`。
其余 11 个 Method compatibility shards、method-level JMH、large-corpus 均 success。

[terminal receipt](terminal-receipt.json)、[unit snapshot](terminal-unit.json)、
[benchmark snapshot](terminal-benchmark.json) 绑定 exact head。
监测按约 55 秒一次执行，仅变化输出，20 次快照后正常退出；没有重跑 CI、push、修改源码、Java、构建或本地新测量。
原候选 139 因 old34 第 2 组 P95 +133.86% 违反每组进步要求已永久拒绝；本审计只覆盖显式回退，不能重开原候选或声称全目标达到。

## Method4 prefix CPU 双次失败

从已完成 shard artifact 的四份原始 JMH JSON 独立核对：

| 观测 | base → revert whole-process CPU | 变化 |
|---|---:|---:|
| 初测 | 1.11 → 1.40 s | +26.1261% |
| 反向确认 | 1.29 → 1.55 s | +20.1550% |

两次均超过既有 15% 界限，是该 shard 唯一 blocked 指标。
Wall、CPU、RSS-after、RSS-delta 的所有初测和最终 status 行均逐项匹配原 JMH 值；
四份 correctness 文件每份 3 个不同签名，初测/确认、base/revert 全部一致。
RSS-delta 中的 advisory 项保留原值，不改写成 blocked。
这是 whole-process CPU，包含客户端、reference oracle、引擎和后台工作，不能直接归因到查询某一阶段。

原值见 [method-confirmed-failures.json](method-confirmed-failures.json)、
[CPU status](method4-string/method-compatibility-4-string-cpu-status.json)、
[初测 base](method4-string/base-method-compatibility-4-string.json) /
[revert](method4-string/candidate-method-compatibility-4-string.json)、
[确认 base](method4-string/method-confirmation-base-4-string.json) /
[revert](method4-string/method-confirmation-candidate-4-string.json)。

## Global-wide：实际逐查询回归

六份原始 TSV 与 JMH 独立重算一致。下列 P95 是每轮 34 条不同查询的第 33 个升序值，
均对应 wrapped case-insensitive DISTINCT dense，不是单条查询重复样本的 P95。

| Pair / 顺序 | base → revert P95 ms | base → revert CPU s |
|---|---:|---:|
| 1 / candidate-base | 149.664773 → 113.354634 | 4.05 → 3.37 |
| 2 / base-candidate | 251.113633 → 143.173830 | 3.84 → 3.93 |
| 3 / candidate-base | 125.709738 → 111.394132 | 3.82 → 3.40 |

`global-wide-callee-class-zero` 重复超过相对 >15% 且绝对 >1 ms：
第 2 组 **4.235666→6.008926 ms**；第 3 组 **3.465365→5.646283 ms**。
使用整数条件 `candidateNanos * 100 > baseNanos * 115 && delta > 1000000` 独立复算。
这是唯一重复越界查询，不能因为三组 aggregate P95 较低就忽略它。
`regressionPassed`、`progressAchieved`、`targetAchieved` 全部 false，
不是仅未达到最终 10x。CPU/heap/RSS 原始值匹配 status，本轮 global 资源界限未失败。

204 个完整 correctness 签名逐行匹配冻结 oracle 的 14 个字段；三组各 query 的
hitGraphIds、source/access、work、parallelScan、indexLookup、executionPath 均无差异。
这是签名与原始结果摘要核验，没有重新执行查询或重新解码完整行对象。
[Global 报告](global-wide/global-wide-report.md) / [状态](global-wide/global-wide-status.json)。

## Routing：startup-prepared graph-id P95 失败

Cold 与 warm 通过；startup-prepared 的 **576 条 graph-id 查询 P95（nearest rank 548）**
为 **2.664805→3.162504 ms**，增加 **18.6768% / 0.497699 ms**。
实际界限是 `max(base * 1.15, base + 250000 ns)`，即 3.06452575 ms，revert 超界。
精确 head 的 comparator 内容 SHA 与 artifact provenance 相符；失败来自既有回归界限，
不能把 status 中的 legacy `minimumSpeedup=10` 元数据当成此次失败原因。

从原始六份 TSV 重算三种状态的 graph-id / request-selected P50/P95，
及全部 2/8/64 图宽度 P50/P95、宽度缩放界限，与发布 status 完全一致。
Startup-prepared 的 request-selected 和全部图宽度界限通过；
8 图 P95 **1.077150→1.504308 ms（+39.6563% / +0.427158 ms）** 的退化保留。
Comparator 对每个宽度分别执行 P50 `max(base * 1.15, base + 250000 ns)`、
P95 `max(base * 1.15, base + 1000000 ns)`，并检查相邻宽度 normalized P95 ≤1.5x。
8 图行未超过其 1 ms 绝对界限，不触发该 gate；通过界限不代表该行变快。
实际代码边界见 [comparator 摘录](comparator-gate-excerpt.txt)。
三种状态共 6,822 个完整 correctness 签名匹配 1,137 条独立 oracle。
原始逐行 latency 与 status 的 576 + 192 行对齐值均核验一致。
[Routing 完整状态](routing/graph-routing-status.json)、
[startup-prepared 原状态](routing/graph-routing-startup-prepared-status.json)、
[报告](routing/graph-routing-report.md)。

## 输入与审计边界

Global 与 routing evidence manifest 的 **51 个文件 SHA** 全匹配；revision、图清单、
fixture provenance 与重复生成语义 hash 均核验一致。两边记录的 JAR 内容 hash 同为
`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`。
核验的是托管 artifact 的记录与哈希链，没有重新获取 hosted JAR 或图文件。
记录的二进制相同不豁免 Method、global、routing 失败，也不能单凭它解释为噪声或指定运行时原因。

[ci-audit.json](ci-audit.json) 包含独立重算、所有原值和边界；
[recompute-audit.py](recompute-audit.py)、[recompute-method-audit.py](recompute-method-audit.py)
只读取已有文件，不执行 Java 或性能任务。
下载绑定凭据为 [download-receipt.json](download-receipt.json) /
[download-method-receipt.json](download-method-receipt.json)，
[comparator-source-receipt.json](comparator-source-receipt.json) 绑定实际界限源码。
审计一致表示证据互相吻合，不表示 CI 通过。
