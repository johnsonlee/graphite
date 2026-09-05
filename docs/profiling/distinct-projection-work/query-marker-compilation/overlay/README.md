# 原34查询的离线 JFR marker 诊断 JAR

这只是诊断工具，不是 Attempt 141、生产候选或性能验收。未运行查询、加载图或录制 JFR。

冻结输入 JAR 保持不变，SHA-256：`a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`。

诊断输出 `diagnostic-jmh.jar` 已设只读（0444），SHA-256：`2728888d2820fc9721149302612c94b1001bfd38ee5a8c69244fed59fc9b6ef9`。

唯一原条目变化是 `io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.class`。其 `replay$lambda$33$lambda$29`（40-byte Code）BCI 36 从 `CrossGraphCypherExecutor.execute(String,Map)` 的 invokevirtual 改为 `QueryExecutionMarker.execute(executor,String,Map)` 的 invokestatic。常量池追加5条、208 bytes；方法 Code 长度、帧、调试属性及其他58个方法均不变。独立 javap 全文差异只有这一条调用。没有修改原 executor 构造、提交/等待、查询顺序、参数、结果校验、预算、池、缓存或生产类。

原 ZIP 有30,488个条目、30,467个唯一名称；没有去重原有重复条目。按条目序号逐项比较，30,487个原条目的解压内容逐字节相同，只改变上述 benchmark class，新增 helper 和其 nested Event 两个 class。不是“所有 JMH 字节相同”，也不宣称 ZIP 压缩容器字节相同。完整条目比较在 `entry-comparison.json`，第二次不调用 patch 脚本的检查在 `independent-structure-audit.py/json`。

## Marker 契约

Event Name：`graphite.diagnostic.QueryExecution`。显式启用该事件、threshold=0、stackTrace=false。内建 `startTime` 与 `duration` 表示 begin/end 窗口。字段为：

- `ordinal`：每个新 JVM 中实际进入 helper 的顺序，从1开始。
- `query`：传入 executor 的原字符串。
- `parametersJson`：按 key 排序的紧凑 ASCII JSON；`parameterEncoding=sorted-string-null-json-v1`。仅支持 String key、String/null value；不修改或替换传给 executor 的原 Map。
- `javaThreadId`、`javaThreadName`：事件创建的实际 Java 线程；也须与 JFR 内建 eventThread 对照。
- `success`、`exceptionClass`：返回或异常结果，成功时 exceptionClass为空。
- `markerFailuresBefore`：创建该事件前累计的 marker 错误数。

本次导出的原34 catalog 中31条空 Map，另3条只有 String `term` 参数，均在支持范围内。必须同时比较 ordinal、原query、完整参数及TSV的query ID，不能仅凭重复query文本绑定。独立检查保存了34条期望参数编码。

helper 在 prepare 完成字段和参数编码后调用 begin；原 executor 调用被 try/catch/finally 包围，finally先end再写状态并commit。字节码确认捕获的原 Throwable 引用直接 athrow，没有包装异常；finish 自身 marker 异常被隔离计数，不替换原返回或异常。准备失败仍执行原 executor，但不产生该 marker。诊断验证必须 fail-closed：完整34事件、ordinal严格1..34、无重复/缺失、顺序/窗口不重叠、query/params/线程一致、全部成功且 `markerFailuresBefore=0`。事件未启用、丢失或末尾commit失败同样由完整数量约束拒绝；不能静默丢弃不合格窗口。

## 验证及扰动边界

`build.py` 只执行 Java17 javac、离线 Python patch 和 javap；真实构建 session59734 已exit0。另用 `-Xverify:all` 加载原 benchmark 类但不初始化它，验证 helper 参数编码的空值、排序、转义、Unicode和不支持类型拒绝；没有调用 executor或创建 JFR事件。所有命令、日志和时间在 `commands.json`、`verification-commands.json`；实际反汇编在 `*-javap.txt`，精确改动在 `benchmark-bytecode.diff`。冻结输入 JAR 前后 hash一致。

Marker 仍会扰动执行：新增 helper 调用、事件分配、参数编码、计数及首次 class/Event 初始化。字段准备发生在事件开始之前、提交在结束之后，但它们仍包含在原 TSV 延迟范围内。窗口也包含 begin返回到executor调用、executor返回/异常到end之间的少量helper指令；它不是无开销的精确方法入口探针。窗口不含 executor构造、排队/等待或返回后的 canonical结果编码。后续 JFR 可用于关联本录制中 native 编译/去优化/采样事件，不能把该 JAR 的时间当原基线或生产加速结果，不能仅凭时间重叠推断因果。
