# Attempt 138 回退 CI 终态审计

Exact head `27de1f5ebd318fb5f60b24596712a5b3a6a3836e`：unit **33991414367 成功**，
benchmark **33991414379 失败**。两个 run 均已终态，失败 job 是
`global-wide-pressure-evidence` 和最终 `benchmark-regression-gate`。
完整终态快照与 SHA 见 `terminal-unit.json`、`terminal-benchmark.json`、
`terminal-receipt.json` 和 `ci-audit.json`。只读监测约每 55 秒一次，已正常结束；
没有重试 CI、修改源码、push、Java、本地构建或新测量。

12 个 Method compatibility shards 及总 gate 均为 success，没有终态失败的
Method shard；本次没有开展新的反向测量。Method-level JMH、large-corpus、routing 及其他已启用检查均成功；
两个 legacy external-evidence jobs 按工作流设计 skipped，并非已完成性能比较。
Method 成功结论来自 exact-head 终态 job 记录，未虚构逐个 Method 原始样本的再审计。

## Global-wide 是实际回归失败

从六份原始 TSV 重算，第 33 个升序值构成每轮 34 条不同查询的 nearest-rank P95，
全部由 wrapped case-insensitive DISTINCT dense 行决定。它不是某条查询重复测量的 P95。

| Pair / 顺序 | base → revert P95 ms | base → revert CPU s |
|---|---:|---:|
| 1 / candidate-base | 130.939067 → 120.137039 | 3.92 → 4.05 |
| 2 / base-candidate | 130.636671 → 142.437091 | **3.28 → 3.87** |
| 3 / candidate-base | 191.349031 → 122.319238 | 4.42 → 4.00 |

第 2 组 whole-process CPU 增加 **17.9878%**，超过既有 15% 门槛。
六个查询重复超过相对 >15% 且绝对 >1 ms 的双界限，独立计算使用整数比较：

| 查询 ID | 触发 pair | 触发观测 base → revert ms |
|---|---|---|
| global-wide-class-pair-zero | 1, 2 | 7.752720→10.567829；7.085889→10.954236 |
| global-wide-aliased-targeted | 1, 2, 3 | 9.655432→12.420924；8.708113→12.869438；12.304968→16.395394 |
| global-wide-parameterized-targeted | 1, 3 | 5.832781→7.839121；6.457241→8.181506 |
| global-wide-distribution-localized-early | 1, 2, 3 | 3.601546→7.593872；2.814158→3.963596；4.015209→6.736075 |
| global-wide-four-properties-dense | 2, 3 | 10.909952→13.866179；12.062314→14.203873 |
| global-wide-class-pair-targeted | 2, 3 | 10.217513→11.780115；11.735852→14.529413 |

不能因为两组 aggregate P95 较低就忽略 CPU 与逐查询失败。
`regressionPassed`、`progressAchieved`、`targetAchieved` 都是 false；此次不是
仅未达到严格 progress 或最终 10x。Heap/RSS 原始 JMH 值与发布结果一致，
没有资源 gate 报告它们退化。原始 status/report、六份 JMH/TSV/正确性签名保留在
`global-wide/reference-4e328b0109e13c896b74004823fb049fcb19251a/`。

## 正确性、routing 和输入证据

Global-wide 六份 TSV 的 **204 个完整 correctness 签名**逐行匹配冻结 oracle
（14 字段，包括查询身份、行数、response bytes、digest），且 JMH 报告均为
34 success / 0 failure。每组对应 query 的 hitGraphIds、source/access 计数和列表、
work units、parallelScanCount、indexLookupCount、executionPath 相同。
这是既有签名证据核验，不是重新执行查询或重新解码完整行对象。

Routing cold / warm / startup-prepared **全部通过**。六份 TSV 的 **6,822** 个
签名匹配 1,137 条独立 oracle；按 2/8/64 图宽度的 P50/P95 从 TSV 独立重算，
与各状态发布值一致。原始状态、TSV、oracle 及报告均留在 `routing/`。

Global 和 routing evidence manifest 共 **51 个文件 SHA**全部匹配；base/candidate
revision、manifest、fixture-provenance 和重复生成语义 hash 一致。两边记录的 JAR
内容 hash 同为 `ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`。
这里核验的是托管 artifact 的记录及哈希链，没有重新取得原始 hosted JAR 或图文件。

记录的二进制相同不豁免上述失败，也不能单凭这一点解释为噪声或归因到某个阶段。
原候选 Attempt 138 已因两个新增 v3 查询重复退化在本地拒绝，未进入候选 hosted CI；
本次审计只覆盖其显式回退 head，不改变原拒绝决定或声称任何优化接受。

复算入口 `recompute-audit.py`，结果 `ci-audit.json`，下载绑定凭据
`download-receipt.json`。审计通过表示这些证据互相一致，不表示 CI 通过。
