# Attempt 137 独立源码审查

候选 clone：`/private/tmp/graphite-attempt137.dcywsuq7/repo`。仅审查一个生产文件 `graphite-webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/MappedCallSiteStringIndexView.kt`，不运行 Java、构建、测试或采样；实际编译和 bytecode 验证由父任务负责。

**没有发现这份窄 diff 的语义问题。** 机械还原两处 import、两处 callback type/default 和两处 `accept` 后，文件逐字节等于 frozen main `4e328b0109e13c896b74004823fb049fcb19251a`。tracked diff 没有其他文件。候选 SHA-256 为 `8a803b81cfd2b9c455276c33640cafff9963a11673eed3a504b59fa5a63b6672`，main 为 `6abdd90ff1f4676dbfeae568e13e3f4419c45fc62d63ab1b9ec981ea1282a7a5`。

## 行为保持

- `updateInts`/`updateLongs` 改接收 Java `IntConsumer`/`LongConsumer`，预期调用 descriptor 为 `accept(I)V` / `accept(J)V`，不再通过 Kotlin `Function1.invoke(Object)` 传 primitive 参数。需要实际 javap 确认 Kotlin SAM adapter 未把 boxing 移到另一层；源码不是 bytecode 或收益证据。
- 默认空 SAM **仍逐元素执行**。Long signatures 段没有跳过；offset/count Int 重载仍调用同一个默认回调版本。不能宣称 callback 对象本身或 capture/scratch 分配都被消除。
- 每个 chunk 的顺序原样保留：interrupt check → scratch.clear → 每元素读取并递增 index → validation callback → reverseBytes/put → bulk CRC → consumeGraphWork。循环边界、chunk 大小、CRC 字节序/覆盖、计费数量和检查时点完全相同。
- property string IDs 的范围和严格升序、ends 严格升序/末尾计数、node ID 范围、trigram 非降序/string ID 范围全部由原 closure 执行。previousStringId/previousEnd/previousPosting 的捕获状态及顺序不变；没有新增线程或共享状态。
- `accept` 不吞或包装异常。require 校验异常、工作预算拒绝和 checkViewInterrupted 的异常路径未改；load 的已有 catch/fallback 策略未改。private class 的内部方法改变，不涉及公共 GraphWorkConsumer 接口，也不涉及既有 noOpParallel SAM 问题。
- 完整 selected posting encounter-order 验证、LIMIT 前 fallback、索引加载/发布/释放、range cache 和 memory admission 全在 diff 之外。

## 现有测试及准确边界

测试均在 `graphite-webgraph/src/test/kotlin/io/johnsonlee/graphite/webgraph/`。

| 测试 | 可证明的行为 |
| --- | --- |
| GraphStoreTest:302 `split raw scans reuse exact matches from the existing persisted index` | :352–441 明确 preferred mapped view 成功、节点/投影/selected 结果；:547–564 修改 CRC 覆盖内容后查同结果、拒绝 mapped view、走 raw fallback。 |
| GraphStoreTest:583 `mapped posting merge falls back before limit when a checksum-valid range is out of order` | 交换前两个 posting IDs 后重算 CRC，LIMIT1 仍得到正确节点/投影并走既定 fallback。保护被选 posting 的完整顺序义务；它不是一项穷举所有 late-position corruption 的测试。 |
| GraphStoreTest:659 `mapped posting validation stays bounded when complete index memory is unavailable` | view 仍可用；range validation cache 有/无内存预算、关闭归还资源的行为。 |
| ParallelDistinctDisjunctionTest:21、:44 | persisted/nonpersisted 两种路径，明确 mapped view 初始化，独立 graph.nodes oracle 比较完整 OR 值、顺序、去重及 selected/null-selected。 |
| GraphStoreTest:2070 `lazy persisted CallSite restore charges sidecar read validation and checksum work`；:2172 `interrupted lazy persisted CallSite restore escapes without publishing the sidecar` | retained restore 的计费/末批拒绝、不发布、资源归还和中断传播。**不是专门对本次 mapped validator 每个 chunk 注入预算/取消的测试。** |

既有测试没有逐项穷举 primitive validator 的全部 string-ID/end/trigram 边界，也没有覆盖在每个 chunk 时点抛出的所有预算/取消异常。对本次严格机械的 callback ABI 替换，现有模块集成回归、上述源码等价核验及实际 bytecode 检查是合适证据，不需要新增镜像 private helper 实现的测试；不能把这个判断写成已拥有穷尽的分支覆盖。本次审查没有宣称测试已运行或通过。

## 假设与性能边界

旧冷四 OR 记录中 validator 的 inclusive CPU 是 11,183/13,830（所有 CPU 80.86%），或 11,183/12,243（应用 CPU 91.34%）。Integer/Long leaf 合计为所有 allocation 采样权重的 94.24%，小部分位于 validator 外；这是同一录制中的 40 个查询窗口，不是 40 次独立 JVM 实验。

这里测试的唯一假设是减少 generic callback 参数的装箱，所有验证工作仍执行。不能把 TLAB 采样权重当作精确可节省字节，也不能把源码回调次数当成分配对象数。JIT 内联/逃逸分析、实际 SAM adapter 与整数缓存仍影响结果；capture Ref、scratch ByteBuffer 和其他装箱不是本次改动范围。

旧34 dense 的既有 cpu3/4/5 窗口中 validator CPU/alloc 样本为0；这既不严格证明零成本，也不能支持本方向可改善旧P95的承诺。需要父任务后续按预设协议验证，不能用冷窗口的高占比替代最终10x及每组进展/无回归门槛。

没有重试 Attempt 098 的 CRC bulk 改法，未删 validation、未新增 postings/tuple/rank 索引或缓存，也没有包含任何调度改动。完整审计字段见 `preimplementation-audit.json`。
