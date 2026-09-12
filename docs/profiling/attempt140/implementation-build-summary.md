# Attempt 140 implementation and build

唯一生产变化：`MappedWebGraphBackedGraph.parallelRawDistinctCallSiteStringProjection` 的 `predicatePropertyIndexes` 从 `predicates.map` 改为 `IntArray(predicates.size) { index -> requiredCallSiteStringPropertyIndex(predicates[index].property) }`。循环、其他变量、分配、执行策略均未编辑；不是 136 while、138 posting 或池/validator 变化。

普通 clone 为 `repo`（真实 `.git` 目录），detached parent `aede4c82f66a925ba9df3fc8588c6e1399c17f61`。130 main/JMH 文件中仅此文件不同于 frozen main 4e；168 test 文件全部等于 parent。主工作区和八个无关 untracked 文件未动。补丁 `candidate-source.diff`，精确前后字符串与 SHA 在 `prebuild-source-receipt.json`。

Java17、`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`，一次运行：

```
./gradlew :webgraph:test :webgraph:detekt :webgraph:jmhJar :webgraph:verifyJmhJarExcludesTests --no-daemon
```

实际 exec session **43629**，terminal exit **0**，耗时 **128.21 秒**。187 tests，6 suites，0 failure/error/skip；含既有 4 个 pure-four-OR 测试。全部 XML 已单独归档到 `test-results/`，准确测试名、hash、计数在 `build-receipt.json`；原命令与完整 `build.log` 保留。未运行性能。

产物 `candidate-jmh.jar` 设为 0444，SHA256：
`fb58d962d349a5c526bec229b996f08d94800702688ed202c1e7e771c3e4e357`

候选源码 SHA256：
`120d23247d266e4b9a57c7a53644154307725b32156531ce62822f914dfb98ba`

受保护 baseline JAR 读取前后 SHA 均为 `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`，没有修改。

## 实际字节码

用两份实际 JAR 做 `javap -c -l -p`，并从 class Code 属性独立读取长度。节点回调对应 captured 参数由 `java.util.List` 变成 primitive `int[]`；编译器同时将该回调的生成名从 `$lambda$32$lambda$31$lambda$30` 改为 `$lambda$31$lambda$30$lambda$29`。

源码 line580 原来是 `List.get`、`checkcast Number`、`Number.intValue` 三条指令，现在替换为一个 `iaload`，紧随其后的 stringIds `iaload` 保留。节点方法 **790→780 字节，357→355 指令**。在规范化 constant-pool 编号、生成 lambda 数字和 branch 绝对位置后，节点指令 diff 仅该三变一，见 `node-instruction.diff`；原始反汇编也完整保存。

节点内静态调用点仍有 **5 个 List.get、2 个 Number.intValue、1 个 Integer.valueOf**。原 range getIndices、IntIterator.nextInt、exact-set contains 各 1 处，数量不变。不能称为所有 List/boxing 消失，也不能把静态调用点减少当作已测分配或速度收益。

外层 raw DISTINCT 方法 Code **2171→2123 字节**；worker 方法 **190→190 字节**，默认桥接 **38→38 字节**。实际长度与方法签名见 `bytecode-receipt.json`，没有推测 C2 阈值或性能效果。

本任务的构建、测试及两次 javap 均已结束，无待运行会话。根线程独立确认进程结束后启动了自己的 v3-control session 64343；之后一次全机器 Java 空集断言检测到该新测量进程，未将其误报为“当前机器没有 Java”，也未启动任何后续 Java 检查。`handoff-receipt.json` 绑定源码、JAR、构建及字节码收据。未 commit、未运行性能、未作接受或 10x 声明；后续计时和最终接受/拒绝由根任务按完整门槛处理。
