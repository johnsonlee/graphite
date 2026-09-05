# 原 34 查询：benchmark marker + 原生 JFR 独立核对方案

本目录仅为冻结 main `4e328b0109e13c896b74004823fb049fcb19251a` 的诊断审计方案与可复查来源。没有运行 Java、构建、采集或性能测试，没有修改工作区。它不属于新优化 attempt，不改变任何验收门槛，不重新运行 v3。**外层查询 marker 足以给原生 JFR 事件建立查询级时间归属；不足以单独区分 DISTINCT 初始选择和来源补全，更不能直接把编译或反优化事件解释为延迟原因。**

## 1. 已读到的实际 overlay 与配置

实际 helper 是 [`QueryExecutionMarker.java`](sources/QueryExecutionMarker.java)，事件名 `graphite.diagnostic.QueryExecution`。在同一个 `broad-query-pressure-worker` 请求线程内，`prepare` 设置 query、参数、1 起始 ordinal、Java thread ID/name，随后 `begin`；调用原 `CrossGraphCypherExecutor.execute`，`finally` 中先 `end`，再记录成功/异常并 `commit`。原结果和异常继续向上传递。准备及结束 helper 仍有少量指令与原调用边界之间的间隔；它是执行调用附近的时间窗口，不是零扰动测量。

读取的 root 字节审计凭据见 [`root-overlay-audit.json`](sources/root-overlay-audit.json)：基线 JAR `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`；overlay `2728888d2820fc9721149302612c94b1001bfd38ee5a8c69244fed59fc9b6ef9`。30,488 个原 ZIP entries 中只改 1 个 JMH class，并新增 2 个 helper class；其他 30,487 个原 entries 保持内容一致。该事实来自已落盘独立字节审计，本方案没有再读取大 JAR。最终采集应复用其按重复 ZIP entry 出现次序逐项核对的方法，而不是只比较唯一名称集合。目标方法在 BCI 36 替换单条调用；所有 production class 内容必须继续相等。

实际 [`capture-plan.json`](sources/capture-plan.json) 为 1 次 marker-only control、3 次 native JFR + LogCompilation 诊断，显式 `ExecutionSample=2 ms`、`Compilation threshold=0 ms`、stack depth 256。这与本方案兼容。保持原冷查询协议、Java 17、ActiveProcessorCount=4、原有 fork/零额外 warmup 及 oracle；禁用 MethodTrace、agentlib/async-profiler、额外预热、强制编译、额外 CompileCommand/编译阈值调整。应区别 JMH 自带的 compiler-blackhole 选项与人为新增的强制编译选项，完整保存实际 fork 参数。

## 2. 34 个 marker 的严格绑定

认证目录为 [`original-catalog.json`](sources/original-catalog.json)，含 34 个对象和 `ordinal,id,query,parameters,requestGraphIds`。[`catalog-receipt.json`](sources/catalog-receipt.json) 绑定冻结 JAR 和真实 64 图 manifest，记录其 ID/顺序与原 TSV 一致。目录提取是此前 root 完成的工作；本审计未再执行提取器。

每个 fork 必须独立完成以下核对，不能拿 control 的 marker 完整性替代三个 profile fork 的检查：

1. `JVMInformation` 的 PID、版本、参数与该 fork 命令及 LogCompilation header 相符；一次实际 workload replay 对应恰好 34 个 marker，ordinal **恰为 1..34**，每值一次。记录开始/结束均覆盖整个 replay。若改成多次 invocation，34 条假设失效，必须显式区分 replay；不得用 modulo 34 隐式修复。
2. ordinal 连接认证 catalog 的 ID；marker 的 query 文本逐字符相等。`parameterEncoding` 必须为 `sorted-string-null-json-v1`，解析 `parametersJson` 后与目录对象精确相等；不要用 Map.toString 或仅 query 文本关联。重复 query 文本仍由 ordinal/目录 ID 区分。原 helper 仅支持 String/null 参数；不支持值将造成缺 marker，必须判诊断无效。
3. 原生 `eventThread` 与 custom `javaThreadId/javaThreadName` 一致，并且为实际请求 worker。保留 native `osThreadId`；它与 Java thread ID 是两个不同域。
4. `success=true`、`exceptionClass` 为空、所有 `markerFailuresBefore=0`。完整 34 个事件与连续 ordinal 是识别最后一次 commit 失败的必要检查；不能只依赖此前失败计数。任何缺失、重复、异常或非零计数均保留原始证据并判归属验证失败。
5. 所有 marker `start <= end` 且按执行顺序不重叠，采用半开区间 `[start,end)`。零长度窗口单独标识；不靠扩张窗口去获取 samples。不在重叠窗口中使用“最近查询”分配。
6. TSV 的 34 行 ID 和顺序匹配同一目录；每行全部 14 个既定 oracle signature 字段与认证 oracle 完全相等，保留原验证脚本及字段清单。若本轮只提供 digest 签名，准确称为 14 字段 oracle 校验，不冒称重新逐行导出所有结果值。超时/失败即使有 finally marker 也不能按成功查询分析。
7. 全真实图输入 manifest/文件数量及前后 hash 收据相同；overlay/base hash 符合批准凭据。hash 属于输入/源码一致性，不是性能 gate 豁免。

工作区 benchmark 的 `replay` 在提交 task 之前启动 TSV 计时，在 `task.get` 返回后停止；执行 receiver 在 worker 内先构造，接着才调用 helper。TSV 包含排队、构造、等待等开销，marker 不包含这些完整环节，亦不包含结果 canonicalization/digest。**不要求 marker duration 与 TSV latency 相等，不把两者之差直接命名为 marker 开销。** 源位置：`graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt` 的 `replay`、`awaitCancellation`、`writeTsv`；冻结 JAR 调用边界则以上述 bytecode receipt 为准。

## 3. 事件配置和字段口径

实际本地事件 schema 已保存为 [`jdk-event-metadata.txt`](sources/jdk-event-metadata.txt)，其生成命令在 [receipt](sources/metadata-command.json)。实际 runtime schema 优先于上游示例。当前 JFC 在 [profile](sources/capture-profile.jfc) 与 [control](sources/capture-control.jfc)，不要把它们与 JDK 自带同名 profile 混淆。

| 事件 | 必要设置/字段 | 核查边界 |
|---|---|---|
| `graphite.diagnostic.QueryExecution` | enabled=true, threshold=0 ns, stackTrace=false；startTime/duration/eventThread 与全部 helper 字段 | 34 条连续绑定，end 在 commit 之前；commit 时间不是查询结束时间 |
| `jdk.ExecutionSample` | enabled=true, period=2 ms；`sampledThread`, `stackTrace`, `state`, startTime | **必须读取 sampledThread**，不是 eventThread；这是采样事件计数，不是 CPU 时长 |
| `jdk.Deoptimization` | enabled=true, stackTrace=true；`compileId,compiler,method,lineNumber,bci,instruction,reason,action,eventThread,stackTrace` | 瞬时事件，无可用于计算反优化耗时的 duration；记录完整栈和事件 method/BCI，两者不互相替代 |
| `jdk.Compilation` | enabled=true, threshold=0 ms；start/end、`compileId,compiler,method,compileLevel,succeded,isOsr,codeSize,inlinedBytes,eventThread` | schema 的字段实际拼写为 **succeded**；这是编译时间区间，不是请求独占 CPU；不能假定有 compilation stackTrace |
| `jdk.CompilationFailure` | enabled=true | 保留失败项；unfinished compiler XML fragment 不自动算 compilation failure |
| `jdk.JVMInformation` | enabled=true, period=beginChunk；pid,版本,参数,jvmStartTime | 同 fork 认证；不要假定 jvmStartTime 与 XML time_ms 完全相等 |
| `jdk.ActiveSetting/ActiveRecording/DataLoss` | enabled=true | 核对实际生效频率/阈值/录制边界/丢失事件；保存原配置与有效设置，缺设置不能默认为所期待值 |
| `jdk.GarbageCollection/GCPhasePause` | 当前实际配置均启用且 threshold=0 ms | 可记录相交暂停，不能把 GC 总持续时间全算查询暂停 |

本地自带 [profile.jfc](sources/local-profile.jfc) 默认 ExecutionSample 10 ms、Compilation threshold 100 ms；[default.jfc](sources/local-default.jfc) 分别为 20 ms 和 1000 ms，并默认关闭 Deoptimization stackTrace。默认阈值会过滤掉已有日志中约 10 ms 的 callback 编译。因此本轮必须显式覆盖 Compilation 阈值，不能用“无事件”证明“未编译”。CompilerInlining/CompilerPhase 不在本轮必要范围，不额外扩大采集。NativeMethodSample 当前没有启用；如果实际设置出现它，单列计数及周期，不与 ExecutionSample 混成统一 CPU 分母。

**原生 JFR 采样不是旧 async-profiler CPU 采样。** OpenJDK sampler 只尝试采样处于 `_thread_in_Java` 的线程，单次 Java 采样轮的上限为 5 个 sample；目标线程在捕获前改变状态还可能丢过一次。2 ms 是调度周期设置，不保证每线程每 2 ms 一个样本。不能计算 `samples × 2 ms = CPU ms`，不能以没有编译器线程样本推断 JIT 没有 CPU。NativeMethodSample 也受可见 Java frame 条件约束，不是完整 native profiler。来源：[OpenJDK 17 sampler](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/hotspot/share/jfr/periodic/sampling/jfrThreadSampler.cpp)，带行号 [excerpt](sources/jfrThreadSampler.cpp.excerpts.txt)。

栈保留 method 类名/名字/**descriptor**、BCI、line、frameType、Java/native 标志和 truncated。未知 BCI/line 保留 `-1`，缺栈单独统计；栈方向明确为 leaf-first。原生 JFR 的 `JIT compiled` 或 `Inlined` 标签不能独立区分 C1/C2。参考本地 [RecordedFrame.java](sources/RecordedFrame.java)。stackdepth=256 是上限设置，不保证所有栈完整。

## 4. 查询时间归属与守恒

| 输入 | 归属规则 | 不可推断的内容 |
|---|---|---|
| ExecutionSample | 用 sample startTime 查找唯一 `[queryStart,queryEnd)`；按 sampledThread 分请求线程、明确命名的查询 worker、其他 Java 线程。worker 跨线程只在唯一独占 query 窗口内做时间归属，并保留线程和栈证据。 | 一个 sample 不代表 2 ms CPU；其他线程不能因为与 query 同时发生就改称 query CPU；worker 时间重叠不是异步任务所有权的额外证明。 |
| Deoptimization | 瞬时时间同样查找唯一 query；保存 eventThread、compileId 和完整 trap identity。缺窗/多窗均保留 unmatched/ambiguous。 | 不从单事件得出它耗时多少、触发查询是唯一原因、同 query 的延迟全部来自它。 |
| Compilation | 同时保留开始所属 query、结束所属 query、所有相交 query 和各 overlap ns。编译可以在一个 query 排队、另一个 query 完成，不能强行单归属。 | overlap 不是阻塞时间、独占 CPU，也不证明该 query 发起编译。 |
| GCPhasePause | 按真实暂停区间相交；同 query 内暂停区间先取 union 再报告。 | 不把嵌套阶段相加，不把 GC wall duration 等同暂停。 |

每类瞬时事件应满足 `总数 = 唯一归属 + 无窗口 + 歧义`，每个唯一归属再按互斥线程类别守恒。Compilation 用原事件序号/identity 保存唯一 ledger；一个事件出现在多个 overlap 列表不重复计为多次编译。分别列“各编译任务 wall duration 之和”（可能并行）与“查询窗口中编译活动时间 union”，前者可超过查询 wall duration，两个都不是 CPU 时间。对零样本的小窗口直接写“样本不足”，不做百分比补齐或插值。

对 full stack 的包含关系、交集和叶子命中分别统计。例如 contains→callback 同时命中的一个 sample 只能在整体分母计一次；inclusive 名称列表不能相加。百分比明确分母是该 query 内的哪一类 ExecutionSample 数量，并报告分子/分母原数。旧 phase/async CPU 百分比不能与本轮原生 JFR 百分比直接比较。

仅 outer marker 时，DISTINCT 初始选择/来源补全默认 `phase=unknown`。明确且唯一的 phase-specific 栈帧可作为“该采样栈包含某路径”的证据；共享 callback、缓存查找或 compiled method 名称不能推断完整阶段边界。不可沿用另一 fork 的 phase 时间窗口，不可将整条 query 的 deopt 直接归到 provenance。BCI 核对使用本次**相同生产字节**的 class/javap 凭据；反优化事件 method 可为被内联方法，而 compilation method 为根编译方法。

## 5. JFR 与 LogCompilation 时钟：先分开，身份验证后才尝试关联

JFR consumer 按各 chunk 的 startTicks、startNanos、ticksPerSecond 转成 epoch ns；使用 `RecordedEvent.getStartTime/getEndTime` 并保留整数秒/nanos，不以浮点 JSON 秒作为 join key。本地 [TimeConverter.java](sources/TimeConverter.java) 展示转换。[Event.java](sources/Event.java) 确认 begin/end/commit 的区别。**本轮原生 JFR 事件与 marker 已共享 JFR 时间域，查询归属无需先与 XML 对齐。**

LogCompilation 的 stamp 由输出流相对计时器以 `%.3f` 秒格式输出；根 `time_ms` 来自打印当时 wall millis 减相对 uptime millis。近似 epoch 可写为 `time_ms*1,000,000 + stamp*1,000,000,000`，但存在毫秒舍入、anchor 量化及两个记录点的先后间隔，不能把这个表达式当作与 JFR 纳秒时间同精度的恒等式。仅 stamp 的三位小数就已有约半毫秒舍入量级；整体误差界还不能仅靠该数字证明。来源：[ostream.cpp](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/hotspot/share/utilities/ostream.cpp) / [excerpt](sources/ostream.cpp.excerpts.txt)，[xmlstream.cpp](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/hotspot/share/utilities/xmlstream.cpp) / [excerpt](sources/xmlstream.cpp.excerpts.txt)。

可靠关联步骤：

1. 仅在**同 fork**中比较，先核对 PID、JVM 版本和参数。compileId 跨 fork 可能复用，绝不跨 fork 匹配。
2. 先用 compileId/compiler、根 method+descriptor、OSR 属性建立 JFR Compilation ↔ XML task/nmethod 身份关联；明确 `task_queued`、编译 task、`nmethod` 发布、`make_not_entrant`、runtime `uncommon_trap` 各自语义。编译器解析树中的 uncommon_trap 不是运行时 trap 计数。
3. JFR Deoptimization 与 XML **运行时** uncommon_trap 再以 compileId、trap method/BCI、reason/action 及 OS thread ID 匹配；XML thread 是 OS ID，不能拿 JavaThreadId 相比。OpenJDK 代码先发 JFR event，再打印 XML runtime trap，因此是关联的同一机制事件而非严格同步时间点。
4. 特别注意 event 的 compileId 来自根 nmethod，`method` 却设置为 trap_method。已有 contains BCI 37 / callback BCI 322 的内联案例不能要求 JFR Deoptimization.method 与 Compilation.method 相等。来源：[deoptimization.cpp](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/hotspot/share/runtime/deoptimization.cpp) / [excerpt](sources/deoptimization.cpp.excerpts.txt)。
5. 多个唯一身份 anchor 分别列 JFR 时刻、XML stamp/近似 epoch、差值、排序和残差分布，检查全录制期间漂移。不能仅拟合一个偏移后声称时钟已对齐，也不能用 compile end 与 nmethod publication 的差当纯时钟偏差。若无足够独立匹配、残差无法解释或无法给出可靠误差界，保留两条时间线，XML query 归属为 unknown。
6. 即使有可解释的校准，靠近 query 边界且误差区间跨界的 XML 事件仍属 ambiguous；不根据期待的 query 调整时钟。优先报告 JFR 已直接确定的 query 归属，XML 提供机制身份与编译生命周期信息。

已有无 tracing 的三个日志能证明 C2 publication、随后 runtime unstable_if/reinterpret、make_not_entrant 和 C1/重排队时间关系，但没有旧查询 marker，不能追溯硬分配到某个 query。它们是独立复现来源，不是本轮共用的时间锚。

## 6. 建议独立审计产物与完成标准

- `query-binding.json`：每 fork 全 34 ordinal/ID、query/参数核对、JFR start/end、线程 ID 域、TSV latency、oracle 14 字段结果、marker/overlap/失败计数；附完整 34 行可读表。
- `event-ledger.jsonl`：逐事件唯一编号、原 schema 字段、原时间、thread、stack、唯一窗口或 overlap 列表、unknown 原因；保留零事件及无栈情况。
- `query-native-jfr-summary.json`：事件总量守恒、线程分类、full-stack inclusive/交集/叶子、Compilation 区间与 union；禁止把采样计数写为 CPU 时间。
- `clock-correlation.json`：同 fork 身份匹配依据、XML 时钟原值、JFR 时间、残差和不确定边界，含所有 unmatched/ambiguous，结论可为“未能证明对齐”。
- `settings-and-input-receipt.json`：实际事件设置/schema、JDK/命令、overlay/生产 class 凭据、图输入前后凭据、JFR/XML/TSV/oracle hash、丢失事件检查。

通过上述完整性检查只说明该轮**诊断可解释**，不代表优化验收、稳定收益、延迟归因成立或先前拒绝被推翻。所有 profile fork 原值均保留，无异常重跑以替换失败结果。

## 来源收据

[`sources-receipt.json`](sources-receipt.json) 列出 19 个来源副本的路径、原来源、字节数和 SHA256；[`collect-sources.py`](collect-sources.py) 仅用 Python 读取本地文本/zip 与下载 pinned OpenJDK 源码。上游版本固定为 `jdk-17.0.18-ga`，它是 HotSpot 语义来源；本机 Homebrew build 的真实字段以已导出的 metadata 和本地 src.zip 为准，不声称上游 tag 与本机构建全部字节相同。上游 [metadata.xml](https://raw.githubusercontent.com/openjdk/jdk17u/jdk-17.0.18-ga/src/hotspot/share/jfr/metadata/metadata.xml) / [excerpt](sources/metadata.xml.excerpts.txt) 支持事件字段与事件类型口径。
