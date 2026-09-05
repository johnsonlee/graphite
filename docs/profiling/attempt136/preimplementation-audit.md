# Attempt136 独立源码审查

结论：当前实际 diff 未发现正常稳定输入下的语义改变；未识别必须新增的测试缺口。这里只支持进入已有正确性与真实数据验证，不代表测试通过或性能收益。

审查对象是独立 clone 的 `MappedWebGraphBackedGraph.kt:579`，实际函数名为 `parallelRawDistinctCallSiteStringProjection`。HEAD 为 `34b45bf798cbe26932e46ab42d6635b534e7fa3e`，改前该文件逐字节等于 frozen main `4e328b01`。唯一 tracked diff 是该循环的 `indices.any` → primitive `while`，没有修改生产其他位置。完整 diff 与 SHA256 见同名 JSON。

## 采样与已有字节码依据

下表只统计包含目标函数栈的 allocation leaf；单位是 sampled TLAB / OutsideTLAB 加权字节，不是精确分配或预计可节省字节。

| 查询 | 录制 | getIndices leaf | IntProgression.iterator leaf | 每节点 lambda CPU leaf |
|---|---|---:|---:|---:|
| targeted | cpu-3 | 1,310,720 | 2,097,152 | 34 |
| targeted | cpu-4 | 1,572,864 | 3,407,872 | 66 |
| targeted | cpu-5 | 2,359,296 | 4,456,448 | 51 |
| dense | cpu-3 | 1,572,864 | 2,621,440 | 16 |
| dense | cpu-4 | 1,572,864 | 2,621,440 | 45 |
| dense | cpu-5 | 1,310,720 | 2,621,440 | 57 |

这些原始文件位于 `/private/tmp/graphite-main-profiling-n50joikp/cpu-{3,4,5}-analysis-v1/collapsed/`，完整文件名记录在 JSON。

以前生成的 `targeted-diagnostic/base-javap.txt` 显示目标 per-node lambda 在 offset 205 调用 getIndices，243 创建 Iterable iterator，265 使用 **IntIterator.nextInt**。因此精确假设是减少 IntRange/IntProgressionIterator 对象与迭代遍历开销，不能称为移除每个谓词下标的 Integer 装箱，也不能称为移除每节点 any closure；any 已内联。

同一 lambda 的 Integer.valueOf 在 offset 573，属于后面的 selected tuple `projectedPropertyIndexes.map`。dense 栈内 Integer leaf 权重 1,572,864 / 1,048,576 / 1,048,576 不应计入本次可消除成本。JIT 可能改变内联和实际分配，静态指令减少不证明延迟会改善。

## 实际 diff 的语义核对

- 每节点 matched 仍从 false 开始，index 从 0 开始；每轮保留原 property → string ID 映射。
- exactMatchSets 非 null 时只做原 set membership；不会读取为空的 matchStates。原 return@any 的单谓词结果现在赋给 matched。
- 无 exact sets 时，缓存 MATCH/MISS、stringMatches 的 transform/mode/expected、未知状态计算与写回完全保留。
- 顺序仍是从首谓词向后，首次 true 后不再计算后续谓词。false 全部遍历后仍返回不匹配。
- 没有新增异常捕获或吞错。accounting.consume、abort/interruption 轮询、flush、worker join 等都在 diff 外。
- 节点物理遍历、selected tuple 过滤、值解码、DISTINCT 去重、encounterOrder 合并及 LIMIT 都未变。

细节边界：新 while 每轮检查 predicates.size，原 indices 在每节点开始时生成固定范围。在稳定的普通 List 上等价；本次没有验证调用期间并发修改或有副作用的自定义 List。原实现已把谓词转换为并行索引/状态列表，不能据此宣称支持这种动态变更。

## 测试与历史边界

`ParallelDistinctDisjunctionTest.kt` SHA256 为 `22f542ebc4021b072b7e40fe58de42fc3ff9698559ba105a01904547c743817b`。

- 第21行：四个不同词 × 四属性，共16谓词，独立词命中、最后分支、全词重叠、重复tuple、实际物理顺序及LIMIT；同时覆盖有sidecar exact sets与无sidecar状态路径。
- 第44行：反序selected、有缺失tuple、LIMIT及null-selected恢复。
- 第62/83行：混合transform、同transform不同词、反转谓词顺序和完整miss。
- 每次断言 raw scan 实际发生、完整values及严格encounterOrder，避免fallback冒充；原GraphStoreTest大测试继续覆盖split projection预算失败、取消及worker退出。

没有穷举16种“词×属性”的独占摆放，也没有计数短路后未读取的谓词。但此diff只替换迭代控制，不改属性映射或matcher，没有识别必须为本次追加的正确性用例。当前build由父任务负责，本审查未运行任何测试，不能在这里报告通过。

Attempt130 (`f0dbeb6c`) 修改的是另一处 `rawCallSiteStringProjection`（当前801行起、858行已是while），并改用primitive node-id iterator。本次没有重改该函数；相同技术手法的历史结论不能替代本次真实数据验收。

本审查只写 `preimplementation-audit.md` / `.json`，未改生产或测试、未启动Java/性能任务。

## matched 是否引入 BooleanRef

`withRawCallSiteStringIds` 是 private inline 函数，action 直接调用，没有 noinline 或逃逸；matched 声明在每节点 lambda 内。已有 main per-node 字节码没有 BooleanRef，原 any 结果通过 offset463 的 istore15 写入 primitive 局部变量，468 的 iload15 读取。方法签名里的 Ref$IntRef 是 inspected 计数，不是 matched。

因此不能按普通非inline闭包推断新 while 每个谓词会读写 BooleanRef.element；源码没有这类捕获边界。无需仅为此额外引入 predicateMatched。当前审查没有重新生成 candidate 字节码，后续构建验证仍由父任务负责。
