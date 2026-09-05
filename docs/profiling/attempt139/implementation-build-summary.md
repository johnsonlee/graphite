# Attempt 139：两处 inline 实施与构建结果

只修改正常 clone 中 `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt`：callback-taking `updateInts(IntBuffer, validate)` 和 `updateLongs(offset, count, validate)` 各增加一个 `inline`。去掉这两个 modifier 后，文件与冻结 main 字节一致；callback body、默认空 lambda、CRC、预算、取消及循环边界没有源码变动。未引入 137 primitive consumer、138 投影、线程池或其他策略。

130 个 main/JMH 文件与冻结 main 比较，仅上述文件不同。源码 SHA：`e702dd25666c83a98e6222d4634fd985b78b6edb3dcda5d1ae0f2b6269e14793`。修改后只进行一次指定构建，未发生 Kotlin 可见性错误，无需调整权限或增加生产 accessor。

Java 17、`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`、`--no-daemon`：`:webgraph:test`、`:webgraph:detekt`、`:webgraph:jmhJar`、`:webgraph:verifyJmhJarExcludesTests` 全部成功。原始 XML 为 6 suites / 187 tests，failure / error / skip 均 0。构建日志为 `BUILD SUCCESSFUL in 2m 3s`。未修改任何测试或主工作区文件。

冻结 candidate JAR：`candidate-jmh.jar`，SHA `9d0bfd1d6cfcb9891c064a3a3784d7742c45b5692be004e1ce90996e02cec4ca`，与刚构建的 JMH JAR 字节 hash 一致。受保护 baseline JAR 仅用于读取 javap/字节码比较，没有写入。

## 实际字节码机制

读取两个冻结 JAR，分别保存 `javap -c -p` 原文与命令，并独立解析 classfile 的 Code attribute，得到精确 `code_length`：

| 方法/静态调用点 | base | candidate |
|---|---:|---:|
| Companion.load Code bytes | 1,065 | 1,065 |
| Companion.validatePersistentIndex Code bytes | 340 | 1,232 |
| validatePersistentIndex 调 callback-taking validator 方法的调用点 | 5 | 0 |
| validatePersistentIndex 创建 Function1 lambda 的 invokedynamic 点 | 4 | 0 |
| validatePersistentIndex 的 Ref 构造点 | 3 | 0 |
| validatePersistentIndex 对应生成的 lambda 方法 | 4 | 0 |

展开位置是 `load → validatePersistentIndex` 的后者，不能把整个 `load` 说成变大。候选将三个 Int 校验循环、默认空 Long 校验循环和有谓词的 Long 校验循环直接展开在该辅助方法；展开后的调用路径中没有 `Function1.invoke`、`Integer.valueOf` 或 `Long.valueOf`。原基线在 caller 本身也没有这些 per-element 调用，它们位于 caller 调用的 callback-taking validator 方法里，因此必须按调用链比较。

泛型 inline 方法及默认入口仍保留在 classfile，里面仍可看到 `Function1.invoke/valueOf`；这里证明的是已检查的加载校验调用路径不再调用这些入口，不能宣称整个 JAR 没有 boxing。编译器为 private 字段访问生成了 synthetic accessors，这是编译产物，没有扩大源码可见性。CRC、scratch put、workConsumer、interrupt 检查仍在展开后的循环内。

1,232 是实际字节码长度，不是源文件长度或本地 JIT 机器码大小。本次没有测量/查询当前 C2 huge-method 阈值，不凭记忆判断离该阈值多远，也不据此声称 C2 一定编译或取得收益。静态调用点数同样不是运行时调用数、对象数或实际节省 bytes。

文件：`build-command.json`、`build.log`、`build-receipt.json`、`prebuild-source-receipt.json`、`candidate-source.diff`、`bytecode-receipt.json`、`mechanism-receipt.json` 和四份 javap 原文。`inspect-bytecode.py` 保存 Code 长度解析与命令。所有构建及 javap 进程均已结束；尚未运行任何性能任务，交由 root 继续预设真实数据验证。
