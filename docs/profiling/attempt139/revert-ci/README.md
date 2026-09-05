# Attempt 139 回退 CI 终态

回退 head `aede4c82f66a925ba9df3fc8588c6e1399c17f61`：unit
[33992947613](https://github.com/johnsonlee/graphite/actions/runs/33992947613) 成功，benchmark
[33992947567](https://github.com/johnsonlee/graphite/actions/runs/33992947567) 失败。
[终态 receipt](terminal-receipt.json)、[unit](terminal-unit.json)、[benchmark](terminal-benchmark.json)、
[完整审计](ci-audit.md) / [JSON](ci-audit.json) 保存 exact head 与原始证据。

三个实际失败不能被 aggregate 改善或相同源码豁免：

- **Method4 prefix CPU**：初测 **1.11→1.40 s（+26.1261%）**，反向确认
  **1.29→1.55 s（+20.1550%）**，两次均超过 15%。
  [原值与独立审计](method-confirmed-failures.json)、[原 status](method4-string/method-compatibility-4-string-cpu-status.json)。
- **Global `global-wide-callee-class-zero`**：pair2 **4.235666→6.008926 ms**，
  pair3 **3.465365→5.646283 ms**，两次同时超过 >15% 与 >1 ms。
  三组全局 P95 虽为 149.664773→113.354634、251.113633→143.173830、
  125.709738→111.394132 ms，`regressionPassed`、`progressAchieved`、`targetAchieved` 仍全为 false。
  [Global report](global-wide/global-wide-report.md) / [status](global-wide/global-wide-status.json)。
- **Routing startup-prepared graph-id P95**：576 条不同查询的第 548 个升序值
  **2.664805→3.162504 ms（+18.6768% / +0.497699 ms）**，超过
  `max(base * 1.15, base + 0.25 ms)`。
  [原 status](routing/graph-routing-startup-prepared-status.json)、[完整 routing report](routing/graph-routing-report.md)。

Routing cold/warm、startup-prepared request-selected P50/P95 通过。
Startup-prepared **8 图 P95 1.077150→1.504308 ms（+39.6563% / +0.427158 ms）** 的退化仍保留。
实际 comparator 对每个 2/8/64 宽度分别检查 P50 `max(base * 1.15, base + 0.25 ms)`、
P95 `max(base * 1.15, base + 1 ms)`，另检查相邻宽度 normalized P95 ≤1.5x；
这些宽度界限通过，8 图行因未超 1 ms 绝对界限未触发失败。这不表示该行变快。
[精确 head 代码边界](comparator-gate-excerpt.txt) / [hash 凭据](comparator-source-receipt.json)
与托管 provenance 匹配。`minimumSpeedup=10` 是 legacy report 字段，不是此次 routing 的实际失败原因。

四份 Method correctness 文件各 3 个签名一致；Global 204 个签名、Routing 6,822 个签名
分别匹配冻结 oracle，三组 global work/source/access 无差异，51 个原始文件 hash 核验通过。
记录的 base/revert JAR 内容 hash 同为
`ddfed3136e1c443d1cbd1cc96285de133fb84ba2a6a252b43326f82020250275`，
它不豁免上述失败，也不足以指定失败原因。未重新获取 hosted JAR 或图文件。

[复算主脚本](recompute-audit.py) / [Method 脚本](recompute-method-audit.py)、
[下载凭据](download-receipt.json) / [Method 下载凭据](download-method-receipt.json)、
[文件哈希清单](audit-receipt.json) 均已保留。
这些脚本依赖本目录原始 TSV/JMH 等完整输入；复制精简归档后应在完整临时目录中复算。
监测已正常退出，没有重试 CI、修改源码、Java、构建或本地新测量。
候选 139 因 old34 pair2 P95 +133.86% 违反每组进步要求已永久拒绝；回退 CI 单独记录，全目标未达到。
