# 早期 outer-only 录制的 raw 节点帧状态

三份较早录制也保留了 per-node line/BCI，且同样出现 Interpreted / C1 标签。因此这些**记录标签的出现不依赖新增的 projectSource/provenance phase trace**。这不是 trace-on/off 配对实验，不能据此归因 tracing 开销，也不代表无 profiler 的执行或编译状态。

只读原有 `cpu-3.jfr`、`cpu-4.jfr`、`cpu-5.jfr`，位于 `/private/tmp/graphite-main-profiling-n50joikp`。未重录、重建候选、修改生产或 JAR。小型离线导出 Java 已全部结束。

## 输入绑定

各自 `cpu-{3,4,5}-command.json` 明确指定 frozen JAR、真实 fixture64、旧 34 查询，`indexState=cold`、Java 17、CPU4、零 JMH warmup、单迭代单 fork。JMH 日志 `# VM options` 与对应 command 参数逐字相同。Native profiler 为 `event=cpu,interval=1ms,alloc=256k,lock=1ms`，唯一 MethodTrace 目标是 `CrossGraphCypherExecutor.execute`；额外的 JVM companion JFR 本次不读取。

`query-catalog.json` 记录 frozen main `4e328b0109e13c896b74004823fb049fcb19251a` 和同一 JAR SHA256；当前重新 hash JAR 为 `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`，class 为 `41f1966d893020d389727faf93bb49ad28d4c0754f82485d9c7774d5ae3097ca`，与后期 phase 分析的精确 class 相同。JFR 和 TSV 重算 hash 与旧 analysis 匹配，JFR 在离线读取前后不变。

**绑定限制：** 未找到早期每份录制独立的采集前后 JAR hash 收据。现有证据是命令/实际 VM 参数、catalog 的 frozen JAR 身份以及当前保留的匹配 JAR/class；JFR 本身不嵌入应用 JAR 的加密 hash。没有补猜不存在的逐录制证明。

## 完整窗口及事件核对

新导出原始 leaf-first 全帧 method、line、BCI、frameType、javaFrame，以及 timestamp/thread/truncated；原 JSON 文件完整保留在本目录。另导出全部 MethodTrace，重新核对 exact 方法签名、request 线程、开始/结束、duration。

- 各 34 个 outer trace，共 **102 窗口**；与旧 analysis 的开始/结束/duration 精确一致，非重叠。
- TSV 34 IDs/顺序与 catalog 一致，结果全 success，rowCount/digest/workloadIdentity 与原 analysis 一致；trace 与 TSV gap 按原规则保留，未丢弃首查询较大正 gap。
- 三份录制 CPU 事件总数 **10,721 / 10,679 / 10,690** 与原 analysis 一致。原全窗口 collapsed 样本总数及每线程权重守恒。
- 三份 raw-family CPU 事件 **118 / 225 / 180，共 523**，每事件恰好归属一个 outer window，无重复/漏分配/truncated。
- 重新规范化逐帧方法、反转为 collapsed 后，每个窗口、每个线程的完整 raw 栈直方图与原 collapsed 文件逐项一致。非 raw CPU 没有重新导出帧元数据，不声称它们也完成了逐事件重新解码归属。

## Whole-query 节点 leaf 分布

这里均是整个 outer query 的**节点 lambda 真 leaf**，不是 raw inclusive 的所有样本；dense 包含初始选择和 provenance。所有 269 个早期节点 leaf 都有正行号及非负 BCI；完整分布保留在 summary.json。

| 早期录制 | 查询 | 所有线程 CPU | 应用 CPU | 应用 raw inclusive | 节点 leaf | Interpreted | C1 | C2 标签 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| cpu-3 | targeted | 136 | 73 | 64 | 34 | 25 | 9 | 0 |
| cpu-4 | targeted | 295 | 161 | 156 | 66 | 50 | 16 | 0 |
| cpu-5 | targeted | 176 | 93 | 90 | 51 | 48 | 3 | 0 |
| cpu-3 | dense | 196 | 105 | 54 | 16 | 0 | 16 | 0 |
| cpu-4 | dense | 252 | 136 | 69 | 45 | 45 | 0 | 0 |
| cpu-5 | dense | 334 | 178 | 90 | 57 | 57 | 0 | 0 |

| 同范围聚合 | 早期 outer-only | 后期 outer+phase |
|---|---|---|
| targeted whole-query 节点 leaf | 151：123 Interpreted / 28 C1 | 98：63 / 35 |
| dense whole-query 节点 leaf | 118：102 Interpreted / 16 C1 | **50：8 / 42** |

后期 dense 的正确 whole-query 分母是 initial 7 + provenance 43 = **50**；不能与 provenance-only 的 43（3 Interpreted / 40 C1）直接比较。两批的样本量、时间、JIT 历史不同，无重复顺序交叉控制；标签差异不能算 phase trace 的因果影响。C2-labelled leaf 为零不证明没有 C2 编译或执行。macOS recording 的 `event=cpu`、`engine=wall` 也不是 Linux hardware cycles。

本报告不比较 profiler 延迟来重判旧候选，不估算删池、改数组或编译选项的收益。只确认更早 outer-only 录制也存在相同类型的帧元数据，且当前采样不能替代 unprofiled gate。

复算：`python3 analyze.py` 只读已导出事件和既有分析；`export-receipt.json` 保存已完成的离线 Java 命令与源码/输出 hash，`RawFrameSensitivity.java` 是最小导出器。全部逐窗口计数、输入 hash、分布与限制在 `summary.json`。
