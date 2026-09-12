# 查询时间窗口：去优化发生得有多晚

**不能用两次查询尾部的去优化解释前面三十多毫秒。** 三份新录制将编译事件与原 34 条查询精确对应：第 2、3 份的 raw DISTINCT 节点 callback 在 targeted 查询距离结束仅 0.598 / 0.383 ms 时发生去优化；第 1 份的对应事件在 dense 查询期间，且触发位置不同。

本轮没有优化候选。冻结 main 是 `4e328b0109e13c896b74004823fb049fcb19251a`；只对独立诊断 JAR 的一个 benchmark 调用做离线替换，新增两个 JFR marker 类。全部 30,488 个原 JAR 条目按序核对（包含重复名称），只有 benchmark class 改动，全部生产 class 不变。其 40-byte 方法只有 BCI 36 的一个调用改变；不是 runtime agent、生产方法 tracing 或生产类重定义。

一个仅 marker 控制 JVM，加三个 marker/native-JFR/LogCompilation JVM，共 136 条查询；原 14 字段 oracle、完整 marker 文本/参数/顺序/线程/成功状态均通过，全部非耗时 TSV 字段与原基线相同。每条 marker 是实际 execute 的 begin/end，不含提交排队、序列化、指标收集及 marker 准备/提交。首条查询有约 10 ms 的窗口外差值，不能抹掉或当成引擎窗口耗时。

| 录制 | 根 callback C2 的去优化所在查询 | contains BCI | 距该查询结束 | 可由该事件栈证明的阶段 |
|---|---|---:|---:|---|
| profile-1 | dense | 58 | 23.962708–23.991792 ms（3 个线程事件，同一编译） | 1 个来源补全栈；2 个 segment 栈未知 |
| profile-2 | targeted | 37 | 0.597584 ms | segment 栈未知 |
| profile-3 | targeted | 37 | 0.383458 ms | segment 栈未知 |

JFR 的 trap method 与被影响的根编译方法是两回事。上述事件先按同 fork 的 compileId 匹配 XML nmethod，再确认根为 790-byte raw callback；callback 调用点均为 BCI 322。第 3 份 dense 期间另有 contains BCI 58 事件，其根是独立 contains 编译 4246，不是 callback 4248 再次失效。其他带 raw 栈的事件（预算消费、StringUTF16、HashMap 等）完整保留，不能全算作 callback 去优化。

第 1 份后续确实又发布了 callback C2 编译 4308。不能沿用上一批日志“末尾未再见 C2 发布”的结论。全部根 callback 的 JFR/运行时 XML 事件身份与次数独立匹配；编译器内部 parse 阶段的 trap 描述没有混入运行时计数。

| 录制 | targeted marker 时长 | targeted 应用 Java 快照中含节点 callback | 其中 Interpreted / JIT compiled |
|---|---:|---:|---:|
| profile-1 | 42.704375 ms | 29 / 31 | 24 / 5 |
| profile-2 | 37.748709 ms | 27 / 31 | 5 / 22 |
| profile-3 | 37.172500 ms | 22 / 25 | 16 / 6 |

这些数字说明节点扫描仍频繁出现在定向查询的 Java 栈中，但不是 CPU 百分比。JDK 原生 ExecutionSample 是周期性 Java 线程快照；不能把样本数乘 2 ms 当 CPU 时间，也不能沿用 async-profiler 的 CPU 分母。JIT compiled 标签不区分 C1/C2，且不能排除 callback 在其他编译方法中内联执行。

这个 callback 的独立 C2 编译在第 1 份跨过 targeted 结束，到 dense 开始后约 7.771 ms 才完成；第 2、3 份分别在 targeted 开始后约 32.672 / 36.508 ms 完成。对应编译持续约 12.439 / 14.256 / 12.177 ms。这是编译器活动的经过时间，不是请求线程阻塞或独占 CPU，不能直接从查询时长中相减。是否值得减少解释/C1 阶段的节点工作，仍需先核对实际转发、内联和重复操作。

dense 的应用 Java 快照中，节点 callback 分别为 12/29、10/29、7/22；其余工作也保留在全查询叶方法统计。三份录制没有与这两条 DISTINCT 窗口相交的已记录 GCPhasePause，不能推广为没有任何停顿。每份还有 3 个查询开始前的 CompilationFailure（Jvmti state change invalidated dependencies），没有隐去或当作查询失败。

阶段只在栈明确含有冻结 QueryPipeline 的 initial/projectSource 或 provenance 方法时标注；segment 线程缺父栈的事件保持未知。所有 query 归属只用 JFR 的同一时钟。XML stamp 的毫秒精度和日志起点不能直接作为 JFR 纳秒时钟，身份匹配后的残差仍单独保留，没有强行校时。

输入是原有四个真实依赖各 16 个持久化分片，共 64 图；全部 1,088 个文件在控制组和录制组前后 hash 相同。冻结 JAR 未重建或改写；诊断 JAR SHA 为 `2728888d2820fc9721149302612c94b1001bfd38ee5a8c69244fed59fc9b6ef9`。原顺序、冷索引回放协议、Java 17、4 active CPUs、8 GiB heap、零 warmup/单次回放/GC profiler 保留。没有以录制时长报告 P95 或收益，没有重跑接受门槛。

实际 ActiveSetting 经各 JFR 的 type ID 核对：profile 的 ExecutionSample=2 ms、Compilation threshold=0 ms、Deoptimization stackTrace=true；control 关闭这些原生诊断事件。没有 DataLoss 事件。JVM 参数、版本、进程/线程 ID 有记录，但没有 OSInformation/CPUInformation/hostname 事件，不声称由 JFR 独立验证了物理主机。Marker、native JFR、编译日志及初始化都会影响执行状态；这不是 tracing 开关的配对因果实验。

- [主分析 JSON](analysis.json) 与 [复算脚本](analyze.py)
- [独立 marker／oracle／设置审计](independent-marker-audit.md)
- [独立根编译／去优化审计](independent-deopt-audit.md)
- [冻结原查询 catalog](original-catalog.json)、[查询导出凭据](catalog-receipt.json)
- [单调用 overlay 独立核验](root-overlay-audit.json)、[采集计划](capture-plan.json)
- [控制核对](control-verification.json)、[三份录制完成凭据](profile-completion.json)

原始 JFR、JSON、XML、命令、完整图清单、构建与独立审计都保留。压缩的原 JSON/XML 解压回同名文件后可用脚本重算。完整临时输入目录是 `/private/tmp/graphite-query-marker-capture`，overlay 构建目录是 `/private/tmp/graphite-query-marker-diagnostic`。本次证据不构成新优化验收，CallSite 线程池和最终 10x 目标仍未完成。
