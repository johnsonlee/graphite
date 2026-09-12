# Attempt 140 显式回退 CI 终态审计

Exact head `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`：unit
[33995836241](https://github.com/johnsonlee/graphite/actions/runs/33995836241) **success**，benchmark
[33995836230](https://github.com/johnsonlee/graphite/actions/runs/33995836230) **failure**。
40 个 benchmark jobs：36 success、2 failure、2 legacy-disabled skipped。
失败 job 仅 `global-wide-pressure-evidence` 与 `benchmark-regression-gate`。

**此次实际阻断是每组 P95 必须严格进步，而不是重复 regression gate 失败。**
Global `regressionPassed=true`，`progressAchieved=false`，`targetAchieved=false`，
`iterationPassed=false`，`requireTarget=false`。第 1、3 组未比 last accepted frozen main 改善，
因此在不要求最终10x的 iteration gate 下仍然失败，不能把它只写成“未达10x”。

## Global 原值与独立门槛重算

| Pair / 顺序 | main → revert P95 ms | CPU s | heap bytes | RSS bytes |
|---|---:|---:|---:|---:|
| 1 / candidate-base | 119.915932→297.628775 | 4.13→4.26 | 4277629680→4449040944 | 5597147136→5280157696 |
| 2 / base-candidate | 127.839829→118.118335 | 3.94→3.68 | 4398811736→4379619720 | 5194678272→5152776192 |
| 3 / candidate-base | 110.626223→117.182906 | 3.68→3.81 | 4304212872→4274491192 | 5087035392→5092986880 |

六份原 TSV 的 nearest-rank 第33位与 JMH/status P95 完全一致；每轮分母是34条不同查询，
不是某条查询重复样本的P95。所有P95均为 wrapped case-insensitive DISTINCT dense。
第1组 **+148.1979% / +177.712843 ms**；第3组也变慢，虽未超过重复 regression 的相对界限，
仍违反单独要求的严格进步。没有将失败界限放宽或重跑。

独立重算完整102对：**45个变慢观测、10个单次双界限越界、0个同查询重复越界**。
只有第1组 aggregate P95 / DISTINCT shape P95 超过相对>15%且绝对>1ms，未重复；
CPU/heap/RSS三组均未超过15%资源界限。故原 regression-only PASS 与 strict-progress FAIL
并不矛盾。全部负向原值见 [全34表](global-full-table.md)，未删去单次越界。

204个完整14字段 correctness 签名逐行匹配冻结 oracle；三组对应查询的
hitGraphIds、inputSourceCount、accessedGraphIds/Count、graphWorkUnits、parallelScanCount、
indexLookupCount、executionPath 均相同。本次是原签名/摘要核验，没有重新执行查询或解码原始完整结果行。
[Global 原报告](global-wide/global-wide-report.md) / [状态](global-wide/global-wide-status.json)。

## Method 与 routing

12个 Method compatibility shards 及 aggregate gate 均 success，method-level JMH、large-corpus
亦 success。这里来自 exact-head [终态 job 记录](method-job-results.json)；没有终态失败 shard，
也未声称重新审计所有Method原始JMH样本。两项 legacy external-evidence skipped 不是通过的性能比较。

Routing cold / warm / startup-prepared **全部通过**。六份 TSV 的6,822个完整签名与1,137条
独立 oracle 一致。独立重算每状态576条graph-id和192条request-selected的P50/P95，
所有对应latency行与发布status逐项一致；2/8/64图宽度P50/P95与缩放界限亦核验。
这些界限通过不代表每行都变快，不将139回退的startup-prepared失败沿用到本次。
[Routing 原报告](routing/graph-routing-report.md) / [完整状态](routing/graph-routing-status.json)。

## 身份、范围与决定

Global+routing evidence manifest共51个文件SHA全匹配；base/candidate revision、图清单、
fixture provenance与重复生成语义hash均一致。精确head comparator内容SHA匹配两份artifact的
provenance，见 [源码绑定receipt](comparator-source-receipt.json)。两边记录的JAR内容hash同为
`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`。
核验的是托管artifact记录和哈希链，未重新取得hosted原JAR或图文件。
**记录的二进制相同不豁免strict-progress失败，也不能单凭它解释为噪声或指定失败原因。**

原候选140已经因v3 `mixed-four-few-rows`两组重复越界被永久拒绝，未进入候选hosted CI。
本次只覆盖其显式生产回退head，不改变原拒绝决定；CallSite池与最终全局P95 10x目标未完成。

约55秒一次的只读监测共16次快照后正常退出；没有workflow dispatch/rerun、源码修改、push、
PR修改、Java/build/profiler或本地性能任务。
[dispatch receipt](dispatch-receipt.json)、[终态receipt](terminal-receipt.json)、
[unit快照](terminal-unit.json)、[benchmark快照](terminal-benchmark.json)、
[下载凭据](download-receipt.json) 保留exact SHA。
[独立复算JSON](ci-audit.json) / [脚本](recompute-audit.py) 保存原值、完整102对和门槛推导。
审计一致说明证据吻合，不代表CI通过。
