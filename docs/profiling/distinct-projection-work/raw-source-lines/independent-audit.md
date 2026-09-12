# 原始 frame 元数据独立核验

通过。只读审查 RawFrameDetails.java 并独立按整数纳秒时间分区，没有调用父 analyze.py，没有再次运行 Java。导出器仅选择 `jdk.ExecutionSample` 中包含 raw-family 方法的事件，保留 JFR leaf-first 原始帧顺序、线程、时间、truncated、method、lineNumber、bytecodeIndex、frameType、javaFrame。原来的 ProfileWindows 格式只保留 method，元数据不是从旧 collapsed 栈推测出来的。

三份导出分别有 132 / 126 / 92 个 raw-family CPU 事件，共 350；全部恰好落入一个既有 initial/provenance 时间并集，没有重复分配、漏分配或 truncated 栈。将方法名按原格式规范化并反转后，逐 query/phase/thread 的完整方法栈直方图与旧 phase JSON 精确一致。

- Targeted initial：75 / 74 / 60，共 **209 个 raw inclusive 应用 CPU 样本**。
- 其中节点 lambda 为真正 leaf：32 / 36 / 30，共 **98**；所有 98 都有正 lineNumber 和非负 BCI，逐 `(method,line,BCI,frameType)` 直方图与新 summary 一致。
- 98 个节点 leaf 标记为 **63 Interpreted、35 C1 compiled**。这是原始 frame 标签，不能据此独立解释性能或当作稳定 C2 热点。
- Dense provenance：53 / 46 / 28，共 **127 个 raw inclusive 应用 CPU 样本**；不是 98 的另一个分母。
- 剩余 14 个样本属于 dense initial。不能把 350 全当成 targeted 节点 leaf。

导出源码和三个输出文件 SHA256 与 export receipt 一致；JFR SHA256 与既有 phase receipt 一致。本核验没有重新 hash 或独立解码原始 JFR，因此是“导出源码 + 已有输入收据 + 全事件分区/逐栈守恒”的独立审计，不声称第二次 JFR 解码验证。

BCI/行号现在可用，但合成 Kotlin 行号必须绑定**同一个冻结 JAR 的精确 class**，用 LineNumberTable/SMAP 才能映射；不能直接指向仓库同号源码。BCI 是记录中的字节码位置，不是硬件 PC、stall 类型或可消除耗时。内联行表、采样归属和 tracing/编译状态限制仍保留。下一步映射由根线程负责，本审计未实现优化、重测或更改此前拒绝判定。

脚本 `independent-audit.py`；完整数值与边界 `independent-audit.json`。
