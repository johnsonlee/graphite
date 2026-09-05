# Attempt 136 回退 CI 独立审计

审计对象是回退 head `d14bc77f625c515c2e5416728e1b07e0554aa67a` 的已下载 global-wide 与 routing artifacts。父任务已确认两次下载终态成功；本次只读核对源码门槛、JSON/TSV 与哈希，不运行 Java、不重试 CI、不修改 gate。性能 workflow 为 33986202016；unit 33986202091 成功、Method17 aggregate/order 失败由父任务另行核验，本报告不把这些外部状态当成自己重新查询的结果。

**结论：两套数据的正确性与证据完整性通过，性能失败仍成立。** global-wide 同时有真实记录的 repeated aligned-latency regression 和缺少每组 P95 进展；routing 仅 cold k64 P50 触发回归。它们不是只有 10x 门槛未达。

## 完整性、来源与正确性

- global-wide reference evidence manifest 的 19 个文件、routing evidence manifest 的 32 个文件全部存在且 SHA-256 一致。两套 provenance 均绑定上述 candidate head 和 frozen main `4e328b0109e13c896b74004823fb049fcb19251a`。
- 两套 provenance 记录同一个 base/candidate JAR content SHA-256：`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`；manifest 和 fixture-provenance hash 也一致。fixture-reproducibility.json 的两次 provenance/semantic-manifest hash 相同、passed=true。这是 CI 记录的 content equivalence，本地 artifacts 没有原 JAR 可供重新 hash；不能据此把实测失败称作噪声或确定其原因。
- global-wide 六个 34 行 TSV 共 204 条，完整 14 字段 signature 按顺序与独立 base oracle 一致；另核验 34 条 oracle seed TSV。routing 六个 1,137 行 TSV 共 6,822 条，完整签名按 ID 与 base-single-source-oracle.manifest 一致，并与各自 .correctness 文件的顺序相同。所有结果 success，JMH failureCount/timeoutCount 均为 0。
- 每组 base/candidate 的 hitGraphIds、inputSourceCount、accessedGraphCount/accessedGraphIds、graphWorkUnits、parallelScanCount 逐行相同。signature 包含 digest、rowCount、responseBytes、workload identity 等；这是对已有完整 canonical 结果摘要的独立核验，不是重跑 raw rows oracle。

## global-wide

证据目录：`global-wide/reference-4e328b0109e13c896b74004823fb049fcb19251a/`。用升序第 ceil(34×0.95)=33 项重算 P95，与 JMH metric 和 comparator status 相同；六次 P95 都由 wrapped DISTINCT dense 驱动。

| pair / 执行顺序 | base → candidate P95 ms | speedup | 每组严格改善 | CPU s base → candidate | heap bytes base → candidate | RSS bytes base → candidate |
| --- | --- | --- | --- | --- | --- | --- |
| 1 / candidate-base | 194.765887 → 100.719754 | 1.933741x | 是 | 2.97 → 2.78 | 4,259,459,784 → 4,270,578,528 | 5,014,839,296 → 5,057,716,224 |
| 2 / base-candidate | 103.967619 → 97.493250 | 1.066408x | 是 | 2.83 → 2.64 | 4,368,712,248 → 4,272,701,704 | 5,168,558,080 → 5,070,184,448 |
| 3 / candidate-base | 126.274819 → 238.913798 | 0.528537x | 否 | 3.13 → 3.12 | 4,396,385,896 → 4,270,751,192 | 5,209,083,904 → 5,082,980,352 |

CPU、peak used heap、peak RSS 三组均未越过 paired +15% 门槛。原始 regression failure 是 `global-wide-wrapped-case-insensitive-distinct/zero`，两组同时满足相对 >15% 与绝对 >1 ms：

| pair | base ms | candidate ms | 增量 ms | 相对增量 |
| --- | --- | --- | --- | --- |
| 1 | 6.310643 | 8.426481 | 2.115838 | 33.52809% |
| 2 | 7.739032 | 8.901044 | 1.162012 | 15.01495% |

第二组是 +15.01495%，确实严格超过门槛，不能按四舍五入的 15.0% 判通过。第三组 zero 12.415617→7.743975 ms 改善，不抵消前两组；第三组 dense 126.274819→238.913798 ms 回退虽只出现一组，不构成 repeated aligned 失败，却独立违反“每组 P95 改善”。

三份 targeted 为 41.766065→42.520846、42.118052→43.849365、51.110475→54.359829 ms；12 行、work 106,706、命中 `fixture-kotlin-compiler-11,fixture-kotlin-compiler-15` 均相同。dense 为 200 行、work 283,544、命中 `fixture-android-00,fixture-tika-00`；zero 为 0 行、work 99。

顶层 global-wide-status.json 的 regressionPassed=false；progressAchieved=false 的直接原因是 wrapper 要求有效且 regression 通过的 paired evidence，因而短路。即使另算纯 P95，第三组也不满足严格进展。targetAchieved=false；requireTarget=false 只表示此阶段不以最终 10x 为硬验收，不能放过 regression/progress。既有三组 P95 speedup 也都远未达到每组 10x。

## routing

`routing/graph-routing-status.json` 只有 cold k64 一项错误；warm 和 startup-prepared 都通过。直接从 graph-id-in-literal / graph-id-in-parameter 两种 shape 的对应 TSV 行重算了 k2/k8/k64 分位数：每种状态样本数分别 192/48/6，与 status 完全相同。

| 状态 | k64 base → candidate P50 ms | k64 base → candidate P95 ms | gate |
| --- | --- | --- | --- |
| cold | 0.278150 → 1.524298 | 2.319441 → 3.077890 | 失败 |
| warm | 0.168389 → 0.142590 | 0.238622 → 0.201647 | 通过 |
| startup-prepared | 0.289327 → 0.291720 | 2.916262 → 3.084920 | 通过 |

按 `.github/scripts/benchmark-gate.mjs:1445–1459`，cold k64 P50 上限 max(base×1.15, base+0.25 ms)=0.528150 ms，candidate 1.524298 ms 超限。P95 上限 max(base×1.15, base+1 ms)=3.319441 ms，candidate 3.077890 ms 没有超限。因此错误文本虽同时打印 P50/P95，真正触发的是 P50。cold-first 专项 478.081925→390.930440 ms，未触发其 728.081925 ms 上限。

这里保留现行门槛的精确计算，不采纳源码注释对噪声的概括作为本次失败的因果解释。没有配套调度/CPU profile 能说明这次回退 head 为何产生这些差异；内容 hash 相同不能推翻实测门槛结果。

完整状态字段、每对资源/关键行数值、routing k64 原始六行、51 文件 hash 核验及所有输入 hash 见 `independent-ci-audit.json`。审计通过表示证据自洽，不表示 CI 性能通过。
