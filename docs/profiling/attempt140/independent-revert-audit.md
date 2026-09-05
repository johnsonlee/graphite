# Attempt 140 显式回退独立核验

通过。完整回退 HEAD 为 `b94b8caa8dea10d1d2ddb74a0a0c39a3ab5351f0`，直接父提交是已拒绝候选 `4215b66e462675baeb3e1b1f2013cf7e6de01812`，候选父提交为 `aede4c82f66a925ba9df3fc8588c6e1399c17f61`。仅只读检查 Git 对象、工作区和证据 hash，未构建、测量、运行 Java 或提交。

- candidate→revert 共 4 个文件变化：**仅 1 个非 docs 文件**，即 `MappedWebGraphBackedGraph.kt`。独立按目标方法范围验证，候选恰是原 map→IntArray 两行替换，回退恰好恢复原字节；同文件其他三处相同 map 文本保持原状。
- 全部 **130 个 main/JMH 文件的路径集合及内容**与 frozen main `4e328b0109e13c896b74004823fb049fcb19251a` 完全相同；工作区文件亦一致。
- 全部 **168 个 test 文件的路径集合及内容**与父 aede 及候选相同；既有 `ParallelDistinctDisjunctionTest.kt` 的 **4 个 pure-four-OR correctness 测试**完整保留。
- aede→revert 最终 **322 个变化路径全部位于 docs/**，包含历史诊断、测量及回退证据；无最终生产、测试、JMH 或 gate 代码变化。
- Git tracked 工作区与 index 干净；恰好保留原 **8 个无关 untracked 文件**，每个 size、mtime_ns、SHA256 与 140 实施前快照完全相同。

证据复制再次独立核验：`copy-receipt.json` 的 **161 个条目**，原始临时文件、工作区副本、回退提交 blob、候选提交 blob 均与记录 SHA256 完全一致；复制收据本身亦未因回退改变。最终独立审计收据的 **13 个 hash** 和 unchanged runner 复制收据的 **2 个 hash**全部一致。没有重新生成或修改原测量结果。

候选源码 SHA256：`120d23247d266e4b9a57c7a53644154307725b32156531ce62822f914dfb98ba`；恢复源码 SHA256：`9133289c4382b59f4bc880f9de3d8aee0930d449a32f9a487893dabb56255cb7`。全 130/168 路径及 hash、161 个复制条目、准确改动列表和 untracked 快照在 `independent-revert-audit.json`；`independent-revert-audit.py` 可只读复算。

本结论仅证明回退和证据完整性。Attempt 140 因 v3 `mixed-four-few-rows` 两组重复退化维持拒绝，不重开性能判断；此次审计不证明新回退 CI 通过，也不表示 CallSite 池已移除或最终 10x 已达到。
