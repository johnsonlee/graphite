# Attempt146 control 与 gate 独立审计

完整正确性通过；本轮尚未满足每对严格改善与最终10倍目标。此次审计只读取原始文件，不执行 Java、基准、原比较器或 CI，也不以进程 exit0 代替验收。

## 控制与输入

- v3 明确为 `graphite-wide-query-oracle-v3`，18 logical / 36 queries，原顺序完整。逐项核对 workload 的 base64 原查询、SHA、投影列、完整6,171行 values/order/graphIds、digest、返回来源集合、完整命中图 census 对应和64源/per-query-cold。所有结果一致。
- `run.json` 为 complete，1个已验证fork，每query仅1次观测，empiricalP95均未报告，performanceGate=false。此控制不是36查询性能比较。
- old34 共六份原TSV与JMH JSON，204条完整14字段 oracle 签名全等。102个base/candidate对的全部33非时间字段相同，六份也均与冻结原oracle.tsv相同；保留逐行耗时，不能据work相同推断耗时原因。
- v3与old34前后图清单一致：64图/1,088文件/10,321,426,977 bytes。catalog、workload、工具源码和编译runner class均按收据实际重算SHA。JAR SHA绑定build/control/old34收据；本审计未重新读取10GB图或整JAR。
- 六份old34命令逐参数从原oracle模板核对，只有JAR、输出、record→verify及oracle参数替换；实际JMH JVM参数一致。Java17、P4、global-wide64/cold、0 warmup、1 iteration/fork，顺序C/B、B/C、C/B。

## 原始观测与已执行门槛

| Pair | Base P95 ms | Candidate P95 ms | Δ% | CPU Δ% | heap Δ% | RSS Δ% | 严格改善 | candidate graph/segment peak |
|---|---:|---:|---:|---:|---:|---:|---|---|
| 1 | 57.550125 | 42.817666 | -25.599 | -3.915 | -9.404 | -8.701 | True | 2/2 |
| 2 | 62.780833 | 47.492458 | -24.352 | -1.743 | +6.774 | +6.191 | True | 2/2 |
| 3 | 51.742417 | 52.154333 | +0.796 | +6.676 | +0.148 | +0.030 | False | 2/2 |

P95按34条排序后的第33条（ceil(0.95×34)）重算；六次决定项均是 `global-wide-wrapped-case-insensitive-distinct-dense`。这不是某条查询跨三fork的P95。p50/max和全部原JMH相关聚合work/access/path计数也与TSV一致。三对CPU、heap、RSS均未超过15%上限；分别记录的graph/segment peak均为2，不能把两个峰值相加声称同时live4线程。

单query的“双阈值”使用严格大于15%且严格增加超过1ms，须至少两对重复才构成原比较器中的对应回归。三对中只有pair2以下三条单次越界，无重复；JSON保留全部102条增减。

| Pair2 query | Base ms | Candidate ms | Δ% |
|---|---:|---:|---:|
| global-wide-caller-class-targeted | 2.631792 | 3.638125 | +38.238 |
| global-wide-aliased-targeted | 3.401084 | 4.494250 | +32.142 |
| global-wide-wrapped-case-insensitive-distinct-zero | 3.252958 | 5.003625 | +53.818 |

两层执行边界必须分开：

1. `run-old34-pairs.py` 每对后检查严格P95改善、2/2峰值和三项资源15%条件。前两对满足；第三对P95增加0.411916ms（约0.796%），首次触发停止分支。因为这是第三对，六个录制均已完成，没有未跑的pair。该break正常exit0，但 `strictProgressEveryPair=false`，driver没有调用比较器。
2. 随后root对**已有六份录制**另行执行未修改的原完整比较器，`comparison-exit.json`明确记录这一步，无新测量。命令是 `--regression-only --minimum-speedup 10`；比较器exit0、`regressionPassed=true`、`passed=true`、errors为空。独立复算资源、每query及aggregate/wrapped重复门槛均与之相符。
3. `targetAchieved=false`，三对总P95及两个wrapped shape各未达10倍，共9条targetErrors，数值已独立重算。该比较器的regression-only通过不覆盖额外的每对严格改善要求，也不等于整体接受。`local-progress.json` 保留 accepted=false/ciRun=false；没有本轮CI验收。

本审计第一次读取时比较器尚未执行；最终报告和SHA清单已更新至随后落盘的正式比较结果。不能把先前的“未执行”快照与最终status混用。

目前证据说明这一精确快照未通过额外进展要求，并不证明共享调度方向无效，也不解释第三对的小幅变化。未因诊断结论重跑或筛除异常。

复算入口：[control-and-gate-audit.py](control-and-gate-audit.py)；完整行比较、资源、决定P95项、9项target复算与原始SHA：[control-and-gate-audit.json](control-and-gate-audit.json)。
