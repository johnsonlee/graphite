# 冻结 main 编译日志：独立核验

核验通过，未发现主报告的实质错误。独立脚本直接解析三份原始 XML、冻结 JAR 的 classfile、TSV 和输入凭据，没有执行父分析脚本，也没有启动 Java、构建或测量。结果见 `independent-audit.json`；可用 `python3 independent-audit.py` 复核，兼容原 XML 压缩归档。

| fork | 首次 C1 发布 | C2 发布（ID） | 真正运行时 trap | 后续 C1 发布 | 后续 C2 排队（未见发布） |
|---|---:|---|---:|---|---|
| 1 | 7.633 | 7.647（3101） | 7.648 | 7.650 OSR、7.656 | 7.671（3191） |
| 2 | 7.575 | 7.589（3071） | 7.591 | 7.593 OSR、7.599 | 7.616（3182） |
| 3 | 7.643 | 7.666（3112） | 7.666 | 7.668 | 7.696（3204） |

表内时间是 JVM 日志秒数，非查询时间窗口。三次均只在 `tty` 直接子事件中找到一个对应 C2 nmethod 的 `unstable_if / reinterpret`，后接同一编译 ID 的 `make_not_entrant`。两层 `jvms` 栈均为 `IntOpenHashSet.contains`（70 bytes）BCI 37 → 精确 raw DISTINCT 节点 callback（790 bytes）BCI 322。三个已完成的目标 C2 编译任务内部另有 109、108、107 个编译阶段 `uncommon_trap` 节点，未计作运行时事件。

三个后来排队的目标任务分别出现在 C2 编译线程的未完成 fragment 中；日志截止时没有其 nmethod 或 `task_done`。每份 XML 全局有两个 fragment，其中一个属于目标 callback。已完成的目标任务均 `success=1`，未见对应编译失败；未发布和未完成不等于失败。

从冻结 JAR 独立解析常量池及 `Code` 字节，确认 callback BCI 322 是对 `IntOpenHashSet.contains(I)Z` 的 `invokevirtual`；contains BCI 37 是 `if_icmpne 42`，比较查询 key 和初始非空槽值。不等分支开始后续线性探测，相等分支返回 true。此处定位不能恢复当次 key、槽值或完整分支历史，也不能计算该操作的独占成本。

三个 fork 的 102 条原始结果逐一通过完整 14 字段 oracle，顺序一致，全部非 latency TSV 字段与 prior base 完全相等。原始命令与模板比较仅改变输出路径并增加三项编译日志选项；JMH JSON 和实际 VM 参数确认 Java 17、零预热、单次测量、GC profiler、4 CPU、8 GiB heap及同一冻结 JAR。没有 tracing/async-profiler或新增强制编译选项。实际 VM 还带有 JMH 自动 compiler-blackhole 配置及临时 `CompileCommandFile`，已完整记录；本审计未恢复临时文件内容。

当前冻结 JAR SHA-256 独立重算为 `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`，与三次运行的前后收据均一致；两份目标 class 的 SHA 与主报告及 javap 收据相符。64 图、1,088 个文件的完整前后清单在路径、大小、SHA 和 manifest 图顺序上完全一致。本次核对了清单和生成清单的逐文件 hash 实现，没有重新读取整个大型图语料做第二次内容 hash。

本证据只确认这些带 LogCompilation 的回放中发生过 C2 发布和该运行时转折。没有查询时间窗口，不归属 targeted/dense 查询，不计算去优化耗时，不解释任何既有候选回归；也不能当作 tracing 开关的因果对照或新优化收益证据。
