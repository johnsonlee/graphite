# Attempt 137 回退后的 CI 终态

回退 head `e6c932c5e1d0fb7b583ceb9e14c8ef88ec9d9694`：
unit [33988848638](https://github.com/johnsonlee/graphite/actions/runs/33988848638) 成功，
benchmark [33988848640](https://github.com/johnsonlee/graphite/actions/runs/33988848640) 失败。
完整终态、正确性签名和证据 hash 审计见 [ci-audit.md](ci-audit.md) / [JSON](ci-audit.json)。

Method 17 OR CPU 初次 1.95→3.17 s，反向确认 2.18→3.41 s；
Method 36 contains 初次 5.00→5.88 s，确认 5.11→6.02 s，均连续超过 15% 门槛。
[两项失败的原值](method-confirmed-failures.json) 与各目录初测／确认 JMH JSON 均保留。

Global-wide 三组 P95 为 125.214119→144.742573、121.341803→148.690291、
112.354387→150.906486 ms；有实际 regression 失败，不能只解释为未达到 progress。
[报告](global-wide/global-wide-report.md)、[状态](global-wide/global-wide-status.json)。
Routing 三种状态通过，[完整状态](routing/graph-routing-status.json) 已保留。
记录的 base/candidate JAR 内容相同也不豁免失败或证明失败原因。

原候选 benchmark 最终是 cancelled；取消前已经完成的两个 Method 4 CPU 双次失败
仍足以拒绝。其 large-corpus、routing 和 global-wide 被取消，不能写成通过。
见 [原候选 terminal receipt](../ci/terminal-receipt.json)。

本目录是已有证据归档，没有重试 CI 或进行新测量。审计原始文件的哈希与复制后哈希
见 [copy-receipt.json](copy-receipt.json)；归档只可能归一化末尾换行，数值不变。
原始大 TSV、完整 routing 分状态资料及其余 artifact 仍在
`/private/tmp/graphite-attempt137.dcywsuq7/revert-ci/`，复算脚本依赖该完整原始目录。
