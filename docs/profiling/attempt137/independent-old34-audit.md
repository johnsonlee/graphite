# Attempt 137：old34 独立审计

**六份结果通过预设 old34 本地条件，但尚未接受候选，最终 10x 未达。** 独立重算确认 regressionPassed=true、strictProgressEveryPair=true。第二组 P95 仅减少 0.882167 ms（1.790171%），不能声称稳定收益；该组 targeted 还有一次明显退化，现行 repeated gate 仍可通过。

只读取既有 JSON、TSV、命令、源码和 javap 文本；没有启动 Java、测试或性能任务，也没有在父任务 v3 计时期间重新 hash 大 JAR/图文件。机器可读完整证据与小文件 SHA-256 见 `independent-old34-audit.json`。

## 正确性与协议

- 六份各 34 行，共 204 个完整 14 字段 signature 按顺序等于 oracle；oracle SHA-256 为 `a331a139c575120eb47bec21e2cbafb766f1f68dee2f892939edfa608e105219`。全部 success，JMH failureCount/timeoutCount=0。核验包含 digest、rowCount、responseBytes 和 workload identity；没有重新执行 raw-row oracle。
- 所有逐行 graphWorkUnits、hitGraphIds、accessedGraphIds/count、inputSourceCount、parallelScanCount、indexLookupCount、executionPath 均未变。每份合计 work 为 58,071,626；这证明记录的访问计费相同，不是证明 CPU 成本相同。
- 精确 benchmark 为 `LargeBroadQueryPressureBenchmark.replayBroadQueries`；Java17、ActiveProcessorCount=4、8GiB heap、64 图、indexState=cold、0 warmup、1 measurement/1 fork；执行顺序 candidate/base、base/candidate、candidate/base。仅 JMH gc profiler，没有 async CPU profiler。
- 本地脚本记录 base JAR `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`、candidate JAR `b129029382bc6f0e8491c97c9057830c5993902b9cbeae7f21e6df10865a1fb7`，并在前后重新 hash 断言未变；candidate hash 与 build receipt 一致。本次未重复大文件 hash。

## P95、资源与最慢三条

P95 使用第 ceil(34×0.95)=33 项；独立 TSV 计算同时等于 JMH metric 和 comparator status。三组最慢三条顺序始终相同：four-properties-zero（最大值）、wrapped DISTINCT dense（P95）、wrapped DISTINCT targeted（第三慢）。最大值那一条没有进入 empirical P95。

| pair / order | P95 base → candidate ms | speedup | CPU s base → candidate | used heap bytes base → candidate | peak RSS bytes base → candidate |
| --- | --- | --- | --- | --- | --- |
| 1 / candidate-base | 137.409167 → 52.732459 | 2.605780x | 1.687700 → 1.430862 | 4,709,031,616 → 3,834,421,568 | 5,250,940,928 → 4,373,413,888 |
| 2 / base-candidate | 49.278375 → 48.396208 | 1.018228x | 1.544348 → 1.415953 | 4,318,929,000 → 3,847,261,672 | 4,856,233,984 → 4,392,648,704 |
| 3 / candidate-base | 49.659250 → 40.778083 | 1.217793x | 1.702896 → 1.282096 | 4,713,383,688 → 3,847,604,808 | 5,268,914,176 → 4,357,668,864 |

三种资源在三组都下降，未触发 +15% 门槛。P50 speedup 分别 0.937055 / 1.071595 / 1.148155，并非每个分位数每组都改善。没有置信区间或更多独立 fork 支持“稳定”表述。

| pair | four-properties-zero ms base → candidate | DISTINCT dense ms base → candidate | DISTINCT targeted ms base → candidate |
| --- | --- | --- | --- |
| 1 | 266.021500 → 150.219125 | 137.409167 → 52.732459 | 38.769334 → 39.452166 |
| 2 | 243.222208 → 151.925167 | 49.278375 → 48.396208 | 32.371084 → 42.323250 |
| 3 | 261.569709 → 149.124750 | 49.659250 → 40.778083 | 46.251042 → 30.438625 |

这三条 work 分别始终为 57,642,093 / 283,544 / 106,706。dense 200 行、命中 android-00+tika-00；targeted 12 行、命中 kotlin-compiler-11+15；zero 0 行、无命中图。所有其他查询的 work/path 也没有变化。

第二组 targeted 32.371084→42.323250 ms，增加 9.952166 ms、30.74400%，同时超过 15% 和 1ms。但只有这一组，未形成 gate 所需的至少两组 aligned repeated violation。不能把 regressionPassed=true 描述成每条查询每组均无回退，也不能以其单组失败推翻既有 repeated 判定规则。

第二组是唯一 reverse-order pair，P95 改善只有约 1.02x；第一组 base dense 137.409167 ms 明显高于另外两组约49ms，不能只引用最大的 2.61x 作为普遍收益。

## 机制证据与因果边界

现场候选源码 SHA-256 仍为 `8a803b81cfd2b9c455276c33640cafff9963a11673eed3a504b59fa5a63b6672`。机械还原两 imports、callback type/default、accept 调用后仍逐字节等于冻结 main；没有引入 cache、posting、调度或校验删减。

已有 javap 文本可独立确认 PersistentIndexViewValidator 的两个循环调用点：base 各有一次 Integer.valueOf/Long.valueOf、两次 Function1.invoke；candidate 这三类指令计数为0，改成一次 IntConsumer.accept:(I)V 和一次 LongConsumer.accept:(J)V。默认 callback 的 primitive 静态方法仍存在。这里的“次”是字节码调用位置数，不是动态运行次数。

这证明改动到达了目标 callback ABI，不证明所有生成 callback body、JIT 机器码、精确分配节省，更不证明后续 warm 查询的全部延迟改善由该变化直接造成。整个 replay 标记 cold，但后面的查询可以复用已加载 view/index 和已编译代码；此前 old34 dense validator 样本为0，因此不能借冷验证热点强行解释每一组 dense P95 变化。相同 graphWorkUnits 也不会计入对象装箱/GC/编译的成本。

本结果只满足测量计划中进入 v3 三组与必需 CI 验证的前置条件。local-progress.json 明确 accepted=false（CI acceptance not established）；target10x=false，wrapped 非 DISTINCT shape 的 speedup 约0.90/1.00/0.97也远未达10x。本文不提前接受候选，不以三组局部通过替代尚未完成的 v3/CI。
