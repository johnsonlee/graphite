# Attempt 138 回退后的 CI 终态

回退 head `27de1f5ebd318fb5f60b24596712a5b3a6a3836e`：
unit [33991414367](https://github.com/johnsonlee/graphite/actions/runs/33991414367) 成功，
benchmark [33991414379](https://github.com/johnsonlee/graphite/actions/runs/33991414379) 失败。
[终态 receipt](terminal-receipt.json)、[unit 快照](terminal-unit.json)、
[benchmark 快照](terminal-benchmark.json) 和 [完整审计](ci-audit.md) / [JSON](ci-audit.json) 已保留。

失败的是 `global-wide-pressure-evidence` 和最终 `benchmark-regression-gate`。
12 个 Method compatibility shards、其总 gate、method-level JMH、large-corpus 与 routing 均成功；
Method 结论来自 [终态 job 记录](method-job-results.json)，未声称逐个重审 Method 原始样本。
两个 legacy evidence jobs 按工作流设计 skipped。

Global-wide 存在实际回归；`regressionPassed`、`progressAchieved`、`targetAchieved` 均为 false。
三组原始 P95 为 130.939067→120.137039、130.636671→142.437091、
191.349031→122.319238 ms。这里 P95 是每轮 34 条不同查询的第 33 个升序值，
不是某条查询重复样本的 P95。
第 2 组 whole-process CPU **3.28→3.87 s（+17.9878%）**，超过既有 15% 界限。
原值见 [reference status](global-wide/reference-4e328b0109e13c896b74004823fb049fcb19251a/global-wide-status.json)、
[base JMH](global-wide/reference-4e328b0109e13c896b74004823fb049fcb19251a/base-global-wide-2.json) 和
[revert JMH](global-wide/reference-4e328b0109e13c896b74004823fb049fcb19251a/candidate-global-wide-2.json)。

以下六条查询在至少两组同时超过相对 >15% 与绝对 >1 ms 界限。
逐条纳秒原值和触发 pair 见 [repeated-query-failures.json](repeated-query-failures.json)，
完整比较见 [global 报告](global-wide/global-wide-report.md) / [状态](global-wide/global-wide-status.json)。

| 查询 ID | 触发 pair | base → revert ms |
|---|---|---|
| global-wide-class-pair-zero | 1, 2 | 7.752720→10.567829；7.085889→10.954236 |
| global-wide-aliased-targeted | 1, 2, 3 | 9.655432→12.420924；8.708113→12.869438；12.304968→16.395394 |
| global-wide-parameterized-targeted | 1, 3 | 5.832781→7.839121；6.457241→8.181506 |
| global-wide-distribution-localized-early | 1, 2, 3 | 3.601546→7.593872；2.814158→3.963596；4.015209→6.736075 |
| global-wide-four-properties-dense | 2, 3 | 10.909952→13.866179；12.062314→14.203873 |
| global-wide-class-pair-targeted | 2, 3 | 10.217513→11.780115；11.735852→14.529413 |

Global 204 个完整 correctness 签名与冻结 oracle 一致，三组 work/source/access 计数无差异。
Routing cold / warm / startup-prepared 全通过，6,822 个签名与 1,137 条 oracle 一致，
2/8/64 图宽度 P50/P95 独立复算与发布值相符；见 [routing 报告](routing/graph-routing-report.md) /
[完整状态](routing/graph-routing-status.json)。这是既有签名审计，没有重新执行查询。

Global 与 routing 的 51 个文件哈希均通过原始 evidence manifest 核验。
记录的 base/candidate JAR 内容 hash 同为
`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`；
这是托管 artifact 的记录与哈希链，未重新获取 hosted JAR 或图文件。
**记录的二进制相同不豁免失败，也不能单凭它解释为噪声或归因到某个阶段。**
原候选 138 已因新增 v3 两项重复退化被拒绝，未进入候选 hosted CI；此次只审计显式回退 head。

本目录只归档既有证据，没有重试 CI、构建或新测量。
[下载凭据](download-receipt.json) 绑定原始 artifact 与 run，
[copy receipt](copy-receipt.json) 记录原始及归档哈希；复制最多归一化末尾换行，JSON 数值不变。
本目录未复制 JAR/JFR、原始 TSV 和重复的 routing 分状态报告。
完整原始证据仍在 `/private/tmp/graphite-attempt138.7ihszrob/revert-ci/`；
[复算脚本](recompute-audit.py) 依赖该完整目录，应在那里运行。
审计证据一致不代表 CI 通过。
