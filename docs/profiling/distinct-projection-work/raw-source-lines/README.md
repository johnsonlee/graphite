# raw DISTINCT 的原始源码行与字节码位置

这次离线分析补回了此前聚合方法栈丢弃的 frame 元数据。没有重采样、重建或改写冻结 JAR，没有执行新候选。三份原 JFR 前后 SHA-256 均等于此前记录；只运行独立的小型 JFR 解码器和 javap。完整命令与输入哈希见 `export-receipt.json`、`bytecode-receipt.json`。

## 新得到的定位

原先只能定位到大节点 lambda 的 targeted initial **98 个 CPU 叶样本**，全部带有行号和 BCI。它们在冻结 JAR 的精确 class 中均对应有效字节码指令，记录行号也符合该方法 LineNumberTable；Kotlin 合成行使用该 class 自身的 SMAP 还原。

| 记录的字节码区域 | targeted initial 叶样本 | dense provenance 叶样本 |
| --- | ---: | ---: |
| range/iterator、OR 遍历控制 | 42 | 23 |
| 谓词属性索引的 List.get / 拆箱 / 数组取值 | 18 | 3 |
| raw offset、字段寻址和读取 | 17 | 5 |
| exact set 的选择、检查与调用位置 | 13 | 8 |
| selected tuple 构造/检查区域 | 0 | 4 |
| 中断、预算、scratch 写入、LIMIT 检查 | 8 | 0 |
| 合计 | 98 | 43 |

这些区域按 BCI 不重叠，只分摊 lambda 自身的叶样本。全部 targeted initial 应用 CPU 为 221，raw 调用族为 209；dense provenance raw 为 127。其他方法叶样本（例如 contains 内部、buffer 内部）保持原分类，不能把本表与它们的 inclusive 数直接相加。上表不是各区域的精确耗时、CPU 百分比或可省成本。

具体例子：targeted 的 18 个谓词索引区域叶样本中，8 个记录 BCI 283（`List.get` 调用），8 个记录 BCI 291（`Number.intValue` 调用），另 2 个记录 cast/array-read。源码是 `MappedWebGraphBackedGraph.kt:580`；这比仅凭 `ArrayList.get` 方法名更能区分访问点。它没有证明把该 List 改成 IntArray 就能通过整体 P95 gate。

## 对下一步的约束

1. range/iterator 仍是有样本支撑的成本，但已被 Attempt 136 直接修改、测量并拒绝。补出 BCI 不改变其失败结论，不作为再次尝试相同改动的理由。
2. 谓词属性索引的 boxed List 访问现在有精确调用点证据，与 136 删除遍历 range 的方向不同；其样本规模仍不足以承诺主导成本或 10x。任何实现都须作为单一新假设，经过原完整门槛及新增四词 OR 覆盖。
3. 本批 targeted lambda 叶样本标记为 **63 Interpreted、35 C1 compiled**；dense provenance 为 **3 Interpreted、40 C1 compiled**。索引已热不等于这段代码已达到稳定的 C2 状态。标签仅描述这三份带 tracing 的录制，不能据此认定无 profiler 的 gate 也在解释执行，不能用它推出调参或强制编译的收益。
4. 图级并发的区间审计仍不能归为池的纯开销，去掉 CallSite 池和最终 10x 都尚未完成。这里没有 Attempt 140 生产改动。

## 守恒与解析细节

`RawFrameDetails.java` 对原 `jdk.ExecutionSample` 导出 leaf-first 逐帧方法、BCI、行号、类型以及事件时间/线程。三份录制导出 132/126/92，共 350 个 raw CPU 事件。`analyze.py` 在原阶段调用的半开区间内归属事件；按 query/phase/thread 重新聚合的完整方法栈与旧 JSON 精确相同。targeted 209、dense provenance 127、dense initial 14；没有缺失、重复或截断。

`map-bytecode.py` 将全部 148 个真实节点 lambda 叶样本（含 dense initial 的 7 个）绑定到该 class 的指令及行表，保留 BCI 对应的指令文本、原记录行号和 SMAP 文件/行/调用点，见 `source-mapping.json`。`per-node-javap.txt` 是完整方法反汇编及行表；`class-smap.txt` 为完整原 SMAP。

开发映射器时首次断言暴露出 **BCI 121 在同一 LineNumberTable 中有两个行条目：3637 和 3638**。已将错误的“一 BCI 只有一行”假设改为保留同一起点的全部候选，并验证 JFR 原记录行号属于候选集合；没有更改采样数据或重采集。最终 148 个样本全部通过。BCI 是 JVM 字节码位置，不是硬件 PC；某个调用位置的采样也不是该单条指令的排他成本。

独立二次审计见 [事件分区审计](independent-audit.md)。其边界是检查导出源码、已有哈希凭据及全部导出事件与旧分区的守恒，没有伪称重新独立解码 JFR。[字节码与 SMAP 独立审计](bytecode-independent-audit.md) 则直接解析 JAR 内的 class 二进制，另行验证常量池、Code、指令边界、操作数、行表和 SMAP，没有运行父映射脚本或使用 javap 输出作为唯一验证依据。全部 148 个样本通过。
