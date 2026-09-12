# Attempt 140 回退 CI

Head `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`：unit **success**，benchmark **failure**。

本次 global regression-only **PASS**，实际失败的是 **P95每组严格进步**：

| Pair | main → revert P95 ms | 严格进步 |
|---|---:|---|
| 1 | 119.915932→297.628775 | 否 |
| 2 | 127.839829→118.118335 | 是 |
| 3 | 110.626223→117.182906 | 否 |

没有同一查询重复越过>15%且>1ms，也没有CPU/heap/RSS越界；仍不能豁免第1/3组进步失败。
`requireTarget=false`，所以实际阻断不是仅未达最终10x。
12个Method shards、Method aggregate、method-level JMH、large-corpus和routing三状态均成功。
原候选140的v3拒绝保持不变，此处是回退head，不能写成候选通过。

[完整审计](ci-audit.md) / [JSON](ci-audit.json)、[全34查询配对原值](global-full-table.md)、
[终态receipt](terminal-receipt.json)、[状态receipt](status-receipt.json)、
[Global原状态](global-wide/global-wide-status.json)、[Routing原状态](routing/graph-routing-status.json)。

204个global与6,822个routing完整签名核验一致，51个原文件hash通过。
记录的两边JAR内容hash相同不豁免失败，不证明失败原因。
没有重跑CI或新增Java/build/性能任务。
[下载凭据](download-receipt.json)、[审计文件hash](audit-receipt.json)、[复算脚本](recompute-audit.py)。
复算脚本依赖本临时目录完整原TSV/JMH文件；精简归档后应在完整目录执行。
