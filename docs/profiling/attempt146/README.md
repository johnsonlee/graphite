# Attempt 146：无子任务时省去重复清理

**本地完整正确性和原 regression comparator 通过；每对严格改善要求未通过，10×目标未达到，候选尚未接受。** 下一步仅计划对同一候选做一次独立 CI；本快照尚未提交、推送或执行 CI。不能把计划当成通过，也不追加本地 fork 寻找更好的样本。

候选基于 145 `cf300347247174a74bbbd188e89c36c094ae2bdd`。唯一 production 改动是在 `GraphTask.finishChildren` 的原 monitor 内，先关闭 child 注册，再在 `children.isEmpty()` 时返回原 `primaryFailure`。有 child 的路径、真实退出、cap/lane、scope、取消和通知协议逐字保持。它不改变任务粒度、索引、consumer、预算或匹配算法。[原方案审查](source-review.md)、[实际 diff 与测试审查](actual-source-review.md)。144 的 childDrain 是含等待与探针的墙钟包络，不能当锁成本或收益承诺；没有将有语义缺口的逐 source 方案混入。

## 构建与正确性

[独立构建审计](build-evidence-audit.md)核实315项输入、55份XML，共2107项测试，failure/error/skipped均为0。core455/cypher1236/webgraph195/explore221；四个test任务均执行，detekt仅core执行，另三项FROM-CACHE。两个JMH包及Webgraph排除test任务通过。新增一个JUnit测试内部覆盖正常和cancel(false)，从未有child、注册关闭、原异常身份、未早finished及FIFO canary证明cap未提前释放；不是要求旧版失败的red/green实验。

当前compiled main与JAR逐项绑定683/958类。对145，两包实际均有GraphTask、GraphTaskContext、GraphTaskGroup、GraphTaskScheduler四类payload差异；后三类的差异仅限独立核实的SMAP/调试属性，不能写成原始payload相同。新增test及嵌套类均不在两JAR。

- Webgraph SHA256：`120808180cebddac06522ece2eef37089db99fc08e2864090e91cfa85a66cee9`
- Explore SHA256：`5ed837ba61df069664fd132bad9d94db1bfa69aa1b8fb221e7b48737d075338f`
- 原34冻结base JAR：`a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`

[Control 与 gate 独立审计](control-and-gate-audit.md)：v3的18 logical/36 queries完整通过，逐项核对6171行完整values/顺序/provenance，包括纯四词OR单图和多图。每query仅一个观察，performanceGate=false，不是36条性能比较。原34六份TSV的204条完整14字段签名全等，102个配对的33个非时间字段全部一致；64图1088文件/10,321,426,977 bytes的前后身份一致。

[Method独立审计](method-control-audit.md)：4/17/36 service ×11场景的33组合完成，使用四个真实Android/Tika/Hive/Kotlin corpus循环注册，并非33份独立应用。64文件/1,641,948,871 bytes前后身份一致。requestsSucceeded=1表示整组action通过；源码对应759个测量内HTTP helper调用，另有setup探路，并非独立网络请求普查。manifest保存expected；root ORDER先归一化排序、未知graph ID可能被过滤，不能称完整raw root序和额外ID检查。这是单边正确性control，无Method性能比较；没有用合成CypherBenchmark证明收益。

## 原34三组实际数据

同一真实64图、完整原34顺序、Java17/P4/8GiB、cold、零warmup、单iteration/fork。按C/B、B/C、C/B固定执行，无重录或挑选。P95为34条查询排序后的第33条，六次决定项均为dense DISTINCT，不是单query跨三次的P95。

| Pair | Base P95 ms | Candidate P95 ms | Δ% | CPU Δ% | heap Δ% | RSS Δ% | 严格改善 |
|---|---:|---:|---:|---:|---:|---:|---|
| 1 C/B | 57.550125 | 42.817666 | -25.599 | -3.915 | -9.404 | -8.701 | 是 |
| 2 B/C | 62.780833 | 47.492458 | -24.352 | -1.743 | +6.774 | +6.191 | 是 |
| 3 C/B | 51.742417 | 52.154333 | +0.796 | +6.676 | +0.148 | +0.030 | **否** |

三组graph/segment peak各自均2/2；不能相加称live总线程。CPU/heap/RSS每对均未超过15%。pair2有三条单次同时超过>15%且>1ms：caller-class-targeted 2.631792→3.638125ms、aliased-targeted 3.401084→4.494250ms、wrapped DISTINCT zero 3.252958→5.003625ms；没有query在两对重复越界。全部原值保留在[逐行审计JSON](control-and-gate-audit.json)。

执行边界必须分开：

1. 原driver未改：每对检查strict P95/2+2各峰值/资源15%；第三对增加0.411916ms，触发原停止分支。此时三对六fork均已完成，driver正常exit0但`strictProgressEveryPair=false`（逐对true/true/false）；其自动comparison分支没有执行。原打印“no further acceptance pairs or CI for this snapshot”保留在脚本/日志。
2. root随后仅对已有六份数据执行未修改的完整比较器，命令含`--regression-only --minimum-speedup 10`，exit0、regressionPassed=true、passed=true。这是事后比较，无新采集。[原status](old34-pairs/global-wide-status.json)、[原report](old34-pairs/global-wide-report.md)、[local-progress](old34-pairs/local-progress.json)。
3. targetAchieved=false，共9条targetErrors；regression-only通过不覆盖每对严格改善，更不是整体通过。[独立决策收据](ci-plan.json)明确：后续一次CI是新增的独立决策，不是原driver继续执行，也不抹去本地strict失败。

## 下一步与交付状态

仅计划将这份真实本地证据随单一146提交送到PR116，运行一次exact-head CI。当前candidate未commit/未push，尚无CI run/head或结论；结果必须随后独立记录。没有新local fork、没有v3性能配对、没有以CI计划宣称接受。10×目标仍未达，共享调度方向继续；145的正确性修复与144的失败数据不冒充146收益。

[证据包](evidence.tar.gz)与[逐文件manifest/回读校验](archive-manifest.json)保留commands/logs/XML、原始JMH/TSV、完整oracle、源码快照和工具来源；排除clone、JAR、classes。主要材料按[直接复制清单](direct-copy-list.json)供后续candidate归档，当前只写入本临时目录。[归档范围](archive-inventory.md)列出所有纳入类别。包内保存此时的未提交状态；后续提交或CI不会反写这份本地原证据。

本页及包内材料是提交前冻结的本地证据快照；提交和CI的最新状态以 [PR116](https://github.com/johnsonlee/graphite/pull/116) 为准。这里不把后续CI结果反写进原始测量。
