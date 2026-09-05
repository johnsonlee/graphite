# 字节码与 SMAP 独立核验

通过。没有运行父 `map-bytecode.py`，也没有启动 Java 或新测量。独立 Python 直接解析冻结 JAR 中的 class 二进制：constant pool、方法 Code、指令边界、LineNumberTable、SourceDebugExtension；再使用通用 SMAP stratum/文件/行范围解析，核对手工 relevant 映射。

重新计算冻结 JAR SHA256 为 `a5c2db2b0020798488916ec86902459d1044a7dcef606a73e00055883cdf5abe`，与原 phase 输入收据一致。JAR 内 class 与独立提取文件逐字节相同，SHA256 为 `41f1966d893020d389727faf93bb49ad28d4c0754f82485d9c7774d5ae3097ca`。两份完整 javap 输出、方法摘录、SMAP 与各自收据 hash 一致；方法所有指令起点和行表与 binary class 一致。这里证明绑定到原测量输入 JAR，没有重建 JAR 或声称可重现编译。

节点方法 Code 长 **790 字节**。全部 **148 个真实节点 leaf 样本**的 BCI 都在有效指令起点；独立校验实际 opcode、分支目标、constant-pool 索引及调用/类型符号与记录的 instruction 文本一致。记录行号均在该位置最新起点的 LineNumberTable 候选中。每个文件/源行/inline 调用点与从 class 原始 SMAP 通用解析所得完全一致。

**起点 BCI 121 确有 [3637, 3638] 两条行表记录。** 它们共同适用于下一起点之前的指令；本批实际样本落在 BCI 123 和 128，共 4 个，记录行为 3638。父映射完整保留了两候选，没有错误地要求样本必须恰好落在 BCI 121，也没有覆盖掉另一行。

| 不重叠 BCI 区域 | targeted initial | dense provenance |
|---|---:|---:|
| range / iterator / OR 控制 | 42 | 23 |
| 谓词索引读取 | 18 | 3 |
| raw offset / 字段地址读取 | 17 | 5 |
| exact set 选择和调用 | 13 | 8 |
| selected tuple 区域 | 0 | 4 |
| 中断 / 预算 / scratch / LIMIT | 8 | 0 |
| 合计 | **98** | **43** |

余下 dense initial 为 7，总计 148，逐录制/阶段/源位置直方图全部守恒。Targeted 谓词索引的 18 个样本确为 BCI 283 `List.get` 8 个、BCI 291 `Number.intValue` 8 个、cast/array-read 2 个。Frame 标签 targeted 为 **63 Interpreted / 35 C1 compiled**，dense provenance 为 **3 / 40**，另 dense initial 为 **5 / 2**。README 表、具体例子和这些边界均一致。

区域是宽泛的控制流范围，包含 guard/分支；例如 selected_tuple 的 4 个位置样本不是实际创建 tuple 的次数。BCI/行表指向记录的字节码位置，不能解释为排他的指令 CPU 成本、硬件 PC、stall 或收益。98/43 仅是对应 lambda 自身 leaf 子集，不能将它们当作 209/127 raw inclusive 的全部成本；与其他方法 inclusive 样本亦不可直接累加。编译标签只描述本次 instrumented captures，不推出无 profiler gate 的编译状态。已拒绝的 136/138 保持拒绝，本审计没有新的性能结论。

文件：`bytecode-independent-audit.py` 可独立复算；`.json` 保存哈希、重复行候选、逐阶段计数与验证范围。先前导出/分区审计继续提供帧事件与旧方法栈的绑定，本次没有第二次 JFR 解码。
